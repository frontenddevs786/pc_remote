package com.example.pcremote

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Used by the UI before the Bluetooth backend exists (e.g. while a permission dialog is open). */
object NoopHid : Hid {
    override var connectionListener: ((Boolean) -> Unit)? = null
    override val isConnected: Boolean = false
    override fun start() {}
    override fun stop() {}
    override fun mouse(dx: Int, dy: Int, buttons: Int, wheel: Int) {}
    override fun press(usage: Int, modifiers: Int) {}
    override fun typeChar(c: Char, extraMods: Int): Boolean = false
    override fun consumer(usage: Int) {}
}

/**
 * Process-wide owner of the Bluetooth backend. It is NOT tied to the Activity, so minimizing or
 * closing the app does not drop the PC connection. [HidService] keeps the process alive.
 */
object HidHost {
    @Volatile var backend: Hid? = null
        private set
    @Volatile var connected = false
        private set
    @Volatile var bleMode = true
        private set

    /** Set by the Activity while it is on screen; cleared when it goes away. */
    @Volatile var uiConnection: ((Boolean) -> Unit)? = null
    @Volatile var uiStatus: ((String) -> Unit)? = null

    /** Set by the service to refresh its notification. */
    @Volatile var notifyService: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    // ---------- persistent connection log (survives the app being killed) ----------

    private var appCtx: Context? = null
    private var attached = false
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** Call from the Activity and the Service. The first call in a new process writes a marker line. */
    @Synchronized
    fun attach(c: Context) {
        if (appCtx == null) appCtx = c.applicationContext
        if (!attached) {
            attached = true
            log("=== app process started ===")
        }
    }

    @Synchronized
    fun log(msg: String) {
        val c = appCtx ?: return
        try {
            val f = File(c.filesDir, "pcremote.log")
            if (f.length() > 60_000) {
                f.writeText(f.readText().takeLast(30_000).substringAfter('\n'))
            }
            f.appendText("${fmt.format(Date())}  $msg\n")
        } catch (e: Exception) {
            // logging must never crash the app
        }
    }

    @Synchronized
    fun readLog(): String = try {
        appCtx?.let { File(it.filesDir, "pcremote.log").takeIf { f -> f.exists() }?.readText() }.orEmpty()
    } catch (e: Exception) {
        ""
    }

    @Synchronized
    fun clearLog() {
        try {
            appCtx?.let { File(it.filesDir, "pcremote.log").delete() }
        } catch (e: Exception) {
            // ignore
        }
    }

    /** Returns the running backend for [ble], creating (and starting) it if needed. Main thread only. */
    fun ensure(app: Context, ble: Boolean): Hid? {
        val cur = backend
        if (cur != null && bleMode == ble) return cur

        attach(app)
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            main.post { uiStatus?.invoke("Turn Bluetooth on, then reopen the app") }
            return null
        }

        cur?.stop()
        setConnected(false)
        bleMode = ble
        val onStatus: (String) -> Unit = { msg ->
            log(msg)
            main.post { uiStatus?.invoke(msg) }
        }
        log("Starting ${if (ble) "BLE" else "Classic"} backend")
        val h: Hid = if (ble) BleHidController(app, onStatus) else HidController(app, onStatus)
        h.connectionListener = { c -> setConnected(c) }
        backend = h
        h.start()
        return h
    }

    fun switchMode(app: Context, ble: Boolean): Hid? {
        backend?.stop()
        backend = null
        setConnected(false)
        return ensure(app, ble)
    }

    /** Stops Bluetooth completely (notification "Stop" button, or Bluetooth turned off). */
    fun shutdown() {
        log("Backend shut down")
        backend?.stop()
        backend = null
        setConnected(false)
    }

    private fun setConnected(c: Boolean) {
        connected = c
        log("Link ${if (c) "CONNECTED" else "not connected"}")
        main.post {
            uiConnection?.invoke(c)
            notifyService?.invoke()
        }
    }
}
