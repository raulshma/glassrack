package com.raulshma.grack.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4 schedule model: spec format parsing/serialization, the consecutive-failure
 * streak codec in `lastRunStatus`, the desired-schedule reconciliation input, and the
 * confirmation-gate validator for schedulable snippets.
 */
class ScheduleModelsTest {

    // ------------------------------------------------------------------
    // parseScheduleSpec / serializeScheduleSpec
    // ------------------------------------------------------------------

    @Test
    fun minuteSpecsParse() {
        assertEquals(ScheduleSpec.Interval(15), parseScheduleSpec("every:15m"))
        assertEquals(ScheduleSpec.Interval(45), parseScheduleSpec("every:45m"))
    }

    @Test
    fun hourSpecsParseToMinutes() {
        assertEquals(ScheduleSpec.Interval(120), parseScheduleSpec("every:2h"))
        assertEquals(ScheduleSpec.Interval(720), parseScheduleSpec("every:12h"))
    }

    @Test
    fun dailyParsesCaseInsensitively() {
        assertEquals(ScheduleSpec.Daily, parseScheduleSpec("daily"))
        assertEquals(ScheduleSpec.Daily, parseScheduleSpec("DAILY"))
    }

    @Test
    fun nullBlankAndUnknownSpecsParseToNull() {
        assertNull(parseScheduleSpec(null))
        assertNull(parseScheduleSpec(""))
        assertNull(parseScheduleSpec("   "))
        assertNull(parseScheduleSpec("weekly"))
        assertNull(parseScheduleSpec("every:"))
        assertNull(parseScheduleSpec("every:abc"))
        assertNull(parseScheduleSpec("every:15x"))
        assertNull(parseScheduleSpec("every:-5m"))
        assertNull(parseScheduleSpec("every:0m"))
    }

    @Test
    fun belowFloorAndAboveCeilingIntervalsAreRejected() {
        assertNull(parseScheduleSpec("every:14m"))
        assertNull(parseScheduleSpec("every:13h"))
        // Boundaries themselves are accepted.
        assertEquals(ScheduleSpec.Interval(15), parseScheduleSpec("every:15m"))
        assertEquals(ScheduleSpec.Interval(720), parseScheduleSpec("every:720m"))
    }

    @Test
    fun fractionalHoursAreRejected() {
        // 1h30m is not the documented syntax at all; whole hours only.
        assertNull(parseScheduleSpec("every:1h30m"))
    }

    @Test
    fun serializationRoundTripsThroughParsing() {
        listOf(
            ScheduleSpec.Interval(15),
            ScheduleSpec.Interval(45),
            ScheduleSpec.Interval(720),
            ScheduleSpec.Daily,
        ).forEach { spec ->
            assertEquals(spec, parseScheduleSpec(serializeScheduleSpec(spec)))
        }
    }

    // ------------------------------------------------------------------
    // Failure streak codec
    // ------------------------------------------------------------------

    @Test
    fun streakIsZeroForNonFailureStatuses() {
        assertEquals(0, scheduleFailureStreak(null))
        assertEquals(0, scheduleFailureStreak(SCHEDULE_STATUS_SUCCEEDED))
        assertEquals(0, scheduleFailureStreak(SCHEDULE_STATUS_SKIPPED_OFFLINE))
        assertEquals(0, scheduleFailureStreak(SCHEDULE_STATUS_BLOCKED_CONFIRMATION))
        assertEquals(0, scheduleFailureStreak("whatever"))
    }

    @Test
    fun streakCountsConsecutiveFailures() {
        assertEquals(1, scheduleFailureStreak("FAILED"))
        assertEquals(2, scheduleFailureStreak("FAILED_2"))
        assertEquals(3, scheduleFailureStreak("FAILED_3"))
    }

    @Test
    fun failureStatusLabelsAreCanonical() {
        assertEquals("FAILED", scheduleFailureStatus(1))
        assertEquals("FAILED_2", scheduleFailureStatus(2))
    }

    @Test
    fun scheduleStopsAtThirdConsecutiveFailure() {
        assertFalse(scheduleIsStopped(null))
        assertFalse(scheduleIsStopped("FAILED"))
        assertFalse(scheduleIsStopped("FAILED_2"))
        assertTrue(scheduleIsStopped("FAILED_3"))
        assertTrue(scheduleIsStopped("FAILED_4"))
    }

    @Test
    fun statusLabelAddsStreakContextFromSecondFailureOn() {
        assertEquals("FAILED", scheduleStatusLabel("FAILED"))
        assertEquals("FAILED (2/3)", scheduleStatusLabel("FAILED_2"))
        assertEquals("SUCCEEDED", scheduleStatusLabel("SUCCEEDED"))
        assertNull(scheduleStatusLabel(null))
    }

    // ------------------------------------------------------------------
    // desiredSchedules
    // ------------------------------------------------------------------

    private fun snippet(
        id: String,
        spec: String? = null,
        requiresConfirmation: Boolean = false,
        lastRunStatus: String? = null,
    ) = Snippet(
        id = id,
        name = "runbook-$id",
        commandTemplate = "uptime",
        requiresConfirmation = requiresConfirmation,
        scheduleSpec = spec,
        lastRunStatus = lastRunStatus,
    )

    @Test
    fun snippetsWithValidSchedulesBecomeUniqueWorks() {
        val snippets = listOf(
            snippet("a", spec = "every:30m"),
            snippet("b", spec = "daily"),
        )
        val desired = desiredSchedules(snippets, emptyList())
        assertEquals(2, desired.size)
        assertEquals("sched-snippet-a", desired[0].workName)
        assertEquals(ScheduledWorkKind.SNIPPET, desired[0].kind)
        assertEquals("a", desired[0].refId)
        assertEquals(ScheduleSpec.Interval(30), desired[0].spec)
        assertEquals(ScheduleSpec.Daily, desired[1].spec)
    }

    @Test
    fun invalidClearedAndConfirmationSnippetsHaveNoSchedule() {
        val snippets = listOf(
            snippet("cleared", spec = null),
            snippet("unparseable", spec = "weekly"),
            snippet("gated", spec = "every:1h", requiresConfirmation = true),
        )
        assertTrue(desiredSchedules(snippets, emptyList()).isEmpty())
    }

    @Test
    fun stoppedSnippetsThreeFailuresInARowAreNotRescheduled() {
        val stopped = snippet("stopped", spec = "every:15m", lastRunStatus = "FAILED_3")
        val recovering = snippet("recovering", spec = "every:15m", lastRunStatus = "FAILED_2")
        val desired = desiredSchedules(listOf(stopped, recovering), emptyList())
        assertEquals(listOf("sched-snippet-recovering"), desired.map { it.workName })
    }

    @Test
    fun autoServersGetDailySchedulesManualOnesDoNot() {
        val auto = Server(
            id = "srv-auto",
            name = "auto-node",
            primaryHost = "10.0.0.9",
            updatePolicy = UpdatePolicy.AUTO,
            updateOs = true,
            updateContainers = false,
        )
        val manual = Server(id = "srv-manual", name = "manual-node", primaryHost = "10.0.0.10")
        val desired = desiredSchedules(emptyList(), listOf(auto, manual))
        assertEquals(1, desired.size)
        assertEquals(
            DesiredSchedule(
                workName = "sched-auto-srv-auto",
                kind = ScheduledWorkKind.AUTO_UPDATE,
                refId = "srv-auto",
                spec = ScheduleSpec.Daily,
            ),
            desired.single(),
        )
    }

    // ------------------------------------------------------------------
    // reconcileSchedules (pure diff)
    // ------------------------------------------------------------------

    @Test
    fun reconcileEnqueuesNewAndChangedAndCancelsRemoved() {
        val every15 = desiredSchedules(listOf(snippet("a", spec = "every:15m")), emptyList()).single()
        val every30 = desiredSchedules(listOf(snippet("a", spec = "every:30m")), emptyList()).single()

        // First reconcile: everything is new.
        val initial = reconcileSchedules(tracked = emptyMap(), desired = listOf(every15))
        assertEquals(listOf<ScheduleReconcile>(ScheduleReconcile.Upsert(every15)), initial)

        // No change: no ops (flow emissions without schedule changes must not re-enqueue).
        assertTrue(reconcileSchedules(tracked = mapOf("sched-snippet-a" to "every:15m"), desired = listOf(every15)).isEmpty())

        // Spec changed: one upsert.
        val changed = reconcileSchedules(tracked = mapOf("sched-snippet-a" to "every:15m"), desired = listOf(every30))
        assertEquals(listOf<ScheduleReconcile>(ScheduleReconcile.Upsert(every30)), changed)

        // Removed: one cancel.
        val removed = reconcileSchedules(tracked = mapOf("sched-snippet-a" to "every:30m"), desired = emptyList())
        assertEquals(listOf<ScheduleReconcile>(ScheduleReconcile.Remove("sched-snippet-a")), removed)
    }

    // ------------------------------------------------------------------
    // validateSnippetSchedule
    // ------------------------------------------------------------------

    @Test
    fun plainSnippetsMayBeScheduledAndCleared() {
        val plain = snippet("plain")
        assertEquals(ScheduleValidation.Valid, validateSnippetSchedule(plain, ScheduleSpec.Interval(15)))
        assertEquals(ScheduleValidation.Valid, validateSnippetSchedule(plain, null))
    }

    @Test
    fun confirmationSnippetsAreRejectedWithAnExplanation() {
        val gated = snippet("gated", requiresConfirmation = true)
        val validation = validateSnippetSchedule(gated, ScheduleSpec.Interval(15))
        assertTrue(validation is ScheduleValidation.Invalid)
        assertTrue(validation.reason.contains("requires confirmation", ignoreCase = true))
        // Clearing a schedule stays allowed even for gated snippets.
        assertEquals(ScheduleValidation.Valid, validateSnippetSchedule(gated, null))
    }
}
