package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class HomelabControllerIdlePruneTest {

    @Test
    fun disconnectIdleIsCalledEveryFifteenMinutesFromInit() = runTest {
        val h = ControllerHarness(
            context = ApplicationProvider.getApplicationContext(),
            testScope = this,
        )
        assertEquals(0, h.fake.disconnectIdleCalls.size)

        advanceTimeBy(HomelabController.IDLE_PRUNE_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf(HomelabController.IDLE_PRUNE_MAX_IDLE_MS), h.fake.disconnectIdleCalls)

        advanceTimeBy(HomelabController.IDLE_PRUNE_INTERVAL_MS)
        runCurrent()
        assertEquals(2, h.fake.disconnectIdleCalls.size)
        assertEquals(HomelabController.IDLE_PRUNE_MAX_IDLE_MS, h.fake.disconnectIdleCalls.last())
        h.controller.stop()
    }
}
