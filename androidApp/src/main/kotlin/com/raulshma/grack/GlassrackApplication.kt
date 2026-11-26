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
import com.raulshma.grack.core.ssh.HomelabController
import com.raulshma.grack.notify.AlertNotificationCoordinator
import com.raulshma.grack.notify.AndroidAlertNotifier
import com.raulshma.grack.worker.TelemetryRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

class GlassrackApplication : Application(), KoinComponent {
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val controller: HomelabController by inject()

    override fun onCreate() {
        super.onCreate()
        initGlassrackKoin {
            androidContext(this@GlassrackApplication)
        }
        createNotificationChannels()
        scheduleBackgroundPolling()
        startAlertNotifications()
    }

    private fun startAlertNotifications() {
        AlertNotificationCoordinator(
            scope = coordinatorScope,
            controller = controller,
            notifier = AndroidAlertNotifier(this),
        ).start()
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
