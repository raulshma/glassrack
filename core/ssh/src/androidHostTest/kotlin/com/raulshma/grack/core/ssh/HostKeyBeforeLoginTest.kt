package com.raulshma.grack.core.ssh

import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the item 5 decision logic: unknown or changed keys are rejected (NOT_INCLUDED) so
 * JSch aborts during key exchange under StrictHostKeyChecking=yes, i.e. before any password
 * or key material reaches the remote host.
 */
class HostKeyBeforeLoginTest {

    private fun generateKeyBlob(): ByteArray {
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        return pair.getPublicKeyBlob() ?: error("key pair produced no public key blob")
    }

    private fun challenge(serverId: String = "srv-1") = GrackHostKeyRepository(
        serverId = serverId,
        host = "10.0.0.5",
        port = 22,
        expectedFingerprint = null,
        trustUnknownOnFirstConnect = false,
    )

    @Test
    fun unknownKeyIsRejectedWithoutTrust() {
        val repo = challenge()
        val blob = generateKeyBlob()

        val result = repo.check("10.0.0.5", blob)

        assertEquals(HostKeyRepository.NOT_INCLUDED, result)
        val recorded = repo.recordedChallenge
        assertEquals("srv-1", recorded?.serverId)
        assertEquals(22, recorded?.port)
        assertEquals("SHA256:", recorded?.fingerprintSha256?.take("SHA256:".length))
        assertNull(recorded?.expectedFingerprint)
    }

    @Test
    fun unknownKeyIsAcceptedWhenTrustOnFirstConnectIsRequested() {
        val repo = GrackHostKeyRepository(
            serverId = "srv-1",
            host = "10.0.0.5",
            port = 22,
            expectedFingerprint = null,
            trustUnknownOnFirstConnect = true,
        )
        val blob = generateKeyBlob()

        assertEquals(HostKeyRepository.OK, repo.check("10.0.0.5", blob))
        // Challenge is recorded so the client can persist the fingerprint after login.
        assertEquals(
            computeOpenSshSha256Fingerprint(blob),
            repo.recordedChallenge?.fingerprintSha256,
        )
    }

    @Test
    fun changedKeyIsRejectedWithTheExpectedFingerprint() {
        val blob = generateKeyBlob()
        val otherBlob = generateKeyBlob()
        val repo = GrackHostKeyRepository(
            serverId = "srv-1",
            host = "10.0.0.5",
            port = 22,
            expectedFingerprint = computeOpenSshSha256Fingerprint(blob),
            trustUnknownOnFirstConnect = false,
        )

        val result = repo.check("10.0.0.5", otherBlob)

        assertEquals(HostKeyRepository.NOT_INCLUDED, result)
        val recorded = repo.recordedChallenge
        assertTrue(recorded?.isMismatch == true, "a changed key must surface as a mismatch")
        assertEquals(computeOpenSshSha256Fingerprint(blob), recorded?.expectedFingerprint)
        assertEquals(computeOpenSshSha256Fingerprint(otherBlob), recorded?.fingerprintSha256)
    }

    @Test
    fun matchingKeyIsAccepted() {
        val blob = generateKeyBlob()
        val repo = GrackHostKeyRepository(
            serverId = "srv-1",
            host = "10.0.0.5",
            port = 22,
            expectedFingerprint = computeOpenSshSha256Fingerprint(blob),
            trustUnknownOnFirstConnect = false,
        )

        assertEquals(HostKeyRepository.OK, repo.check("10.0.0.5", blob))
        assertNull(repo.recordedChallenge)
    }

    @Test
    fun nullKeyIsRejected() {
        assertEquals(HostKeyRepository.NOT_INCLUDED, challenge().check("10.0.0.5", null))
    }

    @Test
    fun userInfoNeverAnswersYesToInteractivePrompts() {
        assertNull(GrackUserInfo(null).password)
        assertEquals(false, GrackUserInfo(null).promptYesNo("Are you sure you want to continue connecting?"))
        assertEquals(false, GrackUserInfo("secret").promptYesNo("Anything"))
    }

    @Test
    fun idlePrunePolicyIgnoresBusySessionsAndFreshOnes() {
        val lastUsed = mapOf(
            "stale" to 1_000L,
            "fresh" to 999_000L,
            "tunnelled" to 1_000L,
            "shell" to 1_000L,
        )
        val busy = setOf("tunnelled", "shell")
        val selected = IdlePrunePolicy.selectIdle(
            lastUsedMsByServerId = lastUsed,
            busyServerIds = busy,
            nowMs = 1_000_000L,
            maxIdleMs = 15 * 60_000L,
        )
        assertEquals(listOf("stale"), selected)
    }
}
