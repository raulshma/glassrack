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
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Phase 2 privileged save: an SFTP write refused with permission denied is retried as a
 * temp upload followed by `cp -p <path> <path>.bak && install -m <mode> -o <owner> -g
 * <group> <tmp> <path>` (mode/owner/group preserved; the `&&` chain aborts when the backup
 * fails, keeping the Phase 1B .bak semantics). Hermetic: FakeSshSessionClient only. Every
 * test stops the controller in `finally` so a failed assertion cannot leave a delay loop
 * pumping on the test scheduler.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class PrivilegedSaveTest {

    private val sudoSecret = "Save-Sudo-Pass!"

    private val server = Server(
        id = "srv-1",
        name = "node-a",
        primaryHost = "10.0.0.5",
        sudoAuth = SudoAuth.SEPARATE_PASSWORD,
        capabilities = OsCapabilities(probeVersion = TelemetryParsers.CAPABILITIES_PROBE_VERSION),
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

    /** Permission-denied SFTP write; stat answers 644 root root; sudo needs a password. */
    private fun ControllerHarness.scriptPermissionDenied(
        statExit: Int = 0,
        installExit: Int = 0,
    ) {
        fake.writeFileHandler = {
            throw FilePermissionDeniedException("Permission denied writing /etc/nginx/nginx.conf")
        }
        fake.executeHandler = { request ->
            when {
                request.command.startsWith("stat -c") ->
                    SshExecResult(statExit, if (statExit == 0) "644 root root" else "", "stat failed".takeIf { statExit != 0 } ?: "", 2L)
                request.command == HomelabController.SUDO_PROBE_COMMAND ->
                    SshExecResult(1, "", "sudo: a password is required", 5L)
                request.command.startsWith("rm -f ") ->
                    SshExecResult(0, "", "", 1L)
                request.command.startsWith("sudo -S") ->
                    SshExecResult(installExit, if (installExit == 0) "installed" else "", "cp failed".takeIf { installExit != 0 } ?: "", 4L)
                else -> SshExecResult(0, "ok", "", 1L)
            }
        }
    }

    @Test
    fun permissionDeniedSaveEscalatesToTempUploadAndInstallPreservingModeOwnerGroup() = runTest {
        withController(this) { h ->
            h.scriptPermissionDenied()
            h.vault.putSecret(sudoSecretVaultId(server.id), sudoSecret)

            h.controller.saveFileWithBackup(server, "/etc/nginx/nginx.conf", "server { }", h.vault)

            // 1. Payload uploaded to a /tmp staging path.
            val upload = h.fake.uploadRequests.single()
            assertTrue(upload.remotePath.startsWith("/tmp/grack-save-"), upload.remotePath)
            assertTrue(upload.remotePath.endsWith(".tmp"), upload.remotePath)
            assertEquals("server { }", upload.content)

            // 2. cp -p backup first, then install preserving mode/owner/group; temp cleaned up.
            //    (Undo the outer shellQuote that executePrivileged applied to bash -c.)
            val install = h.fake.executeRequests.last { it.command.startsWith("sudo -S") }
            assertEquals("$sudoSecret\n", install.stdinText)
            val remote = install.command
                .substringAfter("bash -c ")
                .removeSurrounding("'")
                .replace("'\"'\"'", "'")
            assertTrue(
                remote.contains(
                    "cp -p '/etc/nginx/nginx.conf' '/etc/nginx/nginx.conf.bak' && " +
                        "install -m '644' -o 'root' -g 'root' ",
                ),
                remote,
            )
            assertTrue(remote.contains(" && rm -f '/tmp/grack-save-"), remote)

            // 3. Exactly one honest audit: the privileged install itself — real command,
            //    real outcome, no secret anywhere.
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.single()
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals("Saved SFTP File: /etc/nginx/nginx.conf", audit.actionTitle)
            assertTrue(audit.commandSanitized.contains("install -m"), audit.commandSanitized)
            assertTrue(audit.commandSanitized.contains(".bak"), audit.commandSanitized)
            assertTrue(!audit.commandSanitized.contains(sudoSecret), audit.commandSanitized)
        }
    }

    @Test
    fun unreadableStatAbortsThePrivilegedSaveWithoutUploadingAnything() = runTest {
        withController(this) { h ->
            h.scriptPermissionDenied(statExit = 1)
            h.vault.putSecret(sudoSecretVaultId(server.id), sudoSecret)

            val error = assertFailsWith<IllegalStateException> {
                h.controller.saveFileWithBackup(server, "/etc/shadow", "root:...", h.vault)
            }
            assertTrue(error.message!!.contains("could not stat /etc/shadow"), error.message)

            assertTrue(h.fake.uploadRequests.isEmpty())
            assertTrue(h.fake.executeRequests.none { it.command.startsWith("sudo -S") })
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals(AuditOutcome.FAILED, audit.outcome)
        }
    }

    @Test
    fun failingInstallIsAuditedAsFailedAndStagesNoSilentSuccess() = runTest {
        withController(this) { h ->
            h.scriptPermissionDenied(installExit = 1)
            h.vault.putSecret(sudoSecretVaultId(server.id), sudoSecret)

            val error = assertFailsWith<IllegalStateException> {
                h.controller.saveFileWithBackup(server, "/etc/nginx/nginx.conf", "server { }", h.vault)
            }
            assertTrue(error.message!!.contains("Privileged install failed"), error.message)

            // Best-effort temp cleanup ran; the failure is on the ledger, not a fake success.
            assertTrue(h.fake.executeRequests.any { it.command.startsWith("rm -f '/tmp/grack-save-") })
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals(AuditOutcome.FAILED, audit.outcome)
            assertTrue(audit.outputSnippet.contains("cp failed"), audit.outputSnippet)
        }
    }

    @Test
    fun permissionDeniedWithoutVaultStillFailsHonestly() = runTest {
        withController(this) { h ->
            h.fake.writeFileHandler = {
                throw FilePermissionDeniedException("Permission denied writing /etc/fstab")
            }

            val error = assertFailsWith<FilePermissionDeniedException> {
                h.controller.saveFileWithBackup(server, "/etc/fstab", "content", vault = null)
            }

            assertTrue(error.message!!.contains("/etc/fstab"))
            assertTrue(h.fake.uploadRequests.isEmpty())
            assertTrue(h.fake.executeRequests.isEmpty())
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals(AuditOutcome.FAILED, audit.outcome)
        }
    }

    @Test
    fun regularIoErrorIsNotMistakenForPermissionDenied() = runTest {
        withController(this) { h ->
            h.vault.putSecret(sudoSecretVaultId(server.id), sudoSecret)
            h.fake.writeFileHandler = { throw IOException("disk full") }

            assertFailsWith<IOException> {
                h.controller.saveFileWithBackup(server, "/var/log/app.log", "content", h.vault)
            }

            assertTrue(h.fake.uploadRequests.isEmpty())
            assertTrue(h.fake.executeRequests.none { it.command.startsWith("sudo -S") })
        }
    }
}
