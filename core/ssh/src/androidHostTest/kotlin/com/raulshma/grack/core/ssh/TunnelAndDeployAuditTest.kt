package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.Server
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 8 audit honesty: every tunnel toggle is audited both ways (close was never
 * recorded before), with the real outcome — a failed open or close records FAILED instead
 * of silently skipping the ledger — and `deployPublicKey` is audited with the exact
 * command the transport runs, the real outcome and the real duration.
 *
 * Every test stops the controller in `finally` (Phases 1A/3A/4 lesson): the controller's
 * loops are not children of the test scope, so a body that throws before an explicit stop
 * would leave the idle-prune loop re-arming virtual time and hang runTest's drain.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class TunnelAndDeployAuditTest {

    private val server = Server(
        id = "srv-1",
        name = "node-a",
        primaryHost = "10.0.0.5",
        capabilities = OsCapabilities(distro = "Debian GNU/Linux 12"),
    )

    private inline fun withHarness(scope: TestScope, block: (ControllerHarness) -> Unit) {
        val h = ControllerHarness(
            context = ApplicationProvider.getApplicationContext(),
            testScope = scope,
        )
        try {
            block(h)
        } finally {
            h.controller.stop()
        }
    }

    // --- Tunnels ---------------------------------------------------------------------------------

    @Test
    fun openingATunnelAuditsSuccessWithTheForwardPreview() = runTest {
        withHarness(this) { h ->
            val opened = h.controller.toggleTunnel("tun-1", server, 8096, "127.0.0.1", 8096)

            assertEquals(true, opened.getOrThrow())
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.first()
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals(AuditCategory.SSH, audit.category)
            assertEquals("Opened SSH Tunnel :8096 -> 127.0.0.1:8096", audit.actionTitle)
            assertTrue(audit.commandSanitized.contains("-L 8096:127.0.0.1:8096"), audit.commandSanitized)
        }
    }

    @Test
    fun closingATunnelIsAuditedToo() = runTest {
        withHarness(this) { h ->
            h.controller.toggleTunnel("tun-1", server, 8096, "127.0.0.1", 8096)

            val closed = h.controller.toggleTunnel("tun-1", server, 8096, "127.0.0.1", 8096)

            assertEquals(false, closed.getOrThrow())
            assertEquals(listOf("tun-1"), h.fake.closePortForwardRequests)
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.first()
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals("Closed SSH Tunnel :8096 -> 127.0.0.1:8096", audit.actionTitle)
        }
    }

    @Test
    fun failedOpenIsAuditedAsFailedInsteadOfSilent() = runTest {
        withHarness(this) { h ->
            h.fake.openLocalPortForwardHandler = { _, _, _, _, _ -> throw IOException("port 8096 busy") }

            val opened = h.controller.toggleTunnel("tun-1", server, 8096, "127.0.0.1", 8096)

            assertTrue(opened.isFailure)
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.first()
            assertEquals(AuditOutcome.FAILED, audit.outcome)
            assertEquals("Opened SSH Tunnel :8096 -> 127.0.0.1:8096", audit.actionTitle)
            assertEquals("port 8096 busy", audit.outputSnippet)
            // Nothing is half-open: the active set never gains the tunnel.
            assertTrue(h.controller.activeTunnels.value.isEmpty())
        }
    }

    @Test
    fun failedCloseIsAuditedAsFailedAndStaysActive() = runTest {
        withHarness(this) { h ->
            h.controller.toggleTunnel("tun-1", server, 8096, "127.0.0.1", 8096)
            h.fake.closePortForwardHandler = { throw IOException("socket gone") }

            val closed = h.controller.toggleTunnel("tun-1", server, 8096, "127.0.0.1", 8096)

            assertTrue(closed.isFailure)
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.first()
            assertEquals(AuditOutcome.FAILED, audit.outcome)
            assertEquals("Closed SSH Tunnel :8096 -> 127.0.0.1:8096", audit.actionTitle)
            assertEquals("socket gone", audit.outputSnippet)
            // Still registered as active so a retry can close it.
            assertEquals(setOf("tun-1"), h.controller.activeTunnels.value)
        }
    }

    // --- deployPublicKey -------------------------------------------------------------------------

    @Test
    fun deployPublicKeyAuditsTheExactCommandWithRealOutcomeAndDuration() = runTest {
        withHarness(this) { h ->
            val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI... grack@device"
            // The fake reports a real elapsed time of 123ms; the audit must carry it through
            // (the previous code never audited at all — or, where audited, fabricated values).
            h.fake.deployPublicKeyHandler = { _, _ -> SshExecResult(0, "added", "", 123L) }

            val result = h.controller.deployPublicKey(server, key)

            assertEquals(0, result.exitCode)
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.first()
            assertEquals(AuditCategory.CONFIG, audit.category)
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals(0, audit.exitCode)
            assertEquals(123L, audit.durationMs)
            // Honest command: the audit text is exactly what the Android transport executes.
            assertEquals(deployPublicKeyCommand(key), audit.commandSanitized)
            assertTrue(audit.commandSanitized.contains("authorized_keys"))
        }
    }

    @Test
    fun deployPublicKeyNonZeroExitAuditsFailed() = runTest {
        withHarness(this) { h ->
            val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI... grack@device"
            h.fake.deployPublicKeyHandler = { _, _ -> SshExecResult(1, "", "Permission denied", 42L) }

            val result = h.controller.deployPublicKey(server, key)

            assertEquals(1, result.exitCode)
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.first()
            assertEquals(AuditOutcome.FAILED, audit.outcome)
            assertEquals(1, audit.exitCode)
            assertEquals(42L, audit.durationMs)
            assertEquals("Permission denied", audit.outputSnippet)
        }
    }

    @Test
    fun deployPublicKeyDroppedConnectionAuditsFailedWithRealDuration() = runTest {
        withHarness(this) { h ->
            h.fake.deployPublicKeyHandler = { _, _ -> throw IOException("connection dropped") }

            val result = h.controller.deployPublicKey(server, "ssh-ed25519 AAAA test")

            assertEquals(-1, result.exitCode)
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.first()
            assertEquals(AuditOutcome.FAILED, audit.outcome)
            assertEquals(-1, audit.exitCode)
            assertEquals("connection dropped", audit.outputSnippet)
        }
    }
}
