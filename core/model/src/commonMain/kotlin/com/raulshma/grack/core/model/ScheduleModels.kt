package com.raulshma.grack.core.model

/**
 * Phase 4 scheduling model — the single place where the `snippets.scheduleSpec` string
 * format is defined, parsed and serialized, where the consecutive-failure streak is encoded
 * into `snippets.lastRunStatus`, and where the set of WorkManager schedules that *should*
 * exist for a given snippets+servers state is derived (pure reconciliation input).
 *
 * Schedule spec format (the one documented contract):
 * - `"every:<n><unit>"` with unit `m` (minutes) or `h` (hours), e.g. `every:15m`, `every:2h`
 *   — a periodic request. Accepted range: [MIN_INTERVAL_MIN] (15, WorkManager's periodic
 *   floor) to [MAX_INTERVAL_MIN] (720 = 12 h) inclusive.
 * - `"daily"` — a one-time request re-armed after each run (so runs never overlap).
 * Anything else (null, blank, out of range, unknown syntax) parses to null = no schedule.
 */

/** Minimum accepted periodic interval in minutes; WorkManager refuses anything shorter. */
const val MIN_SCHEDULE_INTERVAL_MIN: Int = 15

/** Maximum accepted periodic interval in minutes (12 h). */
const val MAX_SCHEDULE_INTERVAL_MIN: Int = 12 * 60

/** A parsed [Snippet.scheduleSpec]. */
sealed interface ScheduleSpec {
    /** Periodic run every [minutes] minutes, [MIN_SCHEDULE_INTERVAL_MIN]..[MAX_SCHEDULE_INTERVAL_MIN]. */
    data class Interval(val minutes: Int) : ScheduleSpec

    /** One-time run re-armed after each completion. */
    data object Daily : ScheduleSpec
}

/** Parses a stored `scheduleSpec` string; null means "no (valid) schedule". */
fun parseScheduleSpec(raw: String?): ScheduleSpec? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.equals("daily", ignoreCase = true)) return ScheduleSpec.Daily
    val match = EVERY_REGEX.matchEntire(trimmed) ?: return null
    val value = match.groupValues[1].toIntOrNull() ?: return null
    if (value <= 0) return null
    val minutes = when (match.groupValues[2].lowercase()) {
        "m" -> value
        // Whole hours only: the regex matches `every:<n>h` exactly, so fractional syntax
        // like `1h30m` never reaches here.
        "h" -> value * 60
        else -> return null
    }
    if (minutes < MIN_SCHEDULE_INTERVAL_MIN || minutes > MAX_SCHEDULE_INTERVAL_MIN) return null
    return ScheduleSpec.Interval(minutes)
}

/** Serializes a spec back to its canonical string form (round-trips through [parseScheduleSpec]). */
fun serializeScheduleSpec(spec: ScheduleSpec): String = when (spec) {
    is ScheduleSpec.Interval -> "every:${spec.minutes}m"
    ScheduleSpec.Daily -> "daily"
}

private val EVERY_REGEX = Regex("every:(\\d+)([mh])")

// ---------------------------------------------------------------------------
// lastRunStatus codec: outcome label plus the consecutive-failure streak.
// ---------------------------------------------------------------------------

/** `lastRunStatus` after a fully successful scheduled run. */
const val SCHEDULE_STATUS_SUCCEEDED: String = "SUCCEEDED"

/** `lastRunStatus` when every target was skipped (e.g. all nodes offline); not a failure. */
const val SCHEDULE_STATUS_SKIPPED_OFFLINE: String = "SKIPPED_OFFLINE"

/** `lastRunStatus` when a confirmation-requiring snippet was rejected by the worker guard. */
const val SCHEDULE_STATUS_BLOCKED_CONFIRMATION: String = "BLOCKED_CONFIRMATION"

/** A schedule stops (its unique work is cancelled) after this many consecutive failures. */
const val MAX_CONSECUTIVE_SCHEDULE_FAILURES: Int = 3

/**
 * Consecutive-failure streak carried in a `lastRunStatus` label:
 * `null`/`SUCCEEDED`/anything non-failure -> 0, `FAILED` -> 1, `FAILED_2` -> 2, ...
 * Written back via [failureStatus]; kept in the same column so the streak survives
 * process death without a schema change.
 */
fun scheduleFailureStreak(lastRunStatus: String?): Int {
    val status = lastRunStatus ?: return 0
    if (status == "FAILED") return 1
    val match = FAILED_STREAK_REGEX.matchEntire(status) ?: return 0
    return match.groupValues[1].toIntOrNull()?.coerceAtLeast(2) ?: 0
}

/** The `lastRunStatus` label for the [streak]-th consecutive failure (`FAILED`, `FAILED_2`, ...). */
fun scheduleFailureStatus(streak: Int): String = if (streak <= 1) "FAILED" else "FAILED_$streak"

/** True when the snippet's failure streak hit [MAX_CONSECUTIVE_SCHEDULE_FAILURES]: stop rescheduling. */
fun scheduleIsStopped(lastRunStatus: String?): Boolean =
    scheduleFailureStreak(lastRunStatus) >= MAX_CONSECUTIVE_SCHEDULE_FAILURES

/** A human label for the runbook row (`FAILED_2` -> "FAILED (2/3)"). */
fun scheduleStatusLabel(lastRunStatus: String?): String? {
    val status = lastRunStatus ?: return null
    val streak = scheduleFailureStreak(status)
    return if (streak >= 2) "FAILED ($streak/$MAX_CONSECUTIVE_SCHEDULE_FAILURES)" else status
}

private val FAILED_STREAK_REGEX = Regex("FAILED_(\\d+)")

// ---------------------------------------------------------------------------
// Desired schedules (pure reconciliation)
// ---------------------------------------------------------------------------

/** Which WorkManager kind a schedule drives. */
enum class ScheduledWorkKind {
    /** Runs a snippet on its schedule targets; unique per snippet. */
    SNIPPET,

    /** Runs the Phase-3B scan + unattended apply for one node; unique per node. */
    AUTO_UPDATE,
}

/**
 * One WorkManager schedule that should exist. [workName] is the unique work name;
 * [spec] decides periodic vs re-armed one-time.
 */
data class DesiredSchedule(
    val workName: String,
    val kind: ScheduledWorkKind,
    val refId: String,
    val spec: ScheduleSpec,
)

/**
 * The schedules that should exist for the given data — the pure heart of the WorkScheduler
 * reconciler:
 * - a snippet contributes a schedule when it has a parsable [Snippet.scheduleSpec], does
 *   NOT require confirmation (unattended execution is incompatible with the gate) and has
 *   not been stopped by [MAX_CONSECUTIVE_SCHEDULE_FAILURES] consecutive failures;
 * - a server contributes a daily AUTO_UPDATE schedule when its [Server.updatePolicy] is
 *   AUTO (the enabled categories themselves — [Server.updateOs]/[Server.updateContainers]
 *   — are consulted at run time, not here, so toggles apply without reconciling).
 */
fun desiredSchedules(snippets: List<Snippet>, servers: List<Server>): List<DesiredSchedule> {
    val fromSnippets = snippets.mapNotNull { snippet ->
        val spec = parseScheduleSpec(snippet.scheduleSpec) ?: return@mapNotNull null
        if (snippet.requiresConfirmation) return@mapNotNull null
        if (scheduleIsStopped(snippet.lastRunStatus)) return@mapNotNull null
        DesiredSchedule(
            workName = scheduledSnippetWorkName(snippet.id),
            kind = ScheduledWorkKind.SNIPPET,
            refId = snippet.id,
            spec = spec,
        )
    }
    val fromServers = servers.filter { it.updatePolicy == UpdatePolicy.AUTO }.map { server ->
        DesiredSchedule(
            workName = scheduledAutoUpdateWorkName(server.id),
            kind = ScheduledWorkKind.AUTO_UPDATE,
            refId = server.id,
            spec = ScheduleSpec.Daily,
        )
    }
    return fromSnippets + fromServers
}

/** Unique WorkManager name for a snippet's schedule. */
fun scheduledSnippetWorkName(snippetId: String): String = "sched-snippet-$snippetId"

/** Unique WorkManager name for a node's AUTO update schedule. */
fun scheduledAutoUpdateWorkName(serverId: String): String = "sched-auto-$serverId"

// ---------------------------------------------------------------------------
// Reconciliation diff (pure)
// ---------------------------------------------------------------------------

/**
 * One change the scheduler applier must make to WorkManager. [tracked] maps unique work
 * names to the fingerprint of the spec they were last enqueued with
 * ([serializeScheduleSpec]); the diff only touches work whose spec actually changed, so
 * flow emissions that carry no schedule change never reset a periodic cycle.
 */
sealed interface ScheduleReconcile {
    /** Enqueue (or re-enqueue with [ExistingPeriodicWorkPolicy.UPDATE]/REPLACE semantics). */
    data class Upsert(val desired: DesiredSchedule) : ScheduleReconcile

    /** Cancel the unique work. */
    data class Remove(val workName: String) : ScheduleReconcile
}

/** Computes the WorkManager ops that bring `tracked` in line with [desired]. */
fun reconcileSchedules(
    tracked: Map<String, String>,
    desired: List<DesiredSchedule>,
): List<ScheduleReconcile> {
    val desiredFingerprints = desired.associate { it.workName to serializeScheduleSpec(it.spec) }
    val removals = tracked.keys
        .filterNot { it in desiredFingerprints }
        .map { ScheduleReconcile.Remove(it) }
    val upserts = desired
        .filter { tracked[it.workName] != desiredFingerprints[it.workName] }
        .map { ScheduleReconcile.Upsert(it) }
    return removals + upserts
}

// ---------------------------------------------------------------------------
// Schedule validation
// ---------------------------------------------------------------------------

/** Result of validating a schedule a user tried to attach to a snippet. */
sealed interface ScheduleValidation {
    /** The schedule may be saved. */
    data object Valid : ScheduleValidation

    /** The schedule must be rejected; [reason] is user-facing. */
    data class Invalid(val reason: String) : ScheduleValidation
}

/**
 * UI-level validation for saving a snippet schedule (the worker enforces the same rule
 * as a guard): a snippet with `requiresConfirmation = true` cannot run unattended —
 * its whole point is a human pressing the gate — so it must never be schedulable.
 */
fun validateSnippetSchedule(snippet: Snippet, spec: ScheduleSpec?): ScheduleValidation {
    if (spec == null) return ScheduleValidation.Valid
    if (snippet.requiresConfirmation) {
        return ScheduleValidation.Invalid(
            "'${snippet.name}' requires confirmation before running, so it cannot be " +
                "scheduled — scheduled runs execute unattended with no gate to press.",
        )
    }
    return ScheduleValidation.Valid
}
