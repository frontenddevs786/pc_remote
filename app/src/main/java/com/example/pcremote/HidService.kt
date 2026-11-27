package com.example.pcremote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps the Bluetooth connection alive while the app is minimized or
 * closed. The actual Bluetooth work lives in [HidHost]; this just stops Android from killing it.
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
            }
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_TURNING_OFF -> HidHost.shutdown()
                BluetoothAdapter.STATE_ON -> startBackendIfAllowed()
            }
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
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        HidHost.log("Service onStartCommand action=${intent?.action} (null intent = restarted by system)")
        if (intent?.action == ACTION_STOP) {
            HidHost.shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasBluetoothPermission()) {
            // Cannot run as a connected-device service without the permission.
            stopSelf()
            return START_NOT_STICKY
        }
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        // After a system restart the backend is gone; bring it back.
        startBackendIfAllowed()
        return START_STICKY
    }

    // Swiping the app away from recents must not stop the link; keep the notification and backend.
    override fun onTaskRemoved(rootIntent: Intent?) {
        HidHost.log("Service onTaskRemoved (app swiped away)")
        if (hasBluetoothPermission()) {
            refreshNotification()
            startBackendIfAllowed()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        HidHost.log("Service onDestroy")
        HidHost.notifyService = null
        try {
            unregisterReceiver(btReceiver)
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED)

    private fun startBackendIfAllowed() {
        if (HidHost.backend == null && hasBluetoothPermission()) {
            val ble = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("ble", true)
            HidHost.ensure(applicationContext, ble)
        }
    }

    private fun refreshNotification() {
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
