package com.raulshma.grack

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.raulshma.grack.di.initGlassrackKoin
import com.raulshma.grack.worker.TelemetryRefreshWorker
import org.koin.android.ext.koin.androidContext
import java.util.concurrent.TimeUnit

class GlassrackApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initGlassrackKoin {
            androidContext(this@GlassrackApplication)
        }
        createNotificationChannels()
        scheduleBackgroundPolling()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Homelab Threshold Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SSH_SERVICE,
                "Active SSH Terminal Sessions",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun scheduleBackgroundPolling() {
        val request = PeriodicWorkRequestBuilder<TelemetryRefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "glassrack_telemetry_poll",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val CHANNEL_ALERTS = "glassrack_alerts"
        const val CHANNEL_SSH_SERVICE = "glassrack_ssh_fg"
    }
}
