package com.example.pcremote

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

/**
 * The screen's view of the Bluetooth link. The screen runs in its own process (":ui") so that
 * closing the app only ends that process, while [HidService] and the Bluetooth link live on in the
 * main process. Everything here just forwards to the service.
 */
object HidClient : Hid {
    override var connectionListener: ((Boolean) -> Unit)? = null
    override val isConnected: Boolean get() = connected

    @Volatile var connected = false
        private set
    @Volatile var running = false
        private set
    @Volatile var lastHostName: String? = null
        private set
    @Volatile var hasLastHost = false
        private set

    var uiConnection: ((Boolean) -> Unit)? = null
    var uiStatus: ((String) -> Unit)? = null

    private var cfgBle = true
    private var cfgAwake = true
    private var out: Messenger? = null
    private var bound = false
    private val pending = ArrayList<Message>()
    private val main = Handler(Looper.getMainLooper())

    private val inbox = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(m: Message) {
            when (m.what) {
                Ipc.STATE -> {
                    connected = m.arg1 == 1
                    val d = m.data
                    running = d.getBoolean("running")
                    lastHostName = d.getString("name")
                    hasLastHost = d.getBoolean("hasHost")
                    uiConnection?.invoke(connected)
                }
                Ipc.STATUS -> uiStatus?.invoke(m.data.getString("m") ?: "")
            }
        }
    })

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val o = Messenger(binder)
            out = o
            try {
                val reg = Message.obtain(null, Ipc.REGISTER)
                reg.replyTo = inbox
                o.send(reg)
            } catch (e: Exception) {
                HidHost.log("IPC register failed: $e")
            }
            sendConfig()
            val queued = ArrayList(pending)
            pending.clear()
            for (m in queued) {
                try {
                    o.send(m)
                } catch (e: Exception) {
                    // service went away again
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            out = null
            connected = false
            running = false
            main.post { uiConnection?.invoke(false) }
        }
    }

    fun bind(ctx: Context) {
        if (bound) return
        bound = ctx.applicationContext.bindService(
            Intent(ctx.applicationContext, HidService::class.java), conn, Context.BIND_AUTO_CREATE
        )
    }

    fun unbind(ctx: Context) {
        if (!bound) return
        post(Ipc.UNREGISTER, queue = false)
        try {
            ctx.applicationContext.unbindService(conn)
        } catch (e: Exception) {
            // already unbound
        }
        bound = false
        out = null
        pending.clear()
    }

    private fun post(what: Int, a1: Int = 0, a2: Int = 0, data: Bundle? = null, queue: Boolean = true) {
        val m = Message.obtain(null, what, a1, a2)
        if (data != null) m.data = data
        val o = out
        if (o == null) {
            if (queue && pending.size < 50) pending.add(m)
            return
        }
        try {
            o.send(m)
        } catch (e: Exception) {
            // service not reachable right now
        }
    }

    private fun sendConfig() {
        post(Ipc.CONFIG, if (cfgBle) 1 else 0, if (cfgAwake) 1 else 0, queue = false)
    }

    // ---------- settings forwarded to the service ----------

    fun configure(ble: Boolean, keepAwake: Boolean) {
        cfgBle = ble
        cfgAwake = keepAwake
        if (out != null) sendConfig()
    }

    fun ensure(ble: Boolean) {
        cfgBle = ble
        post(Ipc.ENSURE, if (ble) 1 else 0)
    }

    fun switchMode(ble: Boolean) {
        cfgBle = ble
        connected = false
        post(Ipc.SWITCH, if (ble) 1 else 0)
    }

    // ---------- Hid ----------

    override fun start() {}
    override fun stop() {}

    override fun advertise() = post(Ipc.ADVERTISE)
    override fun reconnect() = post(Ipc.RECONNECT)

    override fun connectTo(device: BluetoothDevice) {
        post(Ipc.CONNECT_TO, data = Bundle().apply { putParcelable("d", device) })
    }

    override fun mouse(dx: Int, dy: Int, buttons: Int, wheel: Int) {
        val x = dx.coerceIn(-127, 127)
        val y = dy.coerceIn(-127, 127)
        val w = wheel.coerceIn(-127, 127)
        post(Ipc.MOUSE, (x and 0xFFFF) or (y shl 16), (buttons and 0xFF) or ((w and 0xFF) shl 8), queue = false)
    }

    override fun press(usage: Int, modifiers: Int) = post(Ipc.PRESS, usage, modifiers, queue = false)

    override fun typeChar(c: Char, extraMods: Int): Boolean {
        if (HidController.lookup(c) == null) return false
        post(Ipc.TYPE, c.code, extraMods, queue = false)
        return true
    }

    override fun consumer(usage: Int) = post(Ipc.CONSUMER, usage, queue = false)
}
