package com.raulshma.grack.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.raulshma.grack.core.model.DesiredSchedule
import com.raulshma.grack.core.model.ScheduledWorkKind
import com.raulshma.grack.core.model.ScheduleSpec
import com.raulshma.grack.core.model.scheduledAutoUpdateWorkName
import com.raulshma.grack.core.model.scheduledSnippetWorkName
import java.util.concurrent.TimeUnit

/**
 * WorkManager specifics of the Phase 4 scheduler: input keys, enqueue/cancel primitives
 * shared by the [WorkScheduler] reconciler and the daily re-arm in [ScheduledRunWorker].
 * The *decision* logic (which schedules should exist, which ops a reconcile needs) lives
 * in core/model and is unit-tested there.
 */
object ScheduledWorks {

    const val KEY_KIND = "sched_kind"
    const val KEY_REF_ID = "sched_ref_id"
    const val KIND_SNIPPET = "SNIPPET"
    const val KIND_AUTO_UPDATE = "AUTO_UPDATE"

    /** A "daily" schedule is a one-time request re-armed this long after each run. */
    const val DAILY_REARM_DELAY_MS: Long = 24L * 60L * 60L * 1000L

    /** Network connected + battery not low, matching the telemetry worker's constraints. */
    fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun inputData(kind: ScheduledWorkKind, refId: String) = workDataOf(
        KEY_KIND to kind.name,
        KEY_REF_ID to refId,
    )

    /**
     * Enqueues [desired] under its unique name. Interval specs become periodic requests
     * with UPDATE (reconciles swap the specification without resetting the original
     * cycle); "daily" becomes a one-time request with [initialDelayMs] (0 for the first
     * arm, [DAILY_REARM_DELAY_MS] for each re-arm) under KEEP — an already pending or
     * running daily chain is never reset by a reconcile, so a process restart or flow
     * churn cannot make a daily schedule run more often than once a day.
     */
    fun enqueue(workManager: WorkManager, desired: DesiredSchedule, initialDelayMs: Long = 0L) {
        val data = inputData(desired.kind, desired.refId)
        when (val spec = desired.spec) {
            is ScheduleSpec.Interval -> workManager.enqueueUniquePeriodicWork(
                desired.workName,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ScheduledRunWorker>(spec.minutes.toLong(), TimeUnit.MINUTES)
                    .setConstraints(constraints())
                    .setInputData(data)
                    .build(),
            )
            ScheduleSpec.Daily -> workManager.enqueueUniqueWork(
                desired.workName,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ScheduledRunWorker>()
                    .setConstraints(constraints())
                    .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                    .setInputData(data)
                    .build(),
            )
        }
    }

    fun cancel(workManager: WorkManager, workName: String) {
        workManager.cancelUniqueWork(workName)
    }

    /** Cancels from the worker itself (failure streak maxed out, guard tripped, policy left AUTO). */
    fun cancelSelf(context: Context, workName: String) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(workName) }
    }

    fun workNameFor(kind: ScheduledWorkKind, refId: String): String = when (kind) {
        ScheduledWorkKind.SNIPPET -> scheduledSnippetWorkName(refId)
        ScheduledWorkKind.AUTO_UPDATE -> scheduledAutoUpdateWorkName(refId)
    }
}
