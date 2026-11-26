package com.raulshma.grack.core.model

import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3B gating and merge helpers: update applies (OS upgrade, stack update, image pull)
 * always count as destructive for the confirmation gate, and the persisted container
 * verdicts merge into freshly fetched containers as `updateAvailable` flags.
 */
class UpdateGateTest {

    private fun prefs(level: ConfirmationLevel, safeMode: Boolean = false) = UserPreferences(
        destructiveConfirmation = level,
        safeReadOnlyMode = safeMode,
    )

    @Test
    fun updateAppliesAreGatedAtEveryLevelExceptNone() {
        assertTrue(updateApplyRequiresConfirmation(prefs(ConfirmationLevel.DIALOG)))
        assertTrue(updateApplyRequiresConfirmation(prefs(ConfirmationLevel.HOLD_TO_CONFIRM)))
        assertTrue(updateApplyRequiresConfirmation(prefs(ConfirmationLevel.BIOMETRIC)))
        assertFalse(updateApplyRequiresConfirmation(prefs(ConfirmationLevel.NONE)))
    }

    @Test
    fun updateAppliesStayGatedWhenSafeModeIsOff() {
        // Safe mode is a separate hard block in the UI; the gate must not depend on it.
        assertTrue(updateApplyRequiresConfirmation(prefs(ConfirmationLevel.HOLD_TO_CONFIRM, safeMode = false)))
    }

    private fun report(vararg updateNames: String) = NodeUpdateReport(
        scannedAtEpochMs = 1L,
        containers = updateNames.map { name ->
            ContainerUpdateEntry(
                containerName = name,
                image = "$name:latest",
                status = ContainerUpdateStatus.UPDATE_AVAILABLE,
            )
        } + ContainerUpdateEntry(
            containerName = "pinned",
            image = "app@sha256:abc",
            status = ContainerUpdateStatus.PINNED,
        ),
    )

    private fun containers(vararg names: String) = names.map { name ->
        ContainerInfo(id = name, shortId = name, name = name, image = "$name:latest")
    }

    @Test
    fun withUpdateFlagsMarksOnlyReportedUpdateContainers() {
        val flagged = containers("web", "cache", "pinned").withUpdateFlags(report("web"))

        assertTrue(flagged.single { it.name == "web" }.updateAvailable)
        assertFalse(flagged.single { it.name == "cache" }.updateAvailable)
        // A PINNED entry is not an update.
        assertFalse(flagged.single { it.name == "pinned" }.updateAvailable)
    }

    @Test
    fun withUpdateFlagsHandlesNullAndEmptyReports() {
        val fresh = containers("web")
        assertEquals(fresh, fresh.withUpdateFlags(null))
        assertEquals(fresh, fresh.withUpdateFlags(NodeUpdateReport(scannedAtEpochMs = 1L)))
    }

    @Test
    fun reportCountsAndJsonRoundTrip() {
        val report = report("web", "cache")
        assertEquals(0, report.osUpdateCount)
        assertEquals(2, report.containerUpdateCount)
        assertTrue(report.hasAnyUpdates)

        val parsed = NodeUpdateReport.fromJson(NodeUpdateReportJson.encode(report))
        assertEquals(report, parsed)
        assertNull(NodeUpdateReport.fromJson(null))
        assertNull(NodeUpdateReport.fromJson("garbage"))
    }

    /** Tiny bridge: the model's own Json config is private; re-encode with the same semantics. */
    private object NodeUpdateReportJson {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun encode(report: NodeUpdateReport): String = json.encodeToString(report)
    }

    @Test
    fun composeStackCountsStillWorkWithUpdateFlags() {
        val flagged = containers("web", "cache").withUpdateFlags(report("web"))
        val stack = ComposeStack(projectName = "webstack", workingDir = null, containers = persistentListOf(flagged.first()))
        assertEquals(1, stack.totalCount)
    }
}
