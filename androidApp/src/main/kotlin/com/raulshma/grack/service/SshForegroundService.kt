package com.raulshma.grack.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.raulshma.grack.GlassrackApplication
import com.raulshma.grack.MainActivity

class SshForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val sessionCount = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_DEST, "terminal")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SshForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, GlassrackApplication.CHANNEL_SSH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("GRack SSH Active")
            .setContentText("$sessionCount active SSH session(s) — Tap to resume")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Disconnect", stopIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    companion object {
        const val ACTION_STOP = "com.raulshma.grack.STOP_SSH_FG"
        const val EXTRA_COUNT = "session_count"
        const val NOTIFICATION_ID = 4401
    }
}
