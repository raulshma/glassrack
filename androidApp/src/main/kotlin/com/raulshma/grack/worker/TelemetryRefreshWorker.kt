package com.raulshma.grack.worker

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.ssh.HomelabController
import com.raulshma.grack.widget.ActionStripWidget
import com.raulshma.grack.widget.ClusterPulseWidget
import com.raulshma.grack.widget.ContainerControlWidget
import com.raulshma.grack.widget.ServerTelemetryWidget
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
        // Alert notifications are posted by AlertNotificationCoordinator (see GlassrackApplication);
        // the worker only refreshes the widgets here.
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
