package com.raulshma.grack.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.DesiredSchedule
import com.raulshma.grack.core.model.ScheduledWorkKind
import com.raulshma.grack.core.model.ScheduleSpec
import com.raulshma.grack.core.model.parseScheduleSpec
import com.raulshma.grack.core.model.scheduledAutoUpdateWorkName
import com.raulshma.grack.core.model.scheduledSnippetWorkName
import com.raulshma.grack.core.ssh.AutoUpdateOutcome
import com.raulshma.grack.core.ssh.ScheduledRunCoordinator
import com.raulshma.grack.notify.AutoUpdateNotifier
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The Phase 4 scheduled-run worker (modelled on [TelemetryRefreshWorker]). Input data
 * carries `kind` (SNIPPET or AUTO_UPDATE) + `refId`; unique work exists per snippet or
 * per node (see [ScheduledWorks]). All logic lives in the KMP-testable
 * [ScheduledRunCoordinator]; this class maps its outcomes to WorkManager results, cancels
 * the unique work when a schedule must stop, re-arms "daily" one-time requests after each
 * run, and posts the AUTO update summary notification.
 */
class ScheduledRunWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val coordinator: ScheduledRunCoordinator by inject()
    private val repository: GlassrackRepository by inject()

    override suspend fun doWork(): Result {
        val kind = inputData.getString(ScheduledWorks.KEY_KIND)
        val refId = inputData.getString(ScheduledWorks.KEY_REF_ID) ?: return Result.failure()
        return when (kind) {
            ScheduledWorks.KIND_SNIPPET -> runSnippet(refId)
            ScheduledWorks.KIND_AUTO_UPDATE -> runAutoUpdate(refId)
            else -> Result.failure()
        }
    }

    private suspend fun runSnippet(snippetId: String): Result {
        val outcome = runCatching { coordinator.runScheduledSnippet(snippetId) }
            .onFailure { Logger.w(it) { "Scheduled snippet run crashed" } }
            .getOrNull()
            ?: return retryOrFail()

        if (outcome.stopSchedule) {
            ScheduledWorks.cancelSelf(applicationContext, scheduledSnippetWorkName(snippetId))
            return Result.success()
        }
        reArmDaily(snippetId, ScheduledWorkKind.SNIPPET)
        return Result.success()
    }

    private suspend fun runAutoUpdate(serverId: String): Result {
        val outcome = runCatching { coordinator.runScheduledAutoUpdate(serverId) }
            .onFailure { Logger.w(it) { "Scheduled auto-update run crashed" } }
            .getOrNull()
            ?: return retryOrFail()

        when (outcome) {
            AutoUpdateOutcome.NotApplicable -> {
                ScheduledWorks.cancelSelf(applicationContext, scheduledAutoUpdateWorkName(serverId))
                return Result.success()
            }
            is AutoUpdateOutcome.Ran ->
                AutoUpdateNotifier.postIfNeeded(applicationContext, outcome.summary)
            AutoUpdateOutcome.SkippedOffline,
            AutoUpdateOutcome.BlockedSafeReadOnly,
            -> Unit
        }
        reArmDaily(serverId, ScheduledWorkKind.AUTO_UPDATE)
        return Result.success()
    }

    /**
     * "Daily" schedules are one-time requests re-armed after each run, 24 h later. The
     * snippet row is re-read so a schedule cleared mid-run is not resurrected. Periodic
     * schedules re-arm themselves.
     */
    private suspend fun reArmDaily(refId: String, kind: ScheduledWorkKind) {
        runCatching {
            val spec = when (kind) {
                ScheduledWorkKind.SNIPPET -> parseScheduleSpec(repository.getSnippetById(refId)?.scheduleSpec)
                ScheduledWorkKind.AUTO_UPDATE -> ScheduleSpec.Daily
            }
            if (spec == ScheduleSpec.Daily) {
                ScheduledWorks.enqueue(
                    WorkManager.getInstance(applicationContext),
                    DesiredSchedule(
                        workName = ScheduledWorks.workNameFor(kind, refId),
                        kind = kind,
                        refId = refId,
                        spec = ScheduleSpec.Daily,
                    ),
                    initialDelayMs = ScheduledWorks.DAILY_REARM_DELAY_MS,
                )
            }
        }.onFailure { Logger.w(it) { "Daily schedule re-arm failed" } }
    }

    /** Three worker-level crashes in a row stop the retry loop; the reconciler re-arms on next app start. */
    private fun retryOrFail(): Result = if (runAttemptCount < MAX_WORKER_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        const val MAX_WORKER_ATTEMPTS: Int = 3
    }
}
