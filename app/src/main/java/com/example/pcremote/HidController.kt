package com.example.pcremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Makes the phone act as a Bluetooth keyboard + mouse + media-key device.
 * Windows sees a normal Bluetooth HID device, so no PC software is needed.
 */
@SuppressLint("MissingPermission")
class HidController(
    private val context: Context,
    private val onStatus: (String) -> Unit
) : Hid {
    companion object {
        const val ID_KEYBOARD = 1
        const val ID_MOUSE = 2
        const val ID_CONSUMER = 3

        const val MOD_CTRL = 0x01
        const val MOD_SHIFT = 0x02
        const val MOD_ALT = 0x04
        const val MOD_WIN = 0x08

        // HID report descriptor: keyboard (id 1), mouse (id 2), consumer/media keys (id 3)
        internal val DESCRIPTOR: ByteArray = intArrayOf(
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

    override var connectionListener: ((Boolean) -> Unit)? = null
    private val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile var isRegistered = false
        private set

    private fun stateName(state: Int) = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
        else -> "state $state"
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isRegistered = registered
            onStatus("App registered=$registered plugged=${pluggedDevice?.name}")
            if (registered) {
                onStatus("READY: now pair/connect from the PC")
                if (pluggedDevice != null) {
                    // The system already has a live link (e.g. app was restarted): reuse it.
                    host = pluggedDevice
                    connectionListener?.invoke(true)
                } else {
                    handler.postDelayed({ reconnect() }, 1000)
                }
            } else {
                handler.removeCallbacks(retry)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            onStatus("${device.name ?: device.address}: ${stateName(state)}")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    handler.removeCallbacks(retry)
                    attempts = 0
                    host = device
                    prefs.edit()
                        .putString("last_host", device.address)
                        .putString("last_host_name", device.name ?: device.address)
                        .apply()
                    connectionListener?.invoke(true)
                }
                BluetoothProfile.STATE_DISCONNECTED -> if (host?.address == device.address) {
                    host = null
                    connectionListener?.invoke(false)
                    // Drop (out of range, PC slept, PC restarted): keep trying to get the link back,
                    // like a Bluetooth headset does. The PC may also reconnect by itself first.
                    attempts = 0
                    scheduleReconnect(2_000)
                }
            }
        }

        // Windows asks for these right after connecting; unanswered requests can make it drop the link.
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            onStatus("PC: GET_REPORT id=$id")
            val size = when (id.toInt()) {
                ID_KEYBOARD -> 8
                ID_MOUSE -> 4
                ID_CONSUMER -> 2
                else -> 0
            }
            if (size > 0) {
                hid?.replyReport(device, type, id, ByteArray(size))
            } else {
                hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            onStatus("PC: SET_REPORT id=$id")
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            onStatus("PC: SET_PROTOCOL $protocol")
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            onStatus("PC removed the virtual cable")
            prefs.edit().remove("last_host").remove("last_host_name").apply()
            handler.removeCallbacks(retry)
            host = null
            connectionListener?.invoke(false)
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
            onStatus("registerApp() returned $ok")
            if (!ok) onStatus("Could not register as a Bluetooth keyboard (is another app using it?)")
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            host = null
        }
    }

    override fun start() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            onStatus("This device has no Bluetooth")
            return
        }
        if (!adapter.isEnabled) {
            onStatus("Please turn Bluetooth on, then reopen the app")
            return
        }
        onStatus("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
        val ok = adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        onStatus("getProfileProxy(HID_DEVICE) returned $ok")
        if (!ok) onStatus("This phone does not support the Bluetooth HID device profile")
    }

    override fun stop() {
        handler.removeCallbacksAndMessages(null)
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

    override fun connectTo(device: BluetoothDevice) {
        val h = hid
        if (h == null) {
            onStatus("Not ready: HID service not connected yet")
            return
        }
        if (!isRegistered) {
            onStatus("Not ready: app is not registered as a keyboard")
            return
        }
        val ok = h.connect(device)
        if (ok) onStatus("Connecting to ${device.name ?: device.address}...")
        else onStatus("Could not connect to ${device.name ?: device.address}")
    }

    override val isConnected: Boolean get() = host != null

    private var attempts = 0
    private val retry = Runnable { attemptReconnect() }

    private fun scheduleReconnect(delayMs: Long) {
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, delayMs)
    }

    /**
     * Reconnect to the PC that was connected last time (if it is still paired). Keeps retrying in the
     * background (every 5 s at first, then every 30 s) until the link is up, so the PC is picked up
     * again whenever it wakes up or comes back into range, with no tapping needed.
     */
    override fun reconnect() {
        attempts = 0
        attemptReconnect()
    }

    private fun attemptReconnect() {
        val h = hid ?: return
        if (!isRegistered || host != null) return
        val addr = prefs.getString("last_host", null) ?: return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        if (!adapter.isEnabled) return
        val device = adapter.bondedDevices?.firstOrNull { it.address == addr }
        if (device == null) {
            // The PC was un-paired on the phone: nothing to reconnect to until it is paired again.
            onStatus("Remembered PC is no longer paired. Pair it again from the PC.")
            prefs.edit().remove("last_host").remove("last_host_name").apply()
            return
        }
        when (h.getConnectionState(device)) {
            BluetoothProfile.STATE_CONNECTED -> {
                host = device
                connectionListener?.invoke(true)
                return
            }
            BluetoothProfile.STATE_CONNECTING -> {}
            else -> {
                attempts++
                if (attempts == 1) onStatus("Reconnecting to ${device.name ?: device.address}...")
                h.connect(device)
            }
        }
        scheduleReconnect(if (attempts < 12) 5_000L else 30_000L)
    }

    private fun send(id: Int, data: ByteArray) {
        val h = host ?: return
        hid?.sendReport(h, id, data)
    }

    override fun mouse(dx: Int, dy: Int, buttons: Int, wheel: Int) {
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
    override fun press(usage: Int, modifiers: Int) {
        send(ID_KEYBOARD, byteArrayOf(modifiers.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0))
        send(ID_KEYBOARD, ByteArray(8))
    }

    override fun typeChar(c: Char, extraMods: Int): Boolean {
        val (mods, usage) = lookup(c) ?: return false
        press(usage, mods or extraMods)
        return true
    }

    /** Media keys: 0xE9 vol+, 0xEA vol-, 0xE2 mute, 0xCD play/pause, 0xB5 next, 0xB6 prev. */
    override fun consumer(usage: Int) {
        send(ID_CONSUMER, byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte()))
        send(ID_CONSUMER, byteArrayOf(0, 0))
    }
}
