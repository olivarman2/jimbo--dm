package com.jimbo.jdm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Keeps the process alive so downloads continue when the app is closed. */
class DlService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jdm", "Downloads", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "jdm")
            .setContentTitle("Jimbo DM")
            .setContentText("در حال دانلود…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }
}
