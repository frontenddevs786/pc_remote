package com.example.pcremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.LinkedList
import java.util.UUID
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Bluetooth Low Energy "HID over GATT" keyboard + mouse. Windows 10/11 supports this natively.
 * Used when the phone's classic HID-device profile is unavailable (e.g. some vivo phones).
 */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
class BleHidController(
    private val context: Context,
    private val onStatus: (String) -> Unit
) : Hid {

    private class Report(val id: Int, val data: ByteArray)

    companion object {
        private const val ID_KEYBOARD = 1
        private const val ID_MOUSE = 2
        private const val ID_CONSUMER = 3

        private fun u(short: Int): UUID =
            UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", short))

        private val HID_SERVICE = u(0x1812)
        private val BATTERY_SERVICE = u(0x180F)
        private val DEVICE_INFO_SERVICE = u(0x180A)

        private val HID_INFO = u(0x2A4A)
        private val REPORT_MAP = u(0x2A4B)
        private val CONTROL_POINT = u(0x2A4C)
        private val REPORT = u(0x2A4D)
        private val PROTOCOL_MODE = u(0x2A4E)
        private val BATTERY_LEVEL = u(0x2A19)
        private val MANUFACTURER = u(0x2A29)
        private val PNP_ID = u(0x2A50)

        private val CCCD = u(0x2902)
        private val REPORT_REF = u(0x2908)
    }

    override var connectionListener: ((Boolean) -> Unit)? = null
    override val isConnected: Boolean get() = host != null
    private val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var running = false
    @Volatile private var wantAdvertising = false

    private val subscribed = java.util.Collections.synchronizedSet(HashSet<Int>())
    private val pendingServices = LinkedList<BluetoothGattService>()
    private val queue = LinkedBlockingDeque<Report>()
    private val sendSem = Semaphore(1)
    private var worker: Thread? = null

    private lateinit var kbChar: BluetoothGattCharacteristic
    private lateinit var mouseChar: BluetoothGattCharacteristic
    private lateinit var consChar: BluetoothGattCharacteristic

    private val manager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    // ---------- lifecycle ----------

    override fun start() {
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Turn Bluetooth on, then reopen the app")
            return
        }
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            onStatus("This phone cannot advertise over Bluetooth LE, so BLE mode will not work")
            return
        }
        onStatus("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (BLE mode)")
        val srv = manager.openGattServer(context, gattCallback)
        if (srv == null) {
            onStatus("Could not open the Bluetooth GATT server")
            return
        }
        server = srv
        pendingServices.clear()
        pendingServices.addAll(buildServices())
        running = true
        try {
            val paired = adapter.bondedDevices?.joinToString { "${it.name ?: "?"} ${it.address}" }
            onStatus("Paired devices: ${paired ?: "none"}; remembered PC: ${prefs.getString("ble_last_host_name", "none")}")
        } catch (e: Exception) {
            // ignore
        }
        startWorker()
        addNextService()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 30_000)
    }

    override fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        wantAdvertising = false
        try {
            advertiser?.stopAdvertising(advCallback)
        } catch (e: Exception) {
            // ignore
        }
        try {
            server?.close()
        } catch (e: Exception) {
            // ignore
        }
        server = null
        host = null
        subscribed.clear()
        queue.clear()
    }

    private fun addNextService() {
        val s = pendingServices.poll()
        if (s == null) {
            onStatus("BLE keyboard ready")
            advertise()
            reconnect()      // call back the PC that was connected last time
            return
        }
        val ok = server?.addService(s) ?: false
        if (!ok) onStatus("addService failed for ${s.uuid}")
    }

    // ---------- GATT layout ----------

    private fun reportChar(): BluetoothGattCharacteristic {
        val c = BluetoothGattCharacteristic(
            REPORT,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        )
        c.addDescriptor(BluetoothGattDescriptor(REPORT_REF, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED))
        c.addDescriptor(
            BluetoothGattDescriptor(
                CCCD,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
            )
        )
        return c
    }

    private fun buildServices(): List<BluetoothGattService> {
        val readEnc = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        val writeEnc = BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        val read = BluetoothGattCharacteristic.PROPERTY_READ

        // Device information
        val dis = BluetoothGattService(DEVICE_INFO_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        dis.addCharacteristic(BluetoothGattCharacteristic(MANUFACTURER, read, BluetoothGattCharacteristic.PERMISSION_READ))
        dis.addCharacteristic(BluetoothGattCharacteristic(PNP_ID, read, BluetoothGattCharacteristic.PERMISSION_READ))

        // Battery
        val bat = BluetoothGattService(BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val level = BluetoothGattCharacteristic(
            BATTERY_LEVEL,
            read or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        level.addDescriptor(
            BluetoothGattDescriptor(
                CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        bat.addCharacteristic(level)

        // HID
        val hidSvc = BluetoothGattService(HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        hidSvc.addCharacteristic(BluetoothGattCharacteristic(HID_INFO, read, readEnc))
        hidSvc.addCharacteristic(BluetoothGattCharacteristic(REPORT_MAP, read, readEnc))
        hidSvc.addCharacteristic(
            BluetoothGattCharacteristic(CONTROL_POINT, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, writeEnc)
        )
        hidSvc.addCharacteristic(
            BluetoothGattCharacteristic(
                PROTOCOL_MODE,
                read or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                readEnc or writeEnc
            )
        )
        kbChar = reportChar()
        mouseChar = reportChar()
        consChar = reportChar()
        hidSvc.addCharacteristic(kbChar)
        hidSvc.addCharacteristic(mouseChar)
        hidSvc.addCharacteristic(consChar)

        return listOf(dis, bat, hidSvc)
    }

    private fun idFor(c: BluetoothGattCharacteristic?): Int? = when {
        c === kbChar -> ID_KEYBOARD
        c === mouseChar -> ID_MOUSE
        c === consChar -> ID_CONSUMER
        else -> null
    }

    private fun chFor(id: Int): BluetoothGattCharacteristic? = when (id) {
        ID_KEYBOARD -> kbChar
        ID_MOUSE -> mouseChar
        ID_CONSUMER -> consChar
        else -> null
    }

    private fun valueFor(c: BluetoothGattCharacteristic): ByteArray = when {
        c.uuid == REPORT_MAP -> HidController.DESCRIPTOR
        c.uuid == HID_INFO -> byteArrayOf(0x11, 0x01, 0x00, 0x02) // HID 1.11, country 0, normally connectable
        c.uuid == PROTOCOL_MODE -> byteArrayOf(1)                 // report protocol
        c.uuid == BATTERY_LEVEL -> byteArrayOf(100)
        c.uuid == MANUFACTURER -> "PCRemote".toByteArray()
        c.uuid == PNP_ID -> byteArrayOf(0x02, 0x6B, 0x1D, 0x46, 0x02, 0x00, 0x01)
        c === kbChar -> ByteArray(8)
        c === mouseChar -> ByteArray(4)
        c === consChar -> ByteArray(2)
        else -> ByteArray(0)
    }

    // ---------- GATT server callbacks ----------

    private fun respond(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray) {
        if (offset > value.size) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, ByteArray(0))
        } else {
            server?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                value.copyOfRange(offset, value.size)
            )
        }
    }

    private fun releaseSem() {
        if (sendSem.availablePermits() < 1) sendSem.release()
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status != BluetoothGatt.GATT_SUCCESS) onStatus("Service ${service.uuid} failed, status $status")
            addNextService()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val label = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
                else -> "state $newState"
            }
            onStatus("${device.name ?: device.address}: $label (status $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                host = device
                prefs.edit()
                    .putString("ble_last_host", device.address)
                    .putString("ble_last_host_name", device.name ?: device.address)
                    .apply()
                connectionListener?.invoke(true)
                try {
                    advertiser?.stopAdvertising(advCallback)
                } catch (e: Exception) {
                    // ignore
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (host?.address == device.address) {
                    host = null
                    subscribed.clear()
                    queue.clear()
                    connectionListener?.invoke(false)
                    if (wantAdvertising && running) advertise()
                    reconnect()      // unexpected drop: call the PC back
                } else if (wantAdvertising && running && host == null) {
                    advertise()
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            respond(device, requestId, offset, valueFor(characteristic))
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor
        ) {
            val value = when (descriptor.uuid) {
                REPORT_REF -> byteArrayOf((idFor(descriptor.characteristic) ?: 0).toByte(), 1) // id, input report
                CCCD -> {
                    val id = idFor(descriptor.characteristic)
                    if (id != null && subscribed.contains(id)) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    }
                }
                else -> ByteArray(0)
            }
            respond(device, requestId, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD) {
                val id = idFor(descriptor.characteristic)
                val on = value != null && value.isNotEmpty() && value[0].toInt() == 1
                if (id != null) {
                    if (on) subscribed.add(id) else subscribed.remove(id)
                    onStatus("PC ${if (on) "enabled" else "disabled"} input report $id")
                }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value ?: ByteArray(0))
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value ?: ByteArray(0))
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            releaseSem()
        }
    }

    // ---------- reconnect to the remembered PC ----------

    /**
     * In BLE mode the PC (the "central") opens the connection, so all the phone can do is stay
     * visible. Windows reconnects to a paired device by itself when it sees it advertising.
     */
    override fun reconnect() {
        if (!running || host != null) return
        val name = prefs.getString("ble_last_host_name", null)
        if (name != null) onStatus("Waiting for $name to reconnect...")
        advertise()
    }

    /** Re-starts advertising now and then, in case the system silently stopped it. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (host == null && wantAdvertising) {
                onStatus("(re-advertising)")
                advertise()
            }
            handler.postDelayed(this, 30_000)
        }
    }

    // ---------- advertising ----------

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            val name = try { manager.adapter?.name } catch (e: Exception) { null }
            onStatus("Visible to the PC. On Windows: Add device > Bluetooth > pick '${name ?: "this phone"}'")
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED) onStatus("Advertising failed, code $errorCode")
        }
    }

    override fun advertise() {
        val adv = advertiser
        if (adv == null || !running) {
            onStatus("Not ready yet")
            return
        }
        wantAdvertising = true
        try {
            adv.stopAdvertising(advCallback)
        } catch (e: Exception) {
            // ignore
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(HID_SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        adv.startAdvertising(settings, data, scanResponse, advCallback)
    }

    // ---------- sending reports ----------

    private fun startWorker() {
        worker = Thread {
            while (running) {
                val r: Report? = try {
                    queue.poll(500, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                }
                if (r != null) deliver(r)
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun deliver(r: Report) {
        val dev = host ?: return
        val ch = chFor(r.id) ?: return
        // Not gated on 'subscribed': a bonded PC may keep its notification setting across reconnects
        // (or after this app restarted) and then never rewrites it, which would silence every report.
        sendSem.tryAcquire(100, TimeUnit.MILLISECONDS)
        ch.setValue(r.data)
        val ok = server?.notifyCharacteristicChanged(dev, ch, false) ?: false
        if (!ok) releaseSem()
    }

    private fun mouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int) =
        Report(ID_MOUSE, byteArrayOf(buttons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte()))

    override fun mouse(dx: Int, dy: Int, buttons: Int, wheel: Int) {
        if (host == null) return
        synchronized(queue) {
            // Merge consecutive movements so a slow link does not build up a backlog.
            val last = queue.peekLast()
            if (last != null && last.id == ID_MOUSE && (last.data[0].toInt() and 0xFF) == buttons) {
                val mdx = last.data[1] + dx
                val mdy = last.data[2] + dy
                val mw = last.data[3] + wheel
                if (mdx in -127..127 && mdy in -127..127 && mw in -127..127 && queue.pollLast() === last) {
                    queue.offerLast(mouseReport(buttons, mdx, mdy, mw))
                    return
                }
            }
            queue.offerLast(
                mouseReport(buttons, dx.coerceIn(-127, 127), dy.coerceIn(-127, 127), wheel.coerceIn(-127, 127))
            )
        }
    }

    override fun press(usage: Int, modifiers: Int) {
        if (host == null) return
        queue.offer(Report(ID_KEYBOARD, byteArrayOf(modifiers.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0)))
        queue.offer(Report(ID_KEYBOARD, ByteArray(8)))
    }

    override fun typeChar(c: Char, extraMods: Int): Boolean {
        val (mods, usage) = HidController.lookup(c) ?: return false
        press(usage, mods or extraMods)
        return true
    }

    override fun consumer(usage: Int) {
        if (host == null) return
        queue.offer(Report(ID_CONSUMER, byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte())))
        queue.offer(Report(ID_CONSUMER, byteArrayOf(0, 0)))
    }
}
