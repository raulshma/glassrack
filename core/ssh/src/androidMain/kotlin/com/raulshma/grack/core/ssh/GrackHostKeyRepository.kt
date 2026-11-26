package com.raulshma.grack.core.ssh

import com.raulshma.grack.core.model.HostKeyChallenge
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/** The fingerprint format GRack stores on `Server.hostKeyFingerprint`. */
internal fun computeOpenSshSha256Fingerprint(rawKey: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(rawKey)
    return "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
}

/**
 * Verifies the remote host key during key exchange, i.e. BEFORE any authentication data
 * (password or key) reaches the remote host.
 *
 * Under `StrictHostKeyChecking=yes`, returning [HostKeyRepository.NOT_INCLUDED] makes JSch
 * abort the connection during key exchange. The recorded decision is surfaced by
 * [PlatformSshClient] as the existing [HostKeyChallengeException].
 */
internal class GrackHostKeyRepository(
    private val serverId: String,
    private val host: String,
    private val port: Int,
    private val expectedFingerprint: String?,
    private val trustUnknownOnFirstConnect: Boolean,
) : HostKeyRepository {

    private val challengeRef = AtomicReference<HostKeyChallenge?>(null)

    /** The decision recorded during the latest connection attempt, if any. */
    val recordedChallenge: HostKeyChallenge?
        get() = challengeRef.get()

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        val fingerprint = computeOpenSshSha256Fingerprint(key)
        val challenge = HostKeyChallenge(
            serverId = serverId,
            host = this.host,
            port = port,
            keyType = keyTypeName(key),
            fingerprintSha256 = fingerprint,
            expectedFingerprint = expectedFingerprint,
        )
        val expected = expectedFingerprint
        return if (expected != null) {
            if (expected == fingerprint) {
                HostKeyRepository.OK
            } else {
                challengeRef.compareAndSet(null, challenge)
                HostKeyRepository.NOT_INCLUDED // key changed
            }
        } else if (trustUnknownOnFirstConnect) {
            // Trust-on-first-connect: accept now; the fingerprint is persisted after login.
            challengeRef.compareAndSet(null, challenge)
            HostKeyRepository.OK
        } else {
            challengeRef.compareAndSet(null, challenge)
            HostKeyRepository.NOT_INCLUDED // unknown key, never auto-trusted
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

    override fun remove(host: String?, key: String?) = Unit

    override fun remove(host: String?, key: String?, blob: ByteArray?) = Unit

    override fun getKnownHostsRepositoryID(): String = ""

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    private fun keyTypeName(keyBlob: ByteArray): String =
        runCatching { HostKey(host, keyBlob).type }.getOrDefault("unknown")
}

/**
 * Never answers an interactive prompt with "yes". Host key acceptance is decided solely by
 * [GrackHostKeyRepository]; any other yes/no prompt from JSch must be declined.
 */
internal class GrackUserInfo(private val password: String?) : UserInfo {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = password
    override fun promptPassword(message: String?): Boolean = password != null
    override fun promptPassphrase(message: String?): Boolean = false
    override fun promptYesNo(message: String?): Boolean = false
    override fun showMessage(message: String?) = Unit
}

/** Decides which pooled sessions are idle enough to prune, ignoring busy ones. */
internal object IdlePrunePolicy {
    /**
     * @param lastUsedMsByServerId last refresh time per pooled session.
     * @param busyServerIds servers with a live tunnel or an open shell; they never refresh
     *   `lastUsedMs` and would be cut off if pruned by age alone.
     */
    fun selectIdle(
        lastUsedMsByServerId: Map<String, Long>,
        busyServerIds: Set<String>,
        nowMs: Long,
        maxIdleMs: Long,
    ): List<String> = lastUsedMsByServerId
        .filterKeys { it !in busyServerIds }
        .filterValues { nowMs - it > maxIdleMs }
        .keys.toList()
}
