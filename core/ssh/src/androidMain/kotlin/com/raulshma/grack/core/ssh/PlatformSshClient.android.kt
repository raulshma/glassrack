package com.raulshma.grack.core.ssh

import co.touchlab.kermit.Logger
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.AuthType
import com.raulshma.grack.core.model.HostKeyChallenge
import com.raulshma.grack.core.model.RemoteFileEntry
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.security.SecureVault
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Vector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

actual class PlatformSshClient actual constructor(
    private val vault: SecureVault,
    private val repository: GlassrackRepository,
) : SshSessionClient {

    private data class PooledSession(
        val session: Session,
        val endpointHost: String,
        val lastUsedMs: Long,
        val jumpSession: Session? = null,
    )

    private data class ActiveTunnel(
        val serverId: String,
        val localPort: Int,
    )

    private val pool = ConcurrentHashMap<String, PooledSession>()
    private val tunnels = ConcurrentHashMap<String, ActiveTunnel>()

    // One mutex per server: a slow connect to an unreachable node must not serialise
    // connects to healthy nodes behind it (previously one global mutex).
    private val connectMutexes = ConcurrentHashMap<String, Mutex>()

    // Servers with an interactive shell open; their pooled session must not be pruned by age.
    private val openShellsByServer = ConcurrentHashMap<String, MutableSet<ChannelShell>>()

    private fun connectMutexFor(serverId: String): Mutex =
        connectMutexes.computeIfAbsent(serverId) { Mutex() }

    private suspend fun getOrConnect(
        server: Server,
        allServers: List<Server>,
        trustFingerprintOnFirstConnect: Boolean = false,
    ): Session = connectMutexFor(server.id).withLock {
        val existing = pool[server.id]
        if (existing != null && existing.session.isConnected) {
            pool[server.id] = existing.copy(lastUsedMs = System.currentTimeMillis())
            return existing.session
        }
        existing?.session?.disconnect()
        existing?.jumpSession?.disconnect()

        val resolved = openNewSession(server, allServers, trustFingerprintOnFirstConnect)
        pool[server.id] = resolved
        resolved.session
    }

    private suspend fun openNewSession(
        server: Server,
        allServers: List<Server>,
        trustFingerprintOnFirstConnect: Boolean,
        transientPassword: String? = null,
    ): PooledSession = withContext(Dispatchers.IO) {
        var jumpSession: Session? = null
        var targetHost = selectReachableHost(server)
        var targetPort = server.port

        if (!server.jumpHostServerId.isNullOrBlank()) {
            val jumpServer = allServers.firstOrNull { it.id == server.jumpHostServerId }
                ?: throw IllegalStateException("Jump host ${server.jumpHostServerId} not found")
            jumpSession = openNewSession(jumpServer, allServers, trustFingerprintOnFirstConnect).session
            val assignedLocalPort = jumpSession.setPortForwardingL(0, targetHost, targetPort)
            targetHost = "127.0.0.1"
            targetPort = assignedLocalPort
        }

        val jsch = JSch()
        if (server.authType == AuthType.KEY && !server.sshKeyId.isNullOrBlank()) {
            val keyRecord = repository.getSshKeyById(server.sshKeyId!!)
            if (keyRecord != null) {
                val privPem = vault.getSecret(keyRecord.privateKeyVaultRef)
                    ?: throw IllegalStateException("Missing private key in SecureVault")
                val passphrase = vault.getSecret("${keyRecord.privateKeyVaultRef}_pass")
                jsch.addIdentity(
                    keyRecord.alias,
                    privPem.encodeToByteArray(),
                    keyRecord.publicKeyOpenSsh.encodeToByteArray(),
                    passphrase?.encodeToByteArray(),
                )
            }
        }

        val session = jsch.getSession(server.username, targetHost, targetPort)
        // A transient password (handshake-only connectivity test) wins over the stored one
        // and is never persisted anywhere.
        val effectivePassword = transientPassword
            ?: server.credentialVaultId?.let { vault.getSecret(it) }
        if (server.authType == AuthType.PASSWORD && effectivePassword != null) {
            session.setPassword(effectivePassword)
        }
        // Host key is verified by GrackHostKeyRepository during key exchange, before login.
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        session.serverAliveInterval = 30_000
        session.serverAliveCountMax = 3

        val pwForPrompt = effectivePassword
        val hostKeyRepository = GrackHostKeyRepository(
            serverId = server.id,
            host = targetHost,
            port = targetPort,
            expectedFingerprint = server.hostKeyFingerprint,
            trustUnknownOnFirstConnect = trustFingerprintOnFirstConnect,
        )
        session.hostKeyRepository = hostKeyRepository
        session.userInfo = GrackUserInfo(pwForPrompt)

        try {
            session.connect(8_000)
        } catch (e: Exception) {
            session.disconnect()
            jumpSession?.disconnect()
            // A rejection from the host key repository surfaces here: JSch aborts during
            // key exchange, so the password/key was never sent to the unverified host.
            hostKeyRepository.recordedChallenge?.let { challenge ->
                throw HostKeyChallengeException(challenge)
            }
            throw e
        }

        val hostKey = session.hostKey
        if (hostKey != null && server.hostKeyFingerprint == null && trustFingerprintOnFirstConnect) {
            repository.updateHostFingerprint(server.id, computeOpenSshSha256(hostKey.keyBytes()))
        }

        PooledSession(
            session = session,
            endpointHost = targetHost,
            lastUsedMs = System.currentTimeMillis(),
            jumpSession = jumpSession,
        )
    }

    private suspend fun selectReachableHost(server: Server): String = coroutineScope {
        if (server.fallbackHost.isBlank()) return@coroutineScope server.primaryHost
        val primaryReachable = async(Dispatchers.IO) { probeTcp(server.primaryHost, server.port, 1200) }
        if (primaryReachable.await()) {
            server.primaryHost
        } else {
            if (probeTcp(server.fallbackHost, server.port, 2000)) server.fallbackHost else server.primaryHost
        }
    }

    private fun probeTcp(host: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            true
        }.getOrDefault(false)
    }

    internal fun computeOpenSshSha256(rawKey: ByteArray): String = computeOpenSshSha256Fingerprint(rawKey)

    private fun com.jcraft.jsch.HostKey.keyBytes(): ByteArray {
        return Base64.getDecoder().decode(key)
    }

    override suspend fun testConnection(
        server: Server,
        allServers: List<Server>,
        trustFingerprintOnFirstConnect: Boolean,
        transientPassword: String?,
    ): Result<HostKeyChallenge?> = runCatching {
        // Probe with a dedicated, never-pooled session: the test must validate the node as
        // configured right now (including a transient password that was never persisted),
        // and must not leave a half-configured draft node behind in the session pool.
        var probe: PooledSession? = null
        try {
            probe = openNewSession(server, allServers, trustFingerprintOnFirstConnect, transientPassword)
            null
        } catch (e: HostKeyChallengeException) {
            e.challenge
        } finally {
            runCatching { probe?.session?.disconnect() }
            runCatching { probe?.jumpSession?.disconnect() }
        }
    }

    override suspend fun execute(
        server: Server,
        allServers: List<Server>,
        command: String,
        timeoutMs: Long,
        stdinText: String?,
    ): SshExecResult {
        val started = System.currentTimeMillis()
        val session = getOrConnect(server, allServers)
        val channel = session.openChannel("exec") as ChannelExec
        val outStream = CappedByteArrayOutputStream(MAX_CAPTURED_OUTPUT_BYTES)
        val errStream = CappedByteArrayOutputStream(MAX_CAPTURED_OUTPUT_BYTES)
        try {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) {
                    channel.setCommand(command)
                    if (stdinText != null) {
                        // Exec channel only (never a PTY): bytes are written, then EOF.
                        channel.setInputStream(ByteArrayInputStream(stdinText.encodeToByteArray()))
                    }
                    channel.outputStream = outStream
                    channel.setErrStream(errStream)
                    channel.connect(5_000)
                    while (!channel.isClosed) {
                        // delay() is cancellable, so withTimeout really ends the wait here.
                        delay(EXEC_POLL_INTERVAL_MS)
                    }
                }
            }
            val stdout = Utf8ChunkDecoder().decode(outStream.toByteArray())
            val stderr = Utf8ChunkDecoder().decode(errStream.toByteArray())
            return SshExecResult(
                exitCode = channel.exitStatus,
                stdout = stdout.trim(),
                stderr = stderr.trim(),
                durationMs = System.currentTimeMillis() - started,
            )
        } finally {
            runCatching { channel.disconnect() }
                .onFailure { Logger.d(it) { "Exec channel disconnect failed" } }
        }
    }

    override suspend fun executeStreaming(
        server: Server,
        allServers: List<Server>,
        command: String,
        timeoutMs: Long,
        stdinText: String?,
        onChunk: suspend (String) -> Unit,
    ): SshExecResult {
        val started = System.currentTimeMillis()
        val session = getOrConnect(server, allServers)
        val channel = session.openChannel("exec") as ChannelExec
        val stdoutDecoder = Utf8ChunkDecoder()
        val stderrDecoder = Utf8ChunkDecoder()
        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()
        var exitCode = -1

        try {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) {
                    channel.setCommand(command)
                    if (stdinText != null) {
                        channel.setInputStream(ByteArrayInputStream(stdinText.encodeToByteArray()))
                    }
                    val inStream = channel.inputStream
                    val errStream = channel.errStream
                    channel.connect(5_000)
                    val buffer = ByteArray(2048)
                    while (true) {
                        while (inStream.available() > 0) {
                            val read = inStream.read(buffer)
                            if (read <= 0) break
                            val chunk = stdoutDecoder.decode(buffer, read)
                            if (stdoutBuilder.length < MAX_STREAMED_OUTPUT_CHARS) stdoutBuilder.append(chunk)
                            onChunk(chunk)
                        }
                        while (errStream.available() > 0) {
                            val read = errStream.read(buffer)
                            if (read <= 0) break
                            val chunk = stderrDecoder.decode(buffer, read)
                            if (stderrBuilder.length < MAX_STREAMED_OUTPUT_CHARS) stderrBuilder.append(chunk)
                            onChunk(chunk)
                        }
                        if (channel.isClosed) {
                            if (inStream.available() > 0 || errStream.available() > 0) continue
                            exitCode = channel.exitStatus
                            break
                        }
                        delay(STREAM_POLL_INTERVAL_MS)
                    }
                    for (decoder in listOf(stdoutDecoder, stderrDecoder)) {
                        val tail = decoder.flush()
                        if (tail.isNotEmpty()) onChunk(tail)
                    }
                    channel.disconnect()
                }
            }
        } finally {
            runCatching { channel.disconnect() }
                .onFailure { Logger.d(it) { "Streaming channel disconnect failed" } }
        }

        return SshExecResult(
            exitCode = exitCode,
            stdout = stdoutBuilder.toString().trim(),
            stderr = stderrBuilder.toString().trim(),
            durationMs = System.currentTimeMillis() - started,
        )
    }

    override suspend fun openShell(
        server: Server,
        allServers: List<Server>,
        cols: Int,
        rows: Int,
    ): SshShellSession = withContext(Dispatchers.IO) {
        val session = getOrConnect(server, allServers)
        val channel = session.openChannel("shell") as ChannelShell
        channel.setPtyType("xterm-256color")
        channel.setPtySize(cols, rows, cols * 12, rows * 24)
        channel.connect(5_000)
        val shells = openShellsByServer.computeIfAbsent(server.id) { ConcurrentHashMap.newKeySet() }
        shells.add(channel)
        JschShellSession(channel, onClose = { shells.remove(channel) })
    }

    /**
     * Runs a blocking SFTP operation under [timeoutMs]. The channel reference lets the
     * timeout path disconnect the channel, unblocking a read stuck on a dead connection.
     */
    private suspend fun <T> runSftpOperation(
        server: Server,
        allServers: List<Server>,
        timeoutMs: Long,
        operation: suspend (ChannelSftp) -> T,
    ): T = withTimeout(timeoutMs) {
        val channelRef = AtomicReference<ChannelSftp?>(null)
        coroutineScope {
            val deferred = async(Dispatchers.IO) {
                val session = getOrConnect(server, allServers)
                val sftp = session.openChannel("sftp") as ChannelSftp
                channelRef.set(sftp)
                sftp.connect(5_000)
                try {
                    operation(sftp)
                } finally {
                    runCatching { sftp.disconnect() }
                }
            }
            try {
                deferred.await()
            } catch (tce: TimeoutCancellationException) {
                runCatching { channelRef.get()?.disconnect() }
                throw tce
            }
        }
    }

    override suspend fun listFiles(
        server: Server,
        allServers: List<Server>,
        remotePath: String,
        timeoutMs: Long,
    ): List<RemoteFileEntry> = runSftpOperation(server, allServers, timeoutMs) { sftp ->
        @Suppress("UNCHECKED_CAST")
        val entries = sftp.ls(remotePath) as Vector<ChannelSftp.LsEntry>
        entries
            .filter { it.filename != "." && it.filename != ".." }
            .map { entry ->
                val cleanDir = remotePath.trimEnd('/')
                RemoteFileEntry(
                    name = entry.filename,
                    fullPath = if (cleanDir.isEmpty()) "/${entry.filename}" else "$cleanDir/${entry.filename}",
                    isDirectory = entry.attrs.isDir,
                    sizeBytes = entry.attrs.size,
                    permissions = entry.attrs.permissionsString,
                    modifiedEpochSec = entry.attrs.mTime.toLong(),
                )
            }
            .sortedWith(compareBy<RemoteFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun readFileText(
        server: Server,
        allServers: List<Server>,
        remotePath: String,
        timeoutMs: Long,
    ): String = runSftpOperation(server, allServers, timeoutMs) { sftp ->
        val bytes = sftp.get(remotePath).use { it.readBytes() }
        // Binary guard: over 1 MiB or containing NUL bytes never enters the text editor.
        TextFileGuard.validate(bytes, remotePath)
        bytes.decodeToString()
    }

    override suspend fun writeFileTextWithBackup(
        server: Server,
        allServers: List<Server>,
        remotePath: String,
        newContent: String,
        timeoutMs: Long,
    ): Boolean = runSftpOperation(server, allServers, timeoutMs) { sftp ->
        val payload = newContent.encodeToByteArray()
        TextFileGuard.validate(payload, remotePath)
        // Backup first: a failed .bak aborts the save so the original is never lost.
        val backupCaptured = try {
            val current = sftp.get(remotePath).use { it.readBytes() }
            sftp.put(ByteArrayInputStream(current), "$remotePath.bak", ChannelSftp.OVERWRITE)
            true
        } catch (missing: com.jcraft.jsch.SftpException) {
            if (missing.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                false // new file: nothing to back up yet
            } else {
                throw IllegalStateException("Backup write failed for $remotePath; save aborted so the original is preserved", missing)
            }
        }
        sftp.put(ByteArrayInputStream(payload), remotePath, ChannelSftp.OVERWRITE)
        backupCaptured
    }

    override suspend fun deployPublicKey(
        server: Server,
        allServers: List<Server>,
        publicKeyOpenSsh: String,
    ): SshExecResult {
        val escaped = publicKeyOpenSsh.trim().replace("'", "'\"'\"'")
        val cmd = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && grep -qxF '$escaped' ~/.ssh/authorized_keys 2>/dev/null || echo '$escaped' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
        return execute(server, allServers, cmd, 10_000L)
    }

    override suspend fun openLocalPortForward(
        tunnelId: String,
        server: Server,
        allServers: List<Server>,
        localPort: Int,
        remoteHost: String,
        remotePort: Int,
    ): Int = withContext(Dispatchers.IO) {
        val session = getOrConnect(server, allServers)
        val bound = session.setPortForwardingL(localPort, remoteHost, remotePort)
        tunnels[tunnelId] = ActiveTunnel(server.id, bound)
        bound
    }

    override suspend fun closePortForward(tunnelId: String) {
        val active = tunnels.remove(tunnelId) ?: return
        val session = pool[active.serverId]?.session ?: return
        withContext(Dispatchers.IO) {
            runCatching { session.delPortForwardingL(active.localPort) }
        }
    }

    override suspend fun disconnect(serverId: String) {
        val removed = pool.remove(serverId) ?: return
        withContext(Dispatchers.IO) {
            runCatching { removed.session.disconnect() }
            runCatching { removed.jumpSession?.disconnect() }
        }
    }

    override suspend fun disconnectIdle(maxIdleMs: Long) {
        val busyServerIds = buildSet {
            addAll(tunnels.values.map { it.serverId })
            addAll(openShellsByServer.filterValues { it.isNotEmpty() }.keys)
        }
        val lastUsedByServerId = pool.entries.associate { it.key to it.value.lastUsedMs }
        IdlePrunePolicy
            .selectIdle(lastUsedByServerId, busyServerIds, System.currentTimeMillis(), maxIdleMs)
            .forEach { disconnect(it) }
    }

    private companion object {
        /** Per-stream capture cap for `execute` so long-running commands cannot exhaust memory. */
        const val MAX_CAPTURED_OUTPUT_BYTES: Int = 2 * 1024 * 1024

        /** Retained-output cap for `executeStreaming`; live chunks still reach the caller. */
        const val MAX_STREAMED_OUTPUT_CHARS: Int = 2_000_000

        const val EXEC_POLL_INTERVAL_MS = 25L
        const val STREAM_POLL_INTERVAL_MS = 40L
    }
}

private class JschShellSession(
    private val channel: ChannelShell,
    private val onClose: () -> Unit = {},
) : SshShellSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _output = MutableSharedFlow<ByteArray>(replay = 4, extraBufferCapacity = 128)
    override val outputFlow: Flow<ByteArray> = _output.asSharedFlow()
    private val _connected = MutableStateFlow(true)
    override val isConnected: StateFlow<Boolean> = _connected.asStateFlow()

    private val outputStream = channel.outputStream

    init {
        scope.launch {
            val input = channel.inputStream
            val buffer = ByteArray(4096)
            try {
                while (isActive && channel.isConnected) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        _output.emit(buffer.copyOf(read))
                    }
                }
            } catch (t: Throwable) {
                Logger.d(t) { "Shell read loop ended" }
            } finally {
                _connected.value = false
            }
        }
    }

    override suspend fun sendBytes(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            if (channel.isConnected) {
                outputStream.write(bytes)
                outputStream.flush()
            }
        }
    }

    override suspend fun resizePty(cols: Int, rows: Int) {
        withContext(Dispatchers.IO) {
            if (channel.isConnected) {
                runCatching { channel.setPtySize(cols, rows, cols * 12, rows * 24) }
            }
        }
    }

    override suspend fun close() {
        _connected.value = false
        withContext(Dispatchers.IO) {
            runCatching { channel.disconnect() }
        }
        onClose()
    }
}

actual object WakeOnLanSender {
    actual suspend fun sendDirectMagicPacket(
        macAddress: String,
        broadcastIp: String,
        port: Int,
    ) = withContext(Dispatchers.IO) {
        val clean = macAddress.replace(":", "").replace("-", "").trim()
        require(clean.length == 12) { "Invalid MAC address: $macAddress" }
        val macBytes = ByteArray(6) { idx ->
            clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
        val packetBytes = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packetBytes[i] = 0xFF.toByte()
        for (i in 0 until 16) {
            macBytes.copyInto(packetBytes, destinationOffset = 6 + i * 6)
        }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val address = InetAddress.getByName(broadcastIp)
            socket.send(DatagramPacket(packetBytes, packetBytes.size, address, port))
        }
    }
}
