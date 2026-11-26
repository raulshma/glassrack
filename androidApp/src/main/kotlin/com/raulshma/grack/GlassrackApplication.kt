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
import com.raulshma.grack.core.database.AuditRetentionCoordinator
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.ssh.HomelabController
import com.raulshma.grack.notify.AlertNotificationCoordinator
import com.raulshma.grack.notify.AndroidAlertNotifier
import com.raulshma.grack.polling.PollingPauseCoordinator
import com.raulshma.grack.widget.WidgetContainerCache
import com.raulshma.grack.widget.grackAndroidModule
import com.raulshma.grack.worker.TelemetryRefreshWorker
import com.raulshma.grack.worker.WorkScheduler
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
    private val repository: GlassrackRepository by inject()

    override fun onCreate() {
        super.onCreate()
        initGlassrackKoin {
            androidContext(this@GlassrackApplication)
            modules(grackAndroidModule)
        }
        createNotificationChannels()
        // Placeholder until the preferences flow re-enqueues with the right constraint.
        scheduleBackgroundPolling(pauseOnMetered = false)
        startAlertNotifications()
        startPollingHygiene()
        startAuditRetentionPruning()
        startScheduledRuns()
    }

    private fun startAlertNotifications() {
        AlertNotificationCoordinator(
            scope = coordinatorScope,
            controller = controller,
            notifier = AndroidAlertNotifier(this),
        ).start()
    }

    /** Pauses/resumes the controller poll loops and keeps the worker constraint in sync. */
    private fun startPollingHygiene() {
        PollingPauseCoordinator(
            context = this,
            controller = controller,
            preferences = repository.preferences,
            scope = coordinatorScope,
            onMeteredPrefChanged = ::scheduleBackgroundPolling,
        ).start()
    }

    /** Nightly (and at startup) prune of audit entries past the retention window. */
    private fun startAuditRetentionPruning() {
        AuditRetentionCoordinator(
            scope = coordinatorScope,
            preferences = repository.preferences,
            prune = repository::pruneAuditLogs,
        ).start()
    }

    /** Phase 4: reconcile WorkManager schedules from the snippets and servers flows. */
    private fun startScheduledRuns() {
        WorkScheduler(
            context = this,
            scope = coordinatorScope,
            repository = repository,
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
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCHEDULED,
                "Scheduled Runs",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    /**
     * The periodic worker owns background telemetry. When `pausePollingOnMetered` is set it
     * must not run on metered networks, so the constraint tightens to UNMETERED.
     * UPDATE swaps the specification without cancelling the current cycle.
     */
    private fun scheduleBackgroundPolling(pauseOnMetered: Boolean) {
        val request = PeriodicWorkRequestBuilder<TelemetryRefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (pauseOnMetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_TELEMETRY_POLL,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val CHANNEL_ALERTS = "glassrack_alerts"
        const val CHANNEL_SSH_SERVICE = "glassrack_ssh_fg"
        const val CHANNEL_SCHEDULED = "glassrack_scheduled"
        const val WORK_TELEMETRY_POLL = "glassrack_telemetry_poll"
    }
}
