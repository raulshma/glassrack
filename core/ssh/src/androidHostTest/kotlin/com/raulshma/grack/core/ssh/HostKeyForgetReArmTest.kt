package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.Server
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 8 known-hosts manager: "Forget" clears the node's trusted fingerprint, and the
 * state machine re-arms — the very key that was trusted before the forget is now rejected
 * (NOT_INCLUDED) with a fresh challenge, i.e. the TOFU verification runs again on the next
 * connect. The reset itself is audited as a security-relevant CONFIG change.
 *
 * Every test stops the controller in `finally` (Phases 1A/3A/4 lesson): the controller's
 * own loops are not children of the test scope, so a body that throws before an explicit
 * stop would leave the idle-prune loop re-arming virtual time and hang runTest's drain.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class HostKeyForgetReArmTest {

    private val server = Server(id = "srv-1", name = "node-a", primaryHost = "10.0.0.5")

    private inline fun withHarness(scope: TestScope, block: (ControllerHarness) -> Unit) {
        val h = ControllerHarness(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            testScope = scope,
        )
        try {
            block(h)
        } finally {
            // Stop controller loops before runTest's quiescence drain, even on failure.
            h.controller.stop()
        }
    }

    private fun generateKeyBlob(): ByteArray {
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        return pair.getPublicKeyBlob() ?: error("key pair produced no public key blob")
    }

    private fun repositoryFor(server: Server) = GrackHostKeyRepository(
        serverId = server.id,
        host = server.primaryHost,
        port = server.port,
        expectedFingerprint = server.hostKeyFingerprint,
        trustUnknownOnFirstConnect = false,
    )

    @Test
    fun forgetClearsTheFingerprintAndReArmsTheChallenge() = runTest {
        withHarness(this) { h ->
            // The node must exist for the fingerprint columns to be written at all.
            h.repository.upsertServer(server)
            val keyBlob = generateKeyBlob()
            val fingerprint = computeOpenSshSha256Fingerprint(keyBlob)

            // TOFU: the fingerprint is trusted and persisted.
            h.repository.updateHostFingerprint(server.id, fingerprint)
            val trusted = h.repository.getServerById(server.id)!!
            assertEquals(fingerprint, trusted.hostKeyFingerprint)
            val before = repositoryFor(trusted)
            assertEquals(HostKeyRepository.OK, before.check(server.primaryHost, keyBlob))
            assertNull(before.recordedChallenge)

            // Forget: the stored fingerprint is cleared.
            val forgot = h.controller.forgetHostKey(trusted)
            assertTrue(forgot.isSuccess, forgot.exceptionOrNull()?.toString() ?: "")

            val cleared = h.repository.getServerById(server.id)!!
            assertNull(cleared.hostKeyFingerprint)

            // Re-armed: the SAME key that was trusted before is now rejected with a challenge.
            val after = repositoryFor(cleared)
            assertEquals(HostKeyRepository.NOT_INCLUDED, after.check(server.primaryHost, keyBlob))
            assertEquals(fingerprint, after.recordedChallenge?.fingerprintSha256)
            assertEquals(server.id, after.recordedChallenge?.serverId)
        }
    }

    @Test
    fun forgetIsAuditedAsConfigChange() = runTest {
        withHarness(this) { h ->
            h.repository.upsertServer(server)
            h.repository.updateHostFingerprint(server.id, "SHA256:abcdef")

            h.controller.forgetHostKey(h.repository.getServerById(server.id)!!)

            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.first()
            assertEquals(AuditCategory.CONFIG, audit.category)
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals("Forgot Host Key: node-a", audit.actionTitle)
            assertTrue(audit.commandSanitized.contains("forget-host-key"), audit.commandSanitized)
            assertTrue(audit.commandSanitized.contains(server.primaryHost), audit.commandSanitized)
            assertNotEquals(0L, audit.timestampEpochMs)
        }
    }

    @Test
    fun forgetOfUnknownNodeStillAuditsButLeavesNothingBehind() = runTest {
        withHarness(this) { h ->
            val ghost = Server(id = "srv-ghost", name = "ghost", primaryHost = "10.0.0.9")

            val result = h.controller.forgetHostKey(ghost)

            // The clear is a no-op for a missing row, but the security-relevant attempt
            // still lands in the ledger.
            assertTrue(result.isSuccess)
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.first()
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals("ghost", audit.serverNameSnapshot)
        }
    }
}
