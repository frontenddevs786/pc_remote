package com.example.pcremote

import android.Manifest
import android.app.AlarmManager
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
import android.os.IBinder
import android.os.SystemClock

/**
 * Foreground service that keeps the Bluetooth connection alive while the app is minimized or
 * swiped out of the recent-apps list. The actual Bluetooth work lives in [HidHost]; this just
 * stops Android from killing it, and brings it back if the system or the phone vendor does.
 */
class HidService : Service() {

    companion object {
        private const val CHANNEL = "pcremote_link"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.example.pcremote.STOP"
        private const val PREFS = "cfg"
        private const val KEY_WANTED = "svc_wanted"

        /** True once the app has been opened, until the user presses Stop. Decides if we restart after a kill or reboot. */
        fun isWanted(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false)

        private fun setWanted(ctx: Context, wanted: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANTED, wanted).apply()
        }

        /** Starts the service on behalf of the user (opening the app counts as asking for it). */
        fun start(ctx: Context) {
            setWanted(ctx, true)
            startInternal(ctx)
        }

        /** Used by boot / restart paths; does nothing if the user pressed Stop earlier. */
        fun startIfWanted(ctx: Context) {
            if (isWanted(ctx)) startInternal(ctx)
        }

        private fun startInternal(ctx: Context) {
            try {
                val i = Intent(ctx, HidService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                // Not allowed to start right now; the Activity will retry next time it is shown.
                HidHost.log("Service start refused: $e")
            }
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_TURNING_OFF -> HidHost.shutdown()
                        BluetoothAdapter.STATE_ON -> startBackendIfAllowed()
                    }
                // The PC just came into range / woke up: let the backend reconnect to it right away.
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (!HidHost.connected) HidHost.backend?.reconnect()
                }
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
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        registerReceiver(btReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        HidHost.log("Service onStartCommand (${if (intent == null) "restarted by the system" else "started by the app"})")
        if (intent?.action == ACTION_STOP) {
            setWanted(this, false)
            cancelRestart()
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
            HidHost.log("startForeground failed: $e")
            stopSelf()
            return START_NOT_STICKY
        }
        // After a system restart the backend is gone; bring it back.
        startBackendIfAllowed()
        return START_STICKY
    }

    /**
     * Swiping the app out of the recent-apps list must not stop the link. A normal Android keeps this
     * foreground service running. Phones that kill the whole process anyway (vivo, Xiaomi, Oppo, ...)
     * are covered by START_STICKY plus this backup alarm that starts the service again.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        HidHost.log("Service onTaskRemoved (app removed from recents)")
        if (hasBluetoothPermission() && isWanted(this)) {
            refreshNotification()
            startBackendIfAllowed()
            scheduleRestart()
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
        // Destroyed without the user asking for it (e.g. killed by the phone): ask to be started again.
        if (isWanted(this) && hasBluetoothPermission()) scheduleRestart()
        super.onDestroy()
    }

    // ---------- backup restart ----------

    private fun restartIntent(): PendingIntent {
        val i = Intent(applicationContext, HidService::class.java)
        return if (Build.VERSION.SDK_INT >= 26) {
            PendingIntent.getForegroundService(
                applicationContext, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getService(
                applicationContext, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }

    private fun scheduleRestart() {
        try {
            val am = getSystemService(AlarmManager::class.java)
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 3_000, restartIntent())
        } catch (e: Exception) {
            HidHost.log("scheduleRestart failed: $e")
        }
    }

    private fun cancelRestart() {
        try {
            getSystemService(AlarmManager::class.java).cancel(restartIntent())
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------- helpers ----------

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

/** Starts the connection service again after the phone reboots, so the PC can reconnect without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            HidService.startIfWanted(context)
        }
    }
}
