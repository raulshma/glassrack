package com.example.glassrack.core.ssh

import co.touchlab.kermit.Logger
import com.example.glassrack.core.database.GlassrackRepository
import com.example.glassrack.core.model.AuthType
import com.example.glassrack.core.model.HostKeyChallenge
import com.example.glassrack.core.model.RemoteFileEntry
import com.example.glassrack.core.model.Server
import com.example.glassrack.core.security.SecureVault
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Vector
import java.util.concurrent.ConcurrentHashMap

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
    private val connectMutex = Mutex()

    private suspend fun getOrConnect(
        server: Server,
        allServers: List<Server>,
        trustFingerprintOnFirstConnect: Boolean = false,
    ): Session = connectMutex.withLock {
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
        if (server.authType == AuthType.PASSWORD && !server.credentialVaultId.isNullOrBlank()) {
            val pw = vault.getSecret(server.credentialVaultId!!)
            if (pw != null) session.setPassword(pw)
        }
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        session.serverAliveInterval = 30_000
        session.serverAliveCountMax = 3

        val pwForPrompt = server.credentialVaultId?.let { vault.getSecret(it) }
        session.userInfo = object : UserInfo {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String? = pwForPrompt
            override fun promptPassword(message: String?): Boolean = pwForPrompt != null
            override fun promptPassphrase(message: String?): Boolean = false
            override fun promptYesNo(message: String?): Boolean = true
            override fun showMessage(message: String?) = Unit
        }

        session.connect(8_000)

        val hostKey = session.hostKey
        if (hostKey != null) {
            val sha256 = computeOpenSshSha256(hostKey.keyBytes())
            val expected = server.hostKeyFingerprint
            if (expected == null) {
                if (trustFingerprintOnFirstConnect) {
                    repository.updateHostFingerprint(server.id, sha256)
                } else {
                    session.disconnect()
                    jumpSession?.disconnect()
                    throw HostKeyChallengeException(
                        HostKeyChallenge(
                            serverId = server.id,
                            host = targetHost,
                            port = targetPort,
                            keyType = hostKey.type,
                            fingerprintSha256 = sha256,
                            expectedFingerprint = null,
                        ),
                    )
                }
            } else if (expected != sha256) {
                session.disconnect()
                jumpSession?.disconnect()
                throw HostKeyChallengeException(
                    HostKeyChallenge(
                        serverId = server.id,
                        host = targetHost,
                        port = targetPort,
                        keyType = hostKey.type,
                        fingerprintSha256 = sha256,
                        expectedFingerprint = expected,
                    ),
                )
            }
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

    private fun computeOpenSshSha256(rawKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawKey)
        return "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
    }

    private fun com.jcraft.jsch.HostKey.keyBytes(): ByteArray {
        return Base64.getDecoder().decode(key)
    }

    override suspend fun testConnection(
        server: Server,
        allServers: List<Server>,
        trustFingerprintOnFirstConnect: Boolean,
    ): Result<HostKeyChallenge?> = runCatching {
        try {
            getOrConnect(server, allServers, trustFingerprintOnFirstConnect)
            null
        } catch (e: HostKeyChallengeException) {
            e.challenge
        }
    }

    override suspend fun execute(
        server: Server,
        allServers: List<Server>,
        command: String,
        timeoutMs: Long,
    ): SshExecResult = withTimeout(timeoutMs) {
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val session = getOrConnect(server, allServers)
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val outStream = ByteArrayOutputStream()
            val errStream = ByteArrayOutputStream()
            channel.outputStream = outStream
            channel.setErrStream(errStream)
            channel.connect(5_000)
            while (!channel.isClosed) {
                Thread.sleep(25)
            }
            val exitCode = channel.exitStatus
            channel.disconnect()
            SshExecResult(
                exitCode = exitCode,
                stdout = outStream.toByteArray().decodeToString().trim(),
                stderr = errStream.toByteArray().decodeToString().trim(),
                durationMs = System.currentTimeMillis() - started,
            )
        }
    }

    override suspend fun executeStreaming(
        server: Server,
        allServers: List<Server>,
        command: String,
        timeoutMs: Long,
        onChunk: suspend (String) -> Unit,
    ): SshExecResult = withTimeout(timeoutMs) {
        val started = System.currentTimeMillis()
        val session = getOrConnect(server, allServers)
        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()
        var exitCode = -1

        withContext(Dispatchers.IO) {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val inStream = channel.inputStream
            val errStream = channel.errStream
            channel.connect(5_000)
            val buffer = ByteArray(2048)
            while (true) {
                while (inStream.available() > 0) {
                    val read = inStream.read(buffer)
                    if (read <= 0) break
                    val chunk = buffer.decodeToString(0, read)
                    stdoutBuilder.append(chunk)
                    onChunk(chunk)
                }
                while (errStream.available() > 0) {
                    val read = errStream.read(buffer)
                    if (read <= 0) break
                    val chunk = buffer.decodeToString(0, read)
                    stderrBuilder.append(chunk)
                    onChunk(chunk)
                }
                if (channel.isClosed) {
                    if (inStream.available() > 0 || errStream.available() > 0) continue
                    exitCode = channel.exitStatus
                    break
                }
                Thread.sleep(40)
            }
            channel.disconnect()
        }

        SshExecResult(
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
        JschShellSession(channel)
    }

    override suspend fun listFiles(
        server: Server,
        allServers: List<Server>,
        remotePath: String,
    ): List<RemoteFileEntry> = withContext(Dispatchers.IO) {
        val session = getOrConnect(server, allServers)
        val sftp = session.openChannel("sftp") as ChannelSftp
        sftp.connect(5_000)
        try {
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
        } finally {
            sftp.disconnect()
        }
    }

    override suspend fun readFileText(
        server: Server,
        allServers: List<Server>,
        remotePath: String,
    ): String = withContext(Dispatchers.IO) {
        val session = getOrConnect(server, allServers)
        val sftp = session.openChannel("sftp") as ChannelSftp
        sftp.connect(5_000)
        try {
            sftp.get(remotePath).use { it.readBytes().decodeToString() }
        } finally {
            sftp.disconnect()
        }
    }

    override suspend fun writeFileTextWithBackup(
        server: Server,
        allServers: List<Server>,
        remotePath: String,
        newContent: String,
    ) {
        withContext(Dispatchers.IO) {
            val session = getOrConnect(server, allServers)
            val sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(5_000)
            try {
                runCatching {
                    val current = sftp.get(remotePath).use { it.readBytes() }
                    sftp.put(ByteArrayInputStream(current), "$remotePath.bak", ChannelSftp.OVERWRITE)
                }
                sftp.put(ByteArrayInputStream(newContent.encodeToByteArray()), remotePath, ChannelSftp.OVERWRITE)
            } finally {
                sftp.disconnect()
            }
        }
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
        val now = System.currentTimeMillis()
        pool.keys.toList().forEach { id ->
            val entry = pool[id] ?: return@forEach
            if (now - entry.lastUsedMs > maxIdleMs) {
                disconnect(id)
            }
        }
    }
}

private class JschShellSession(private val channel: ChannelShell) : SshShellSession {
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
