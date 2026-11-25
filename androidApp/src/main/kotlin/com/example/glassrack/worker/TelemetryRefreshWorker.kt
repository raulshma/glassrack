package com.example.glassrack.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.glassrack.GlassrackApplication
import com.example.glassrack.core.database.GlassrackRepository
import com.example.glassrack.core.ssh.HomelabController
import com.example.glassrack.widget.ActionStripWidget
import com.example.glassrack.widget.ClusterPulseWidget
import com.example.glassrack.widget.ContainerControlWidget
import com.example.glassrack.widget.ServerTelemetryWidget
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class TelemetryRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val repository: GlassrackRepository by inject()
    private val controller: HomelabController by inject()

    override suspend fun doWork(): Result {
        val servers = repository.getServersOnce()
        servers.forEach { srv ->
            runCatching { controller.pollServerOnce(srv) }
        }
        val alerts = controller.activeAlerts.value
        if (alerts.isNotEmpty() && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val latest = alerts.last()
            val notif = NotificationCompat.Builder(applicationContext, GlassrackApplication.CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Glassrack Alert · ${latest.serverName}")
                .setContentText(latest.message)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(applicationContext).notify(latest.id.hashCode(), notif)
        }
        refreshAllWidgets(applicationContext)
        return Result.success()
    }

    companion object {
        suspend fun refreshAllWidgets(context: Context) {
            runCatching { ClusterPulseWidget().updateAll(context) }
            runCatching { ServerTelemetryWidget().updateAll(context) }
            runCatching { ContainerControlWidget().updateAll(context) }
            runCatching { ActionStripWidget().updateAll(context) }
        }
    }
}
