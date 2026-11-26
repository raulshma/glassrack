package com.raulshma.grack.core.ssh

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The poll-pause state machine: reasons accumulate, polling opens only when all clear. */
class PollingGateTest {

    @Test
    fun reasonsAccumulateAndClearIndependently() {
        val gate = PollingGate()
        assertFalse(gate.isPaused)

        gate.pause("background")
        assertTrue(gate.isPaused)
        assertEquals(setOf("background"), gate.pausedReasons.value)

        gate.pause("metered")
        assertTrue(gate.isPaused)
        assertEquals(setOf("background", "metered"), gate.pausedReasons.value)

        // Clearing one reason must not resume while another owner is still pausing.
        gate.resume("background")
        assertTrue(gate.isPaused)

        gate.resume("metered")
        assertFalse(gate.isPaused)
        assertEquals(emptySet(), gate.pausedReasons.value)
    }

    @Test
    fun resumeOfUnknownReasonDoesNotOpenTheGate() {
        val gate = PollingGate()
        gate.pause("background")
        gate.resume("metered")
        assertTrue(gate.isPaused)
    }

    @Test
    fun awaitOpenSuspendsUntilTheLastReasonIsCleared() = runTest {
        val gate = PollingGate()
        gate.pause("background")
        gate.pause("metered")
        var opened = false
        launch { gate.awaitOpen(); opened = true }
        runCurrent()
        assertFalse(opened)

        gate.resume("background")
        runCurrent()
        // Still one reason left: the waiter must remain suspended.
        assertFalse(opened)

        gate.resume("metered")
        runCurrent()
        assertTrue(opened)
    }
}
