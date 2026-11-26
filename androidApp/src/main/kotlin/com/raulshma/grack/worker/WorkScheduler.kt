package com.raulshma.grack.worker

import android.content.Context
import androidx.work.WorkManager
import co.touchlab.kermit.Logger
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.DesiredSchedule
import com.raulshma.grack.core.model.ScheduleReconcile
import com.raulshma.grack.core.model.desiredSchedules
import com.raulshma.grack.core.model.reconcileSchedules
import com.raulshma.grack.core.model.serializeScheduleSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps WorkManager in line with the database (Phase 4): whenever the snippets or servers
 * flows change, computes the schedules that should exist ([desiredSchedules]) and applies
 * the diff ([reconcileSchedules]) — enqueues new/changed schedules, cancels removed ones.
 * Started once from [com.raulshma.grack.GlassrackApplication].
 *
 * Change detection is fingerprint-based (unique work name -> serialized spec), so flow
 * emissions that carry no schedule change (a telemetry write, a last-run status update)
 * never re-enqueue anything and periodic cycles are not reset.
 */
class WorkScheduler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: GlassrackRepository,
) {

    /** workName -> fingerprint of the spec currently enqueued; guarded by the reconcile loop. */
    private val tracked = mutableMapOf<String, String>()

    fun start() {
        scope.launch {
            combine(repository.snippets, repository.servers) { snippets, servers ->
                desiredSchedules(snippets, servers)
            }.collect { desired ->
                runCatching { reconcile(desired) }
                    .onFailure { Logger.w(it) { "Schedule reconcile failed" } }
            }
        }
    }

    private suspend fun reconcile(desired: List<DesiredSchedule>) {
        val actions = reconcileSchedules(tracked.toMap(), desired)
        if (actions.isEmpty()) return
        val workManager = WorkManager.getInstance(context)
        actions.forEach { action ->
            when (action) {
                is ScheduleReconcile.Upsert -> {
                    ScheduledWorks.enqueue(workManager, action.desired)
                    tracked[action.desired.workName] = serializeScheduleSpec(action.desired.spec)
                }
                is ScheduleReconcile.Remove -> {
                    ScheduledWorks.cancel(workManager, action.workName)
                    tracked.remove(action.workName)
                }
            }
        }
        Logger.i { "Schedules reconciled: ${actions.size} change(s)" }
    }
}
