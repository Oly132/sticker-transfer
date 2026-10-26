package com.example.stickerpackbuilder.sync

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.content.pm.ServiceInfo

class PhoneConnectorService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("transfer", "Sticker transfers", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 0, Intent(this, TransferActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "transfer").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sticker Sync is active").setContentText("Keeping WhatsApp linking and transfers connected.")
            .setContentIntent(pending).setOngoing(true).build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(30, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(30, notification)
        Thread({ runCatching { PhoneRuntime.start(this) { android.os.Handler(mainLooper).post { stopSelf() } } }.onFailure {
            android.os.Handler(mainLooper).post { stopSelf() }
        } }, "connector-start").start()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onDestroy() {
        super.onDestroy()
        // Node can be initialized once per process. A new isolated process reuses saved auth.
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
