package com.example.glassrack.core.ssh

import co.touchlab.kermit.Logger
import com.example.glassrack.core.database.GlassrackRepository
import com.example.glassrack.core.model.AlertMetric
import com.example.glassrack.core.model.AlertRule
import com.example.glassrack.core.model.AppAlert
import com.example.glassrack.core.model.AuditCategory
import com.example.glassrack.core.model.AuditOutcome
import com.example.glassrack.core.model.ContainerAction
import com.example.glassrack.core.model.ContainerInfo
import com.example.glassrack.core.model.ContainerRuntime
import com.example.glassrack.core.model.CronEntry
import com.example.glassrack.core.model.HostKeyChallenge
import com.example.glassrack.core.model.JobPhase
import com.example.glassrack.core.model.MetricSnapshot
import com.example.glassrack.core.model.NetworkInterfaceInfo
import com.example.glassrack.core.model.NetworkPortInfo
import com.example.glassrack.core.model.ProcessInfo
import com.example.glassrack.core.model.ProcessSignal
import com.example.glassrack.core.model.RemoteFileEntry
import com.example.glassrack.core.model.Server
import com.example.glassrack.core.model.ServerHealth
import com.example.glassrack.core.model.ServerRuntimeState
import com.example.glassrack.core.model.SystemdUnit
import com.example.glassrack.core.model.TriggerSource
import com.example.glassrack.core.model.currentTimeEpochMs
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HomelabController(
    private val repository: GlassrackRepository,
    private val sshClient: PlatformSshClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val simulator = DemoClusterSimulator()
    private val sampleCache = TelemetrySampleCache()

    private val _runtimeStates = MutableStateFlow<Map<String, ServerRuntimeState>>(emptyMap())
    val runtimeStates: StateFlow<Map<String, ServerRuntimeState>> = _runtimeStates.asStateFlow()

    private val _activeAlerts = MutableStateFlow<List<AppAlert>>(emptyList())
    val activeAlerts: StateFlow<List<AppAlert>> = _activeAlerts.asStateFlow()

    private val _activeTunnels = MutableStateFlow<Set<String>>(emptySet())
    val activeTunnels: StateFlow<Set<String>> = _activeTunnels.asStateFlow()

    private val pollingJobs = mutableMapOf<String, Job>()
    private var latestServers: List<Server> = emptyList()
    private var latestRules: List<AlertRule> = emptyList()

    init {
        scope.launch {
            repository.ensureSeeded(currentTimeEpochMs())
        }
        scope.launch {
            repository.servers.collect { servers ->
                latestServers = servers
                syncRuntimeMap(servers)
                restartPollers(servers)
            }
        }
        scope.launch {
            repository.recentMetricsByServer.collect { historyMap ->
                _runtimeStates.update { current ->
                    current.mapValues { (id, state) ->
                        val history = historyMap[id].orEmpty()
                        state.copy(
                            latestMetric = state.latestMetric ?: history.lastOrNull(),
                            history = history.toImmutableList(),
                        )
                    }
                }
            }
        }
        scope.launch {
            repository.alertRules.collect { rules ->
                latestRules = rules
            }
        }
    }

    private fun syncRuntimeMap(servers: List<Server>) {
        _runtimeStates.update { existing ->
            servers.associate { server ->
                val prev = existing[server.id]
                server.id to (prev?.copy(server = server) ?: ServerRuntimeState(server = server))
            }
        }
    }

    private fun restartPollers(servers: List<Server>) {
        val activeIds = servers.map { it.id }.toSet()
        pollingJobs.keys.filter { it !in activeIds }.forEach { removed ->
            pollingJobs.remove(removed)?.cancel()
        }
        servers.forEach { server ->
            if (pollingJobs[server.id]?.isActive != true) {
                pollingJobs[server.id] = scope.launch {
                    while (isActive) {
                        val currentServer = latestServers.firstOrNull { it.id == server.id } ?: break
                        pollServerOnce(currentServer)
                        delay(currentServer.pollIntervalSec.coerceAtLeast(2) * 1000L)
                    }
                }
            }
        }
    }

    suspend fun pollServerOnce(server: Server) {
        val now = currentTimeEpochMs()
        if (server.isSimulated) {
            val snapshot = simulator.sampleSnapshot(server, now)
            repository.recordMetricSnapshot(snapshot)
            updateSuccessState(server, snapshot)
            evaluateAlerts(server, snapshot)
            return
        }

        _runtimeStates.update { map ->
            val cur = map[server.id] ?: ServerRuntimeState(server)
            if (cur.health == ServerHealth.UNKNOWN) {
                map + (server.id to cur.copy(health = ServerHealth.CONNECTING))
            } else map
        }

        try {
            var activeServer = server
            if (activeServer.capabilities.distro.isBlank()) {
                val capResult = sshClient.execute(activeServer, latestServers, TelemetryProbes.detectCapabilitiesScript(), 10_000L)
                if (capResult.exitCode == 0 && capResult.stdout.isNotBlank()) {
                    val caps = TelemetryParsers.parseCapabilities(capResult.stdout)
                    activeServer = activeServer.copy(capabilities = caps)
                    repository.upsertServer(activeServer)
                }
            }
            val start = currentTimeEpochMs()
            val probeCmd = TelemetryProbes.buildCompositeProbe(activeServer.capabilities, activeServer.customProbes)
            val execResult = sshClient.execute(activeServer, latestServers, probeCmd, 12_000L)
            val latency = (currentTimeEpochMs() - start).toInt().coerceAtLeast(1)
            val snapshot = TelemetryParsers.parseCompositeSnapshot(
                serverId = activeServer.id,
                timestampMs = currentTimeEpochMs(),
                latencyMs = latency,
                activeEndpoint = activeServer.primaryHost,
                output = execResult.stdout,
                cache = sampleCache,
                customProbes = activeServer.customProbes,
            )
            repository.recordMetricSnapshot(snapshot)
            updateSuccessState(activeServer, snapshot)
            evaluateAlerts(activeServer, snapshot)
        } catch (challengeEx: HostKeyChallengeException) {
            _runtimeStates.update { map ->
                val cur = map[server.id] ?: ServerRuntimeState(server)
                map + (server.id to cur.copy(
                    health = ServerHealth.WARNING,
                    lastError = challengeEx.message,
                    pendingHostKeyChallenge = challengeEx.challenge,
                ))
            }
        } catch (t: Throwable) {
            Logger.w(t) { "Poll failed for ${server.name}" }
            _runtimeStates.update { map ->
                val cur = map[server.id] ?: ServerRuntimeState(server)
                map + (server.id to cur.copy(
                    health = ServerHealth.OFFLINE,
                    lastError = t.message ?: "Connection failed",
                ))
            }
        }
    }

    private fun updateSuccessState(server: Server, snapshot: MetricSnapshot) {
        val health = when {
            snapshot.rpiStats?.underVoltageDetected == true || snapshot.rpiStats?.currentlyThrottled == true -> ServerHealth.WARNING
            (snapshot.tempC ?: 0f) > 80f || snapshot.cpuPct > 92f -> ServerHealth.WARNING
            else -> ServerHealth.ONLINE
        }
        _runtimeStates.update { map ->
            val cur = map[server.id] ?: ServerRuntimeState(server)
            val newHistory = (cur.history + snapshot).takeLast(40).toImmutableList()
            map + (server.id to cur.copy(
                server = server,
                health = health,
                latestMetric = snapshot,
                history = newHistory,
                lastSeenEpochMs = snapshot.timestampEpochMs,
                lastError = null,
                pendingHostKeyChallenge = null,
            ))
        }
    }

    private fun evaluateAlerts(server: Server, snapshot: MetricSnapshot) {
        val triggered = mutableListOf<AppAlert>()
        latestRules.filter { it.enabled && (it.serverId == null || it.serverId == server.id) }.forEach { rule ->
            val value = when (rule.metric) {
                AlertMetric.CPU_PCT -> snapshot.cpuPct
                AlertMetric.RAM_PCT -> snapshot.memPct
                AlertMetric.DISK_PCT -> snapshot.disks.maxOfOrNull { it.usedPercent.toFloat() } ?: 0f
                AlertMetric.TEMP_C -> snapshot.tempC ?: 0f
                AlertMetric.SERVER_OFFLINE -> 0f
            }
            if (value >= rule.threshold) {
                triggered += AppAlert(
                    id = "${rule.id}-${server.id}",
                    ruleId = rule.id,
                    serverId = server.id,
                    serverName = server.name,
                    message = "${rule.name}: ${value.toInt()} (threshold ${rule.threshold.toInt()})",
                    timestampEpochMs = snapshot.timestampEpochMs,
                )
            }
        }
        _activeAlerts.update { existing ->
            val filtered = existing.filterNot { it.serverId == server.id }
            (filtered + triggered).takeLast(20)
        }
    }

    suspend fun acceptHostKeyChallenge(challenge: HostKeyChallenge) {
        repository.updateHostFingerprint(challenge.serverId, challenge.fingerprintSha256)
        _runtimeStates.update { map ->
            val cur = map[challenge.serverId] ?: return@update map
            map + (challenge.serverId to cur.copy(pendingHostKeyChallenge = null, lastError = null))
        }
        latestServers.firstOrNull { it.id == challenge.serverId }?.let { pollServerOnce(it) }
    }

    suspend fun testServerConnection(server: Server, trustFirst: Boolean): Result<HostKeyChallenge?> {
        if (server.isSimulated) return Result.success(null)
        return sshClient.testConnection(server, latestServers, trustFingerprintOnFirstConnect = trustFirst)
    }

    suspend fun executeAuditedAction(
        server: Server,
        category: AuditCategory,
        actionTitle: String,
        command: String,
        triggerSource: TriggerSource = TriggerSource.UI,
        streaming: Boolean = false,
    ): SshExecResult {
        val started = currentTimeEpochMs()
        val jobId = repository.startJob(server.id, server.name, actionTitle, command, started)
        return try {
            val result = if (server.isSimulated) {
                delay(350)
                repository.appendJobOutput(jobId, "$ $command\n", 350)
                val out = "Simulated execution on ${server.name}: OK"
                SshExecResult(0, out, "", 420L)
            } else if (streaming) {
                sshClient.executeStreaming(server, latestServers, command) { chunk ->
                    repository.appendJobOutput(jobId, chunk, currentTimeEpochMs() - started)
                }
            } else {
                sshClient.execute(server, latestServers, command)
            }
            val outcome = if (result.exitCode == 0) AuditOutcome.SUCCESS else AuditOutcome.FAILED
            repository.finishJob(
                jobId = jobId,
                phase = if (result.exitCode == 0) JobPhase.SUCCEEDED else JobPhase.FAILED,
                exitCode = result.exitCode,
                durationMs = result.durationMs,
                outputTail = result.stdout.ifBlank { result.stderr },
            )
            repository.recordAudit(
                timestampEpochMs = currentTimeEpochMs(),
                serverId = server.id,
                serverNameSnapshot = server.name,
                category = category,
                actionTitle = actionTitle,
                rawCommand = command,
                triggerSource = triggerSource,
                outcome = outcome,
                exitCode = result.exitCode,
                durationMs = result.durationMs,
                outputSnippet = result.stdout.ifBlank { result.stderr },
            )
            result
        } catch (t: Throwable) {
            val elapsed = currentTimeEpochMs() - started
            repository.finishJob(jobId, JobPhase.FAILED, -1, elapsed, t.message)
            repository.recordAudit(
                timestampEpochMs = currentTimeEpochMs(),
                serverId = server.id,
                serverNameSnapshot = server.name,
                category = category,
                actionTitle = actionTitle,
                rawCommand = command,
                triggerSource = triggerSource,
                outcome = AuditOutcome.FAILED,
                exitCode = -1,
                durationMs = elapsed,
                outputSnippet = t.message.orEmpty(),
            )
            SshExecResult(-1, "", t.message ?: "Execution failed", elapsed)
        }
    }

    suspend fun recordBlockedAction(
        server: Server?,
        category: AuditCategory,
        actionTitle: String,
        command: String,
        reason: String,
        triggerSource: TriggerSource = TriggerSource.UI,
    ) {
        repository.recordAudit(
            timestampEpochMs = currentTimeEpochMs(),
            serverId = server?.id,
            serverNameSnapshot = server?.name ?: "Cluster",
            category = category,
            actionTitle = actionTitle,
            rawCommand = command,
            triggerSource = triggerSource,
            outcome = AuditOutcome.BLOCKED,
            exitCode = null,
            durationMs = 0L,
            outputSnippet = reason,
        )
    }

    suspend fun fetchContainers(server: Server): List<ContainerInfo> {
        if (server.isSimulated) return simulator.getContainers(server.id)
        val usePodman = !server.capabilities.hasDocker && server.capabilities.hasPodman
        val runtime = if (usePodman) ContainerRuntime.PODMAN else ContainerRuntime.DOCKER
        val res = sshClient.execute(server, latestServers, TelemetryProbes.dockerContainersScript(usePodman), 15_000L)
        return TelemetryParsers.parseContainers(res.stdout, runtime)
    }

    suspend fun performContainerAction(
        server: Server,
        container: ContainerInfo,
        action: ContainerAction,
        triggerSource: TriggerSource = TriggerSource.UI,
    ): SshExecResult {
        val bin = if (container.runtime == ContainerRuntime.PODMAN) "podman" else "docker"
        val cmd = when (action) {
            ContainerAction.START -> "$bin start ${container.name}"
            ContainerAction.STOP -> "$bin stop ${container.name}"
            ContainerAction.RESTART -> "$bin restart ${container.name}"
            ContainerAction.PAUSE -> "$bin pause ${container.name}"
            ContainerAction.UNPAUSE -> "$bin unpause ${container.name}"
            ContainerAction.KILL -> "$bin kill ${container.name}"
            ContainerAction.REMOVE -> "$bin rm -f ${container.name}"
            ContainerAction.PULL_RECREATE -> {
                val dir = container.composeWorkingDir
                val svc = container.composeService
                if (!dir.isNullOrBlank() && !svc.isNullOrBlank()) {
                    "cd '$dir' && docker compose pull '$svc' && docker compose up -d '$svc'"
                } else {
                    "$bin pull '${container.image}'"
                }
            }
        }
        val started = currentTimeEpochMs()
        val jobId = repository.startJob(server.id, server.name, "${action.label} ${container.name}", cmd, started)
        val result = if (server.isSimulated) {
            delay(450)
            simulator.mutateContainer(server.id, container.id, action)
        } else {
            sshClient.executeStreaming(server, latestServers, cmd) { chunk ->
                repository.appendJobOutput(jobId, chunk, currentTimeEpochMs() - started)
            }
        }
        repository.finishJob(
            jobId = jobId,
            phase = if (result.exitCode == 0) JobPhase.SUCCEEDED else JobPhase.FAILED,
            exitCode = result.exitCode,
            durationMs = result.durationMs,
            outputTail = result.stdout.ifBlank { result.stderr },
        )
        repository.recordAudit(
            timestampEpochMs = currentTimeEpochMs(),
            serverId = server.id,
            serverNameSnapshot = server.name,
            category = AuditCategory.CONTAINER,
            actionTitle = "Container ${action.label}: ${container.name}",
            rawCommand = cmd,
            triggerSource = triggerSource,
            outcome = if (result.exitCode == 0) AuditOutcome.SUCCESS else AuditOutcome.FAILED,
            exitCode = result.exitCode,
            durationMs = result.durationMs,
            outputSnippet = result.stdout.ifBlank { result.stderr },
        )
        pollServerOnce(server)
        return result
    }

    suspend fun performComposeStackAction(
        server: Server,
        projectName: String,
        workingDir: String?,
        verb: String,
    ): SshExecResult {
        val cmd = if (!workingDir.isNullOrBlank()) {
            "cd '$workingDir' && docker compose $verb"
        } else {
            "docker compose -p '$projectName' $verb"
        }
        val started = currentTimeEpochMs()
        val jobId = repository.startJob(server.id, server.name, "Compose $verb: $projectName", cmd, started)
        val result = if (server.isSimulated) {
            delay(650)
            simulator.mutateComposeStack(server.id, projectName, verb != "down" && verb != "stop")
        } else {
            sshClient.executeStreaming(server, latestServers, cmd, 120_000L) { chunk ->
                repository.appendJobOutput(jobId, chunk, currentTimeEpochMs() - started)
            }
        }
        repository.finishJob(
            jobId = jobId,
            phase = if (result.exitCode == 0) JobPhase.SUCCEEDED else JobPhase.FAILED,
            exitCode = result.exitCode,
            durationMs = result.durationMs,
            outputTail = result.stdout.ifBlank { result.stderr },
        )
        repository.recordAudit(
            timestampEpochMs = currentTimeEpochMs(),
            serverId = server.id,
            serverNameSnapshot = server.name,
            category = AuditCategory.CONTAINER,
            actionTitle = "Compose $verb: $projectName",
            rawCommand = cmd,
            triggerSource = TriggerSource.UI,
            outcome = if (result.exitCode == 0) AuditOutcome.SUCCESS else AuditOutcome.FAILED,
            exitCode = result.exitCode,
            durationMs = result.durationMs,
            outputSnippet = result.stdout.ifBlank { result.stderr },
        )
        pollServerOnce(server)
        return result
    }

    suspend fun fetchContainerLogs(server: Server, containerName: String, tail: Int = 200): String {
        if (server.isSimulated) return simulator.containerLogs(containerName)
        val res = sshClient.execute(server, latestServers, "docker logs --tail $tail '$containerName' 2>&1", 15_000L)
        return res.stdout.ifBlank { res.stderr }
    }

    suspend fun fetchSystemdAndCron(server: Server): Pair<List<SystemdUnit>, List<CronEntry>> {
        if (server.isSimulated) return simulator.getSystemdAndCron(server.id)
        val res = sshClient.execute(server, latestServers, TelemetryProbes.systemdUnitsScript(), 15_000L)
        return TelemetryParsers.parseSystemdAndCron(res.stdout)
    }

    suspend fun performSystemdAction(server: Server, unitName: String, verb: String): SshExecResult {
        val cmd = "sudo -n systemctl $verb '$unitName' || systemctl $verb '$unitName'"
        return if (server.isSimulated) {
            val res = simulator.mutateSystemdUnit(server.id, unitName, verb)
            repository.recordAudit(
                timestampEpochMs = currentTimeEpochMs(),
                serverId = server.id,
                serverNameSnapshot = server.name,
                category = AuditCategory.SERVICE,
                actionTitle = "Service $verb: $unitName",
                rawCommand = cmd,
                triggerSource = TriggerSource.UI,
                outcome = AuditOutcome.SUCCESS,
                exitCode = 0,
                durationMs = res.durationMs,
                outputSnippet = res.stdout,
            )
            res
        } else {
            executeAuditedAction(server, AuditCategory.SERVICE, "Service $verb: $unitName", cmd)
        }
    }

    suspend fun fetchProcesses(server: Server): List<ProcessInfo> {
        if (server.isSimulated) return simulator.getProcesses(server.id)
        val res = sshClient.execute(server, latestServers, TelemetryProbes.processesScript(), 10_000L)
        return TelemetryParsers.parseProcesses(res.stdout)
    }

    suspend fun sendProcessSignal(server: Server, process: ProcessInfo, signal: ProcessSignal): SshExecResult {
        val cmd = "kill -${signal.code} ${process.pid}"
        return if (server.isSimulated) {
            val res = simulator.sendProcessSignal(process.pid, signal)
            repository.recordAudit(
                timestampEpochMs = currentTimeEpochMs(),
                serverId = server.id,
                serverNameSnapshot = server.name,
                category = AuditCategory.PROCESS,
                actionTitle = "Signal ${signal.signalName} PID ${process.pid}",
                rawCommand = cmd,
                triggerSource = TriggerSource.UI,
                outcome = AuditOutcome.SUCCESS,
                exitCode = 0,
                durationMs = res.durationMs,
                outputSnippet = res.stdout,
            )
            res
        } else {
            executeAuditedAction(server, AuditCategory.PROCESS, "Signal ${signal.signalName} PID ${process.pid}", cmd)
        }
    }

    suspend fun fetchNetworkDetails(server: Server): Pair<List<NetworkInterfaceInfo>, List<NetworkPortInfo>> {
        if (server.isSimulated) return simulator.getNetworkDetails(server.id)
        val res = sshClient.execute(server, latestServers, TelemetryProbes.networkDetailScript(), 10_000L)
        return TelemetryParsers.parseNetworkDetails(res.stdout)
    }

    suspend fun listFiles(server: Server, remotePath: String): List<RemoteFileEntry> {
        if (server.isSimulated) return simulator.listFiles(remotePath)
        return sshClient.listFiles(server, latestServers, remotePath)
    }

    suspend fun readFileText(server: Server, remotePath: String): String {
        if (server.isSimulated) return simulator.readFile(remotePath)
        return sshClient.readFileText(server, latestServers, remotePath)
    }

    suspend fun saveFileWithBackup(server: Server, remotePath: String, newContent: String) {
        if (server.isSimulated) {
            simulator.writeFile(remotePath, newContent)
        } else {
            sshClient.writeFileTextWithBackup(server, latestServers, remotePath, newContent)
        }
        repository.recordAudit(
            timestampEpochMs = currentTimeEpochMs(),
            serverId = server.id,
            serverNameSnapshot = server.name,
            category = AuditCategory.CONFIG,
            actionTitle = "Saved SFTP File: $remotePath",
            rawCommand = "sftp put $remotePath (backup: $remotePath.bak)",
            triggerSource = TriggerSource.UI,
            outcome = AuditOutcome.SUCCESS,
            exitCode = 0,
            durationMs = 120L,
            outputSnippet = "Updated ${newContent.length} bytes with .bak snapshot.",
        )
    }

    suspend fun deployPublicKey(server: Server, publicKey: String): SshExecResult {
        if (server.isSimulated) {
            return executeAuditedAction(server, AuditCategory.CONFIG, "Deploy SSH Public Key", "ssh-copy-id -> ~/.ssh/authorized_keys")
        }
        return sshClient.deployPublicKey(server, latestServers, publicKey)
    }

    suspend fun sendWakeOnLan(server: Server, triggerSource: TriggerSource = TriggerSource.UI): Result<Unit> {
        if (server.macAddress.isBlank()) {
            return Result.failure(IllegalStateException("No MAC address configured for ${server.name}"))
        }
        return runCatching {
            val relayId = server.wolRelayServerId
            val relayServer = relayId?.let { id -> latestServers.firstOrNull { it.id == id } }
            if (relayServer != null && !relayServer.isSimulated) {
                val cmd = "wakeonlan ${server.macAddress} || etherwake ${server.macAddress} || python3 -c \"import socket; m=bytes.fromhex('${server.macAddress.replace(":", "").replace("-", "")}'); s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.setsockopt(socket.SOL_SOCKET,socket.SO_BROADCAST,1); s.sendto(b'\\xff'*6+m*16,('255.255.255.255',9))\""
                executeAuditedAction(relayServer, AuditCategory.POWER, "Relay WoL -> ${server.name}", cmd, triggerSource)
            } else {
                WakeOnLanSender.sendDirectMagicPacket(server.macAddress)
                repository.recordAudit(
                    timestampEpochMs = currentTimeEpochMs(),
                    serverId = server.id,
                    serverNameSnapshot = server.name,
                    category = AuditCategory.POWER,
                    actionTitle = "Wake-on-LAN Magic Packet",
                    rawCommand = "wol ${server.macAddress}",
                    triggerSource = triggerSource,
                    outcome = AuditOutcome.SUCCESS,
                    exitCode = 0,
                    durationMs = 25L,
                    outputSnippet = "Broadcast UDP magic packet to ${server.macAddress}",
                )
            }
        }
    }

    suspend fun toggleTunnel(tunnelId: String, server: Server, localPort: Int, remoteHost: String, remotePort: Int): Result<Boolean> {
        val isOpen = _activeTunnels.value.contains(tunnelId)
        return runCatching {
            if (isOpen) {
                if (!server.isSimulated) sshClient.closePortForward(tunnelId)
                _activeTunnels.update { it - tunnelId }
                false
            } else {
                if (!server.isSimulated) {
                    sshClient.openLocalPortForward(tunnelId, server, latestServers, localPort, remoteHost, remotePort)
                }
                _activeTunnels.update { it + tunnelId }
                repository.recordAudit(
                    timestampEpochMs = currentTimeEpochMs(),
                    serverId = server.id,
                    serverNameSnapshot = server.name,
                    category = AuditCategory.SSH,
                    actionTitle = "Opened SSH Tunnel :$localPort -> $remoteHost:$remotePort",
                    rawCommand = "ssh -L $localPort:$remoteHost:$remotePort ${server.username}@${server.primaryHost}",
                    triggerSource = TriggerSource.UI,
                    outcome = AuditOutcome.SUCCESS,
                    exitCode = 0,
                    durationMs = 45L,
                )
                true
            }
        }
    }

    suspend fun openInteractiveShell(server: Server, cols: Int = 80, rows: Int = 24): SshShellSession {
        repository.recordAudit(
            timestampEpochMs = currentTimeEpochMs(),
            serverId = server.id,
            serverNameSnapshot = server.name,
            category = AuditCategory.SSH,
            actionTitle = "Opened Interactive SSH Session",
            rawCommand = "ssh ${server.username}@${server.primaryHost} -p ${server.port}",
            triggerSource = TriggerSource.TERMINAL,
            outcome = AuditOutcome.SUCCESS,
            exitCode = 0,
            durationMs = 0L,
        )
        return if (server.isSimulated) {
            simulator.openDemoShell(server)
        } else {
            sshClient.openShell(server, latestServers, cols, rows)
        }
    }

    suspend fun rebootOrShutdown(server: Server, reboot: Boolean): SshExecResult {
        val verb = if (reboot) "reboot" else "shutdown -h now"
        val cmd = "sudo -n $verb || $verb"
        return executeAuditedAction(
            server = server,
            category = AuditCategory.POWER,
            actionTitle = if (reboot) "System Reboot" else "System Shutdown",
            command = cmd,
        )
    }
}
