package com.example.pcremote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Foreground service that keeps the Bluetooth connection alive while the app is open or minimized.
 *
 * It lives and dies with the app: when the app is closed (swiped out of recent apps, or its process is
 * killed) or the notification's Stop button is pressed, the connection is closed, the notification is
 * removed and nothing restarts it. The actual Bluetooth work lives in [HidHost].
 */
class HidService : Service() {

    companion object {
        private const val CHANNEL = "pcremote_link"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.example.pcremote.STOP"

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, HidService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                // Not allowed to start right now; the Activity will retry next time it is shown.
                HidHost.log("Service start refused: $e")
            }
        }
    }

    /** True once the service is shutting down, so nothing can show the notification again. */
    @Volatile private var stopping = false
    private val beat = Handler(Looper.getMainLooper())

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (stopping) return
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_TURNING_OFF -> HidHost.shutdown()
                        BluetoothAdapter.STATE_ON -> startBackendIfAllowed()
                    }
                // The PC just came into range / woke up: reconnect right away.
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (!HidHost.connected) HidHost.backend?.reconnect()
                }
            }
        }
    }

    /** Every minute: rebuild the backend if it silently died, and retry the PC if not connected. */
    private val heartbeat = object : Runnable {
        override fun run() {
            if (stopping) return
            try {
                if (hasBluetoothPermission()) {
                    if (HidHost.backend == null) startBackendIfAllowed()
                    else if (!HidHost.connected) HidHost.backend?.reconnect()
                }
            } catch (e: Exception) {
                HidHost.log("Heartbeat error: $e")
            }
            beat.postDelayed(this, 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        HidHost.attach(applicationContext)
        HidHost.log("Service onCreate")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "PC connection", NotificationManager.IMPORTANCE_LOW)
        )
        HidHost.notifyService = { refreshNotification() }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        registerReceiver(btReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        HidHost.log("Service onStartCommand (${intent?.action ?: "start"})")
        if (intent?.action == ACTION_STOP) {
            quit("Stop pressed")
            return START_NOT_STICKY
        }
        if (intent == null) {
            // Restarted by the system after the app was killed: the app is closed, so do not come back.
            quit("restart after kill ignored")
            return START_NOT_STICKY
        }
        if (!hasBluetoothPermission()) {
            // Cannot run as a connected-device service without the permission.
            quit("no Bluetooth permission")
            return START_NOT_STICKY
        }
        stopping = false
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            HidHost.log("startForeground failed: $e")
            quit("startForeground failed")
            return START_NOT_STICKY
        }
        startBackendIfAllowed()
        beat.removeCallbacks(heartbeat)
        beat.postDelayed(heartbeat, 60_000)
        return START_NOT_STICKY
    }

    /** The app was closed from the recent-apps list: end the connection and the notification. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        quit("app closed (removed from recents)")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        HidHost.log("Service onDestroy")
        stopping = true
        HidHost.notifyService = null
        beat.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(btReceiver)
        } catch (e: Exception) {
            // ignore
        }
        // Whatever the reason the service is going away, the link and the notification go with it.
        HidHost.shutdown()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        super.onDestroy()
    }

    /**
     * Full stop. [stopping] and the cleared callback come first: HidHost.shutdown() reports "not connected"
     * asynchronously, and that report used to re-post the notification right after it was removed, which
     * looked like the service restarting.
     */
    private fun quit(reason: String) {
        HidHost.log("Service quit: $reason")
        stopping = true
        HidHost.notifyService = null
        beat.removeCallbacksAndMessages(null)
        HidHost.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        stopSelf()
    }

    // ---------- helpers ----------

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED)

    private fun startBackendIfAllowed() {
        if (stopping) return
        if (HidHost.backend == null && hasBluetoothPermission()) {
            val ble = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("ble", true)
            HidHost.ensure(applicationContext, ble)
        }
    }

    private fun refreshNotification() {
        if (stopping) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HidService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("PC Remote")
            .setContentText(if (HidHost.connected) "Connected to PC" else "Ready, waiting for PC")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }
}
