package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.DistroFamily
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.SudoAuth
import com.raulshma.grack.core.model.TriggerSource
import com.raulshma.grack.core.model.sudoSecretVaultId
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3B item 3: the OS update apply path. Commands per distro family (apt env /
 * lock-timeout / conffile flags; dnf/pacman/apk forms), routing through
 * [HomelabController.executePrivileged] (root as-is, sudo wrapped), streamed with a
 * 30-minute budget, audited as SYSTEM, serialized by the per-server apply mutex, NEVER
 * rebooting, and followed by the read-only reboot-recommendation probe.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class OsApplyPathTest {

    private val secret = "Sudo-Passw0rd!123"

    private fun server(
        distroId: String,
        isRoot: Boolean = false,
        sudoAuth: SudoAuth = SudoAuth.NONE,
    ) = Server(
        id = "srv-1",
        name = "node-a",
        primaryHost = "10.0.0.5",
        sudoAuth = sudoAuth,
        capabilities = OsCapabilities(distroId = distroId, isRoot = isRoot),
    )

    private fun harness(scope: TestScope) = ControllerHarness(
        context = ApplicationProvider.getApplicationContext(),
        testScope = scope,
    )

    private suspend fun TestScope.withController(block: suspend (ControllerHarness) -> Unit) {
        val h = harness(this)
        try {
            block(h)
        } finally {
            h.controller.stop()
        }
    }

    /** Serves `sudo -n true` with [probeExit]; the apply and reboot probes with exit 0. */
    private fun ControllerHarness.scriptProbe(probeExit: Int) {
        fake.executeHandler = { request ->
            when {
                request.command == HomelabController.SUDO_PROBE_COMMAND ->
                    SshExecResult(probeExit, "", if (probeExit == 0) "" else "sudo: a password is required", 5L)
                request.command.contains("===REBOOTCHK===") ->
                    SshExecResult(0, RebootFixtures.required, "", 5L)
                else -> SshExecResult(0, "applied", "", 5L)
            }
        }
    }

    private object RebootFixtures {
        val required = """
            ===REBOOTCHK===
            REBOOT_REQUIRED=1
            __NR_EXIT__=skip
        """.trimIndent()
        val notRequired = """
            ===REBOOTCHK===
            REBOOT_REQUIRED=0
            __NR_EXIT__=skip
        """.trimIndent()
        val needsRestarting = """
            ===REBOOTCHK===
            REBOOT_REQUIRED=0
            __NR_EXIT__=1
        """.trimIndent()
    }

    // ------------------------------------------------------------------
    // Apply command construction per distro
    // ------------------------------------------------------------------

    @Test
    fun debianApplyCarriesEnvLockTimeoutAndConffileFlags() {
        val cmd = UpdateProbes.osUpdateApplyCommand(DistroFamily.DEBIAN)
        assertTrue(cmd.startsWith("DEBIAN_FRONTEND=noninteractive "), cmd)
        assertTrue(cmd.contains("-o DPkg::Lock::Timeout=600"), cmd)
        assertTrue(cmd.contains("--force-confdef"), cmd)
        assertTrue(cmd.contains("--force-confold"), cmd)
        assertTrue(cmd.trim().endsWith("upgrade"), cmd)
    }

    @Test
    fun otherFamiliesUseTheirPackageManagersPlainForms() {
        assertEquals("dnf -y upgrade", UpdateProbes.osUpdateApplyCommand(DistroFamily.RHEL))
        assertEquals("pacman -Syu --noconfirm", UpdateProbes.osUpdateApplyCommand(DistroFamily.ARCH))
        assertEquals("apk upgrade", UpdateProbes.osUpdateApplyCommand(DistroFamily.ALPINE))
        assertEquals("", UpdateProbes.osUpdateApplyCommand(DistroFamily.UNSUPPORTED))
    }

    /** The Phase 3B safety invariant: an OS apply NEVER reboots or shuts the node down. */
    @Test
    fun applyCommandsNeverContainRebootOrShutdownVerbs() {
        val forbidden = listOf("reboot", "shutdown", "poweroff", "halt", "init 0", "init 6", "systemctl")
        DistroFamily.entries.forEach { family ->
            val cmd = UpdateProbes.osUpdateApplyCommand(family).lowercase()
            forbidden.forEach { verb ->
                assertFalse(cmd.contains(verb), "apply for $family must not contain '$verb': $cmd")
            }
        }
    }

    // ------------------------------------------------------------------
    // Controller path: privileged routing, streaming, timeout, audit
    // ------------------------------------------------------------------

    @Test
    fun rootLoginRunsTheDebianApplyAsIsStreamedWithThirtyMinuteBudget() = runTest {
        withController { h ->
            val root = server("debian", isRoot = true)

            h.controller.applyOsUpdates(root, h.vault)

            val request = h.fake.streamRequests.single()
            assertEquals(UpdateProbes.osUpdateApplyCommand(DistroFamily.DEBIAN), request.command)
            assertEquals(HomelabController.OS_APPLY_TIMEOUT_MS, request.timeoutMs)
            assertNull(request.stdinText)

            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals(AuditCategory.SYSTEM, audit.category)
            assertEquals("Apply OS updates", audit.actionTitle)
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals(TriggerSource.UI, audit.triggerSource)
        }
    }

    @Test
    fun sudoCredentialDeliversThePasswordOnStdinNotInTheCommand() = runTest {
        withController { h ->
            h.scriptProbe(probeExit = 1)
            h.vault.putSecret(sudoSecretVaultId("srv-1"), secret)
            val credNode = server("fedora", sudoAuth = SudoAuth.SEPARATE_PASSWORD)

            h.controller.applyOsUpdates(credNode, h.vault)

            val request = h.fake.streamRequests.single()
            assertEquals(
                "sudo -S -p '' bash -c '${UpdateProbes.osUpdateApplyCommand(DistroFamily.RHEL)}'",
                request.command,
            )
            assertEquals("$secret\n", request.stdinText)
            assertFalse(request.command.contains(secret), request.command)
        }
    }

    @Test
    fun archAndAlpineApplyWithoutARebootProbe() = runTest {
        withController { h ->
            val arch = server("arch", isRoot = true)
            val alpine = server("alpine", isRoot = true)

            val archResult = h.controller.applyOsUpdates(arch, h.vault)
            val alpineResult = h.controller.applyOsUpdates(alpine, h.vault)

            assertEquals("pacman -Syu --noconfirm", h.fake.streamRequests[0].command)
            assertEquals("apk upgrade", h.fake.streamRequests[1].command)
            // No reliable pre-reboot marker exists on Arch/Alpine: never recommended.
            assertFalse(archResult.rebootRecommended)
            assertFalse(alpineResult.rebootRecommended)
            assertTrue(h.fake.executeRequests.none { it.command.contains("===REBOOTCHK===") })
        }
    }

    @Test
    fun unsupportedDistroIsBlockedWithoutAnyExecution() = runTest {
        withController { h ->
            val unknown = server("nixos", isRoot = true)

            val result = h.controller.applyOsUpdates(unknown, h.vault)

            assertEquals(-1, result.exec.exitCode)
            assertTrue(h.fake.streamRequests.isEmpty())
            assertTrue(h.fake.executeRequests.isEmpty())
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.single()
            assertEquals(AuditOutcome.BLOCKED, audit.outcome)
            assertEquals(AuditCategory.SYSTEM, audit.category)
        }
    }

    @Test
    fun failedApplySkipsTheRebootProbe() = runTest {
        withController { h ->
            val root = server("debian", isRoot = true)
            h.fake.streamHandler = { _ -> SshExecResult(1, "", "apt failed", 5L) }

            val result = h.controller.applyOsUpdates(root, h.vault)

            assertEquals(1, result.exec.exitCode)
            assertFalse(result.rebootRecommended)
            assertTrue(h.fake.executeRequests.none { it.command.contains("===REBOOTCHK===") })
        }
    }

    // ------------------------------------------------------------------
    // Reboot recommendation probe
    // ------------------------------------------------------------------

    @Test
    fun successfulDebianApplySurfacesRebootRecommended() = runTest {
        withController { h ->
            val root = server("debian", isRoot = true)
            h.fake.executeHandler = { request ->
                if (request.command.contains("===REBOOTCHK===")) {
                    SshExecResult(0, RebootFixtures.required, "", 5L)
                } else {
                    SshExecResult(0, "applied", "", 5L)
                }
            }

            val result = h.controller.applyOsUpdates(root, h.vault)

            assertTrue(result.rebootRecommended)
            assertTrue(h.fake.executeRequests.any { it.command.contains("/var/run/reboot-required") })
        }
    }

    @Test
    fun needsRestartingExitOneAlsoMeansRebootRecommended() = runTest {
        withController { h ->
            val root = server("fedora", isRoot = true)
            h.fake.executeHandler = { request ->
                if (request.command.contains("===REBOOTCHK===")) {
                    SshExecResult(0, RebootFixtures.needsRestarting, "", 5L)
                } else {
                    SshExecResult(0, "applied", "", 5L)
                }
            }

            assertTrue(h.controller.applyOsUpdates(root, h.vault).rebootRecommended)
        }
    }

    @Test
    fun cleanDebianApplyIsNotRebootRecommended() = runTest {
        withController { h ->
            val root = server("debian", isRoot = true)
            h.fake.executeHandler = { request ->
                if (request.command.contains("===REBOOTCHK===")) {
                    SshExecResult(0, RebootFixtures.notRequired, "", 5L)
                } else {
                    SshExecResult(0, "applied", "", 5L)
                }
            }

            assertFalse(h.controller.applyOsUpdates(root, h.vault).rebootRecommended)
        }
    }

    @Test
    fun rebootRecommendationParserReadsBothSignals() {
        assertTrue(UpdateParsers.parseRebootRecommended(RebootFixtures.required, DistroFamily.DEBIAN))
        assertTrue(UpdateParsers.parseRebootRecommended(RebootFixtures.needsRestarting, DistroFamily.RHEL))
        assertFalse(UpdateParsers.parseRebootRecommended(RebootFixtures.notRequired, DistroFamily.DEBIAN))
        assertFalse(UpdateParsers.parseRebootRecommended("", DistroFamily.ARCH))
        assertFalse(UpdateParsers.parseRebootRecommended(RebootFixtures.required, DistroFamily.ALPINE))
    }

    // ------------------------------------------------------------------
    // Per-server apply mutex
    // ------------------------------------------------------------------

    @Test
    fun twoAppliesToOneNodeNeverInterleave() = runTest {
        withController { h ->
            val root = server("debian", isRoot = true)
            var inFlight = 0
            var overlap = false
            h.fake.streamHandler = { _ ->
                inFlight++
                if (inFlight > 1) overlap = true
                delay(1_000L)
                inFlight--
                SshExecResult(0, "applied", "", 1_000L)
            }

            val first = async { h.controller.applyOsUpdates(root, h.vault) }
            val second = async { h.controller.applyOsUpdates(root, h.vault) }
            first.await()
            second.await()

            assertFalse(overlap, "applies to the same server overlapped")
            assertEquals(2, h.fake.streamRequests.size)
        }
    }

    @Test
    fun appliesToDifferentServersRunConcurrently() = runTest {
        withController { h ->
            val nodeA = server("debian", isRoot = true).copy(id = "srv-a")
            val nodeB = server("fedora", isRoot = true).copy(id = "srv-b")
            var inFlight = 0
            var overlap = false
            h.fake.streamHandler = { _ ->
                inFlight++
                if (inFlight > 1) overlap = true
                delay(1_000L)
                inFlight--
                SshExecResult(0, "applied", "", 1_000L)
            }

            val first = launch { h.controller.applyOsUpdates(nodeA, h.vault) }
            val second = launch { h.controller.applyOsUpdates(nodeB, h.vault) }
            first.join()
            second.join()

            assertTrue(overlap, "different servers must not serialize against each other")
        }
    }

    /** The whole path stays reboot-free even when the probe says a reboot is recommended. */
    @Test
    fun noCommandSentByTheApplyPathEverReboots() = runTest {
        withController { h ->
            val root = server("debian", isRoot = true)
            h.scriptProbe(probeExit = 0) // reboot probe answers REBOOT_REQUIRED=1

            val result = h.controller.applyOsUpdates(root, h.vault)

            assertTrue(result.rebootRecommended)
            val allCommands = h.fake.executeRequests.map { it.command } + h.fake.streamRequests.map { it.command }
            assertTrue(allCommands.isNotEmpty())
            // The apply itself (and any sudo wrapping of it) must never reboot; the read-only
            // recommendation probe legitimately mentions the `/var/run/reboot-required` file,
            // so it is excluded from the verb scan but its intent is checked separately:
            // it reads a marker file and never executes a power action.
            allCommands.filterNot { it.contains("===REBOOTCHK===") }.forEach { cmd ->
                val lower = cmd.lowercase()
                assertFalse(lower.contains("reboot") || lower.contains("shutdown") || lower.contains("poweroff"), cmd)
            }
            assertTrue(
                allCommands.filter { it.contains("===REBOOTCHK===") }.all { it.contains("needs-restarting") || it.contains("-f /var/run/reboot-required") },
                "the reboot probe may only read, never act",
            )
        }
    }
}
