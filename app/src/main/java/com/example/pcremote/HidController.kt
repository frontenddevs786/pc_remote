package com.example.pcremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.concurrent.Executors

/**
 * Makes the phone act as a Bluetooth keyboard + mouse + media-key device.
 * Windows sees a normal Bluetooth HID device, so no PC software is needed.
 */
@SuppressLint("MissingPermission")
class HidController(
    private val context: Context,
    private val onStatus: (String) -> Unit
) {
    companion object {
        const val ID_KEYBOARD = 1
        const val ID_MOUSE = 2
        const val ID_CONSUMER = 3

        const val MOD_CTRL = 0x01
        const val MOD_SHIFT = 0x02
        const val MOD_ALT = 0x04
        const val MOD_WIN = 0x08

        // HID report descriptor: keyboard (id 1), mouse (id 2), consumer/media keys (id 3)
        private val DESCRIPTOR: ByteArray = intArrayOf(
            // ---- Keyboard ----
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
            // ---- Mouse (3 buttons, x, y, wheel) ----
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x02,
            0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
            0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
            0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
            0xC0, 0xC0,
            // ---- Consumer control (volume / media) ----
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x03,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03,
            0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
            0xC0
        ).map { it.toByte() }.toByteArray()

        // US-layout symbols without Shift
        private val SYMBOLS: Map<Char, Int> = mapOf(
            '-' to 0x2D, '=' to 0x2E, '[' to 0x2F, ']' to 0x30, '\\' to 0x31,
            ';' to 0x33, '\'' to 0x34, '`' to 0x35, ',' to 0x36, '.' to 0x37, '/' to 0x38
        )

        // US-layout symbols that need Shift
        private val SHIFTED: Map<Char, Int> = mapOf(
            '_' to 0x2D, '+' to 0x2E, '{' to 0x2F, '}' to 0x30, '|' to 0x31,
            ':' to 0x33, '"' to 0x34, '~' to 0x35, '<' to 0x36, '>' to 0x37, '?' to 0x38
        )

        /** Returns (modifiers, usage) for a character, or null if unsupported. */
        fun lookup(c: Char): Pair<Int, Int>? {
            return when (c) {
                in 'a'..'z' -> 0 to (0x04 + (c - 'a'))
                in 'A'..'Z' -> MOD_SHIFT to (0x04 + (c - 'A'))
                in '1'..'9' -> 0 to (0x1E + (c - '1'))
                '0' -> 0 to 0x27
                ' ' -> 0 to 0x2C
                '\n' -> 0 to 0x28
                '\t' -> 0 to 0x2B
                else -> {
                    val i = "!@#$%^&*()".indexOf(c)
                    when {
                        i >= 0 -> MOD_SHIFT to (0x1E + i)
                        SYMBOLS.containsKey(c) -> 0 to SYMBOLS.getValue(c)
                        SHIFTED.containsKey(c) -> MOD_SHIFT to SHIFTED.getValue(c)
                        else -> null
                    }
                }
            }
        }
    }

    private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (registered) {
                onStatus("Ready. Tap 'Discoverable', then on your PC: Settings > Bluetooth > Add device > Bluetooth > pick this phone.")
            } else {
                onStatus("Bluetooth keyboard not registered")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    onStatus("Connected to ${device.name ?: device.address}")
                }
                BluetoothProfile.STATE_CONNECTING -> onStatus("Connecting...")
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device.address) host = null
                    onStatus("Disconnected. Tap 'Connect' to reconnect.")
                }
            }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val h = proxy as BluetoothHidDevice
            hid = h
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "PC Remote",
                "Phone as keyboard and mouse",
                "PCRemote",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                DESCRIPTOR
            )
            val ok = h.registerApp(sdp, null, null, executor, callback)
            if (!ok) onStatus("Could not register as a Bluetooth keyboard (is another app using it?)")
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            host = null
        }
    }

    fun start() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            onStatus("This device has no Bluetooth")
            return
        }
        if (!adapter.isEnabled) {
            onStatus("Please turn Bluetooth on, then reopen the app")
            return
        }
        val ok = adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        if (!ok) onStatus("This phone does not support the Bluetooth HID device profile")
    }

    fun stop() {
        try {
            hid?.unregisterApp()
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        } catch (e: Exception) {
            // ignore: shutting down anyway
        }
        hid = null
        host = null
    }

    fun connectTo(device: BluetoothDevice) {
        hid?.connect(device)
    }

    val isConnected: Boolean get() = host != null

    private fun send(id: Int, data: ByteArray) {
        val h = host ?: return
        hid?.sendReport(h, id, data)
    }

    fun mouse(dx: Int, dy: Int, buttons: Int = 0, wheel: Int = 0) {
        send(
            ID_MOUSE,
            byteArrayOf(
                buttons.toByte(),
                dx.coerceIn(-127, 127).toByte(),
                dy.coerceIn(-127, 127).toByte(),
                wheel.coerceIn(-127, 127).toByte()
            )
        )
    }

    /** Press and release one key (usage 0 = modifier-only, e.g. the Windows key). */
    fun press(usage: Int, modifiers: Int = 0) {
        send(ID_KEYBOARD, byteArrayOf(modifiers.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0))
        send(ID_KEYBOARD, ByteArray(8))
    }

    fun typeChar(c: Char, extraMods: Int = 0): Boolean {
        val (mods, usage) = lookup(c) ?: return false
        press(usage, mods or extraMods)
        return true
    }

    /** Media keys: 0xE9 vol+, 0xEA vol-, 0xE2 mute, 0xCD play/pause, 0xB5 next, 0xB6 prev. */
    fun consumer(usage: Int) {
        send(ID_CONSUMER, byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte()))
        send(ID_CONSUMER, byteArrayOf(0, 0))
    }
}
