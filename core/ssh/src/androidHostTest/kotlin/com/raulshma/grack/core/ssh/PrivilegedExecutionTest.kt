package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.SudoAuth
import com.raulshma.grack.core.model.sudoSecretVaultId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 2: executePrivileged resolves the privilege mode per call (root / passwordless sudo
 * / stored credential / fallback) and the sudo password exists ONLY in the transport's
 * stdinText — never in a command string, job preview, audit entry or streamed output.
 * Hermetic: FakeSshSessionClient, no sockets. Every test stops the controller in `finally`:
 * an uncancelled controller keeps re-arming a 15-minute delay on the test scheduler, and a
 * failed assertion must not turn teardown into an endless drain.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class PrivilegedExecutionTest {

    private val secret = "Sudo-Passw0rd!123"

    private fun server(
        sudoAuth: SudoAuth = SudoAuth.NONE,
        isRoot: Boolean = false,
        credentialVaultId: String? = null,
    ) = Server(
        id = "srv-1",
        name = "node-a",
        primaryHost = "10.0.0.5",
        authType = if (credentialVaultId != null) com.raulshma.grack.core.model.AuthType.PASSWORD
            else com.raulshma.grack.core.model.AuthType.KEY,
        credentialVaultId = credentialVaultId,
        sudoAuth = sudoAuth,
        capabilities = OsCapabilities(isRoot = isRoot, probeVersion = TelemetryParsers.CAPABILITIES_PROBE_VERSION),
    )

    private fun harness(scope: TestScope) = ControllerHarness(
        context = ApplicationProvider.getApplicationContext(),
        testScope = scope,
    )

    private inline fun <T> withController(scope: TestScope, block: (ControllerHarness) -> T): T {
        val h = harness(scope)
        try {
            return block(h)
        } finally {
            h.controller.stop()
        }
    }

    /** Serves `sudo -n true` with [probeExit]; everything else with [commandExit]. */
    private fun ControllerHarness.scriptProbe(probeExit: Int, commandExit: Int = 0) {
        fake.executeHandler = { request ->
            if (request.command == HomelabController.SUDO_PROBE_COMMAND) {
                SshExecResult(probeExit, "", if (probeExit == 0) "" else "sudo: a password is required", 5L)
            } else {
                SshExecResult(commandExit, "done", "", 5L)
            }
        }
    }

    @Test
    fun rootLoginRunsTheCommandAsIsWithoutAnyProbe() = runTest {
        withController(this) { h ->
            h.controller.executePrivileged(
                server = server(isRoot = true),
                command = "systemctl restart nginx",
                category = com.raulshma.grack.core.model.AuditCategory.SERVICE,
                actionTitle = "Service restart: nginx",
                vault = h.vault,
            )

            assertEquals(listOf("systemctl restart nginx"), h.fake.executeRequests.map { it.command })
            assertTrue(h.fake.executeRequests.all { it.stdinText == null })
        }
    }

    @Test
    fun passwordlessSudoWrapsQuotedAndCachesTheProbeForTenMinutes() = runTest {
        withController(this) { h ->
            h.scriptProbe(probeExit = 0)

            repeat(2) {
                h.controller.executePrivileged(
                    server = server(),
                    command = "systemctl restart 'weird name'",
                    category = com.raulshma.grack.core.model.AuditCategory.SERVICE,
                    actionTitle = "Service restart",
                    vault = h.vault,
                )
            }

            // Exactly one probe despite two privileged calls (10 minute cache); both real
            // runs are wrapped, and quotes survive the shared shellQuote.
            assertEquals(1, h.fake.executeRequests.count { it.command == HomelabController.SUDO_PROBE_COMMAND })
            val wrapped = h.fake.executeRequests.map { it.command }.filter { it != HomelabController.SUDO_PROBE_COMMAND }
            assertEquals(2, wrapped.size)
            assertTrue(wrapped.all { it == "sudo -n bash -c 'systemctl restart '\"'\"'weird name'\"'\"''" }, wrapped.first())
            assertTrue(h.fake.executeRequests.all { it.stdinText == null })
        }
    }

    @Test
    fun separateSudoPasswordIsDeliveredOnStdinThenEof() = runTest {
        withController(this) { h ->
            h.scriptProbe(probeExit = 1)
            h.vault.putSecret(sudoSecretVaultId("srv-1"), secret)

            h.controller.executePrivileged(
                server = server(sudoAuth = SudoAuth.SEPARATE_PASSWORD),
                command = "systemctl stop nginx",
                category = com.raulshma.grack.core.model.AuditCategory.SERVICE,
                actionTitle = "Service stop: nginx",
                vault = h.vault,
            )

            val run = h.fake.executeRequests.last()
            assertEquals("sudo -S -p '' bash -c 'systemctl stop nginx'", run.command)
            // Password on stdin, newline-terminated, then EOF (exec channel, never a PTY).
            assertEquals("$secret\n", run.stdinText)
        }
    }

    @Test
    fun loginPasswordSudoReadsTheLoginVaultIdAtUseTime() = runTest {
        withController(this) { h ->
            h.scriptProbe(probeExit = 1)
            h.vault.putSecret("srv_pw_srv-1", secret)

            h.controller.executePrivileged(
                server = server(sudoAuth = SudoAuth.LOGIN_PASSWORD, credentialVaultId = "srv_pw_srv-1"),
                command = "reboot",
                category = com.raulshma.grack.core.model.AuditCategory.POWER,
                actionTitle = "System Reboot",
                vault = h.vault,
            )

            val run = h.fake.executeRequests.last()
            assertEquals("sudo -S -p '' bash -c 'reboot'", run.command)
            assertEquals("$secret\n", run.stdinText)
        }
    }

    @Test
    fun withoutRootProbeOrCredentialItFallsBackToSudoNOrPlain() = runTest {
        withController(this) { h ->
            h.scriptProbe(probeExit = 1)

            h.controller.executePrivileged(
                server = server(),
                command = "shutdown -h now",
                category = com.raulshma.grack.core.model.AuditCategory.POWER,
                actionTitle = "System Shutdown",
                vault = h.vault,
            )

            val run = h.fake.executeRequests.last()
            assertEquals(
                "sudo -n bash -c 'shutdown -h now' || bash -c 'shutdown -h now'",
                run.command,
            )
            assertNull(run.stdinText)
        }
    }

    /**
     * The security requirement: the sudo password may exist only in stdinText. Scan every
     * recorded command, every stdinText, every job record and every audit entry for it.
     */
    @Test
    fun sudoPasswordNeverAppearsInCommandsJobsOrAuditRecords() = runTest {
        withController(this) { h ->
            h.scriptProbe(probeExit = 1)
            h.vault.putSecret(sudoSecretVaultId("srv-1"), secret)

            h.controller.executePrivileged(
                server = server(sudoAuth = SudoAuth.SEPARATE_PASSWORD),
                command = "journalctl -u nginx -n 80 --no-pager",
                category = com.raulshma.grack.core.model.AuditCategory.SERVICE,
                actionTitle = "Inspect journalctl nginx",
                vault = h.vault,
                streaming = true, // exercise the streamed path: chunks feed the job live output
            )
            h.controller.rebootOrShutdown(server(sudoAuth = SudoAuth.SEPARATE_PASSWORD), reboot = true, vault = h.vault)

            // Every stdinText: exactly the two credential deliveries, nothing else carries the secret.
            val allStdins = h.fake.executeRequests.map { it.stdinText } + h.fake.streamRequests.map { it.stdinText }
            assertEquals(2, allStdins.count { it == "$secret\n" }, allStdins.toString())
            assertTrue(allStdins.all { it == null || it == "$secret\n" })

            // Commands (probe + real runs) never embed the password.
            val allCommands = h.fake.executeRequests.map { it.command } + h.fake.streamRequests.map { it.command }
            assertTrue(allCommands.none { it.contains(secret) }, allCommands.toString())

            // Job records: preview, live output, titles.
            val jobs = h.repository.activeJobs.value
            assertTrue(jobs.isNotEmpty())
            jobs.forEach { job ->
                assertTrue(!job.commandPreview.contains(secret), job.commandPreview)
                assertTrue(!job.liveOutput.contains(secret), job.liveOutput)
                assertTrue(!job.title.contains(secret), job.title)
            }

            // Audit entries: every stored field.
            val audits = h.repository.auditEntries.first { it.isNotEmpty() }
            assertEquals(2, audits.size)
            audits.forEach { entry ->
                assertTrue(!entry.commandSanitized.contains(secret), entry.commandSanitized)
                assertTrue(!entry.outputSnippet.contains(secret), entry.outputSnippet)
                assertTrue(!entry.actionTitle.contains(secret), entry.actionTitle)
            }
            assertEquals(AuditOutcome.SUCCESS, audits.last().outcome)
        }
    }

    /** The converted call sites: systemd verbs, reboot, foreign-PID signals, journalctl. */
    @Test
    fun systemdRebootSignalAndJournalRoutesGoThroughExecutePrivileged() = runTest {
        withController(this) { h ->
            h.scriptProbe(probeExit = 1)
            h.vault.putSecret(sudoSecretVaultId("srv-1"), secret)
            val sudoAuthServer = server(sudoAuth = SudoAuth.SEPARATE_PASSWORD)

            h.controller.performSystemdAction(sudoAuthServer, "nginx", "restart", h.vault)
            h.controller.rebootOrShutdown(sudoAuthServer, reboot = false, vault = h.vault)
            h.controller.sendProcessSignal(
                sudoAuthServer,
                com.raulshma.grack.core.model.ProcessInfo(
                    pid = 4321, user = "www-data", cpuPct = 1f, memPct = 1f,
                    rssKb = 1000, state = "S", elapsed = "1:00", command = "nginx",
                ),
                com.raulshma.grack.core.model.ProcessSignal.SIGTERM,
                h.vault,
            )
            h.controller.fetchJournalLogs(sudoAuthServer, "nginx", h.vault)

            val runs = h.fake.executeRequests.filter { it.command != HomelabController.SUDO_PROBE_COMMAND }
            assertEquals(4, runs.size, runs.map { it.command }.toString())
            assertEquals("sudo -S -p '' bash -c 'systemctl restart '\"'\"'nginx'\"'\"''", runs[0].command)
            assertEquals("sudo -S -p '' bash -c 'shutdown -h now'", runs[1].command)
            assertEquals("sudo -S -p '' bash -c 'kill -15 4321'", runs[2].command)
            assertEquals("sudo -S -p '' bash -c 'journalctl -u '\"'\"'nginx'\"'\"' -n 80 --no-pager'", runs[3].command)
            runs.forEach { assertEquals("$secret\n", it.stdinText, it.command) }
        }
    }
}
