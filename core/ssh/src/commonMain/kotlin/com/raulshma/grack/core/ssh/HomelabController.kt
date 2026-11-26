package com.raulshma.grack.core.ssh

import co.touchlab.kermit.Logger
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.AlertMetric
import com.raulshma.grack.core.model.AlertRule
import com.raulshma.grack.core.model.AppAlert
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.ContainerAction
import com.raulshma.grack.core.model.ContainerImageInfo
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.ContainerRuntime
import com.raulshma.grack.core.model.CronEntry
import com.raulshma.grack.core.model.HostKeyChallenge
import com.raulshma.grack.core.model.JobPhase
import com.raulshma.grack.core.model.MetricSnapshot
import com.raulshma.grack.core.model.NetworkInterfaceInfo
import com.raulshma.grack.core.model.NetworkPortInfo
import com.raulshma.grack.core.model.ProcessInfo
import com.raulshma.grack.core.model.ProcessSignal
import com.raulshma.grack.core.model.RemoteFileEntry
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.ServerHealth
import com.raulshma.grack.core.model.ServerRuntimeState
import com.raulshma.grack.core.model.SudoAuth
import com.raulshma.grack.core.model.SystemdUnit
import com.raulshma.grack.core.model.TriggerSource
import com.raulshma.grack.core.model.currentTimeEpochMs
import com.raulshma.grack.core.model.sudoSecretVaultId
import com.raulshma.grack.core.security.SecureVault
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/** The engine binary to invoke for this runtime (podman / docker); used by UI and audit previews too. */
val ContainerRuntime.binaryName: String
    get() = when (this) {
        ContainerRuntime.PODMAN -> "podman"
        else -> "docker"
    }

class HomelabController(
    private val repository: GlassrackRepository,
    private val sshClient: SshSessionClient,
    backgroundDispatcher: CoroutineContext = Dispatchers.Default,
    /**
     * Pause gate for the continuous polling loops. The app closes it while backgrounded or
     * (per preference) on metered networks; one-shot calls such as [pollServerOnce] from the
     * WorkManager worker intentionally bypass it.
     */
    val pollingGate: PollingGate = PollingGate(),
    /** Records successful container fetches so widgets never need SSH to render. */
    private val containerStateCache: ContainerStateCache? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)
    private val sampleCache = TelemetrySampleCache()

    private val controllerJob: Job = scope.coroutineContext[Job]!!

    private val _runtimeStates = MutableStateFlow<Map<String, ServerRuntimeState>>(emptyMap())
    val runtimeStates: StateFlow<Map<String, ServerRuntimeState>> = _runtimeStates.asStateFlow()

    private val _activeAlerts = MutableStateFlow<List<AppAlert>>(emptyList())
    val activeAlerts: StateFlow<List<AppAlert>> = _activeAlerts.asStateFlow()

    private val _activeTunnels = MutableStateFlow<Set<String>>(emptySet())
    val activeTunnels: StateFlow<Set<String>> = _activeTunnels.asStateFlow()

    private val pollingJobs = mutableMapOf<String, Job>()
    private var latestServers: List<Server> = emptyList()
    private var latestRules: List<AlertRule> = emptyList()

    /** Consecutive failed polls per server; drives SERVER_OFFLINE alerts (item: offline alerts). */
    private val consecutiveFailures = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** serverId -> epoch ms until which a successful `sudo -n true` probe stays trusted. */
    private val passwordlessSudoUntilMs = mutableMapOf<String, Long>()
    private val sudoProbeMutex = Mutex()

    init {
        scope.launch {
            repository.ensureSeeded()
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
        scope.launch {
            while (isActive) {
                delay(IDLE_PRUNE_INTERVAL_MS)
                runCatching { sshClient.disconnectIdle(IDLE_PRUNE_MAX_IDLE_MS) }
                    .onFailure { Logger.w(it) { "Idle session prune failed" } }
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
                        // Polling hygiene: suspend while a pause reason (backgrounded,
                        // metered) is active instead of polling every few seconds forever.
                        pollingGate.awaitOpen()
                        val currentServer = latestServers.firstOrNull { it.id == server.id } ?: break
                        pollServerOnce(currentServer)
                        delay(currentServer.pollIntervalSec.coerceAtLeast(2) * 1000L)
                    }
                }
            }
        }
    }

    suspend fun pollServerOnce(server: Server) {
        _runtimeStates.update { map ->
            val cur = map[server.id] ?: ServerRuntimeState(server)
            if (cur.health == ServerHealth.UNKNOWN) {
                map + (server.id to cur.copy(health = ServerHealth.CONNECTING))
            } else map
        }

        try {
            var activeServer = server
            val caps = activeServer.capabilities
            if (caps.distro.isBlank() || caps.probeVersion < TelemetryParsers.CAPABILITIES_PROBE_VERSION) {
                val capResult = sshClient.execute(activeServer, latestServers, TelemetryProbes.detectCapabilitiesScript(), 10_000L)
                if (capResult.exitCode == 0 && capResult.stdout.isNotBlank()) {
                    val freshCaps = TelemetryParsers.parseCapabilities(capResult.stdout)
                    activeServer = activeServer.copy(capabilities = freshCaps)
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
            consecutiveFailures.update { it - server.id }
            evaluateAlerts(activeServer, snapshot)
        } catch (challengeEx: HostKeyChallengeException) {
            recordPollFailure(server, challengeEx.message ?: "Host key rejected")
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
            recordPollFailure(server, t.message ?: "Connection failed")
            _runtimeStates.update { map ->
                val cur = map[server.id] ?: ServerRuntimeState(server)
                map + (server.id to cur.copy(
                    health = ServerHealth.OFFLINE,
                    lastError = t.message ?: "Connection failed",
                ))
            }
        }
    }

    /** Counts a failed poll and raises SERVER_OFFLINE rules once failures reach the threshold. */
    private fun recordPollFailure(server: Server, error: String) {
        val failures = (consecutiveFailures.value[server.id] ?: 0) + 1
        consecutiveFailures.update { it + (server.id to failures) }
        if (failures < OFFLINE_ALERT_FAILURE_THRESHOLD) return
        val offlineAlerts = latestRules
            .filter {
                it.enabled && it.metric == AlertMetric.SERVER_OFFLINE &&
                    (it.serverId == null || it.serverId == server.id)
            }
            .map { rule ->
                AppAlert(
                    id = "${rule.id}-${server.id}",
                    ruleId = rule.id,
                    serverId = server.id,
                    serverName = server.name,
                    message = "${rule.name}: unreachable ($failures consecutive failed polls, last error: $error)",
                    timestampEpochMs = currentTimeEpochMs(),
                    severity = ServerHealth.OFFLINE,
                )
            }
        if (offlineAlerts.isEmpty()) return
        _activeAlerts.update { existing ->
            val withoutServer = existing.filterNot { it.serverId == server.id }
            (withoutServer + offlineAlerts).takeLast(20)
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

    suspend fun testServerConnection(
        server: Server,
        trustFirst: Boolean,
        transientPassword: String? = null,
    ): Result<HostKeyChallenge?> {
        return sshClient.testConnection(server, latestServers, trustFingerprintOnFirstConnect = trustFirst, transientPassword = transientPassword)
    }

    suspend fun acceptHostKeyChallenge(challenge: HostKeyChallenge) {
        repository.updateHostFingerprint(challenge.serverId, challenge.fingerprintSha256)
        _runtimeStates.update { map ->
            val cur = map[challenge.serverId] ?: return@update map
            map + (challenge.serverId to cur.copy(pendingHostKeyChallenge = null, lastError = null))
        }
        latestServers.firstOrNull { it.id == challenge.serverId }?.let { pollServerOnce(it) }
    }

    /**
     * Runs [command] with honest job/audit bookkeeping (SUCCESS / FAILED / TIMEOUT / CANCELLED).
     *
     * [timeoutMs] is nullable: `null` means untimed and only makes sense with
     * [streaming] — long-lived streams such as `logs -f` end when the caller cancels,
     * which still records a CANCELLED audit and closes the job — never left RUNNING.
     * Non-streaming calls with a null timeout fall back to [DEFAULT_ACTION_TIMEOUT_MS].
     *
     * [onStreamChunk], when set (streaming only), receives every streamed chunk in addition
     * to the job's live output — the follow-logs UI renders from it.
     */
    suspend fun executeAuditedAction(
        server: Server,
        category: AuditCategory,
        actionTitle: String,
        command: String,
        triggerSource: TriggerSource = TriggerSource.UI,
        streaming: Boolean = false,
        timeoutMs: Long? = DEFAULT_ACTION_TIMEOUT_MS,
        stdinText: String? = null,
        onStreamChunk: (suspend (String) -> Unit)? = null,
    ): SshExecResult {
        val started = currentTimeEpochMs()
        val jobId = repository.startJob(server.id, server.name, actionTitle, command, started)
        return try {
            val result = if (streaming) {
                sshClient.executeStreaming(server, latestServers, command, timeoutMs, stdinText) { chunk ->
                    repository.appendJobOutput(jobId, chunk, currentTimeEpochMs() - started)
                    onStreamChunk?.invoke(chunk)
                }
            } else {
                sshClient.execute(server, latestServers, command, timeoutMs ?: DEFAULT_ACTION_TIMEOUT_MS, stdinText)
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
        } catch (timeoutEx: TimeoutCancellationException) {
            recordUnfinishedAction(server, category, actionTitle, command, triggerSource, jobId, started, AuditOutcome.TIMEOUT, timeoutEx.message)
            SshExecResult(-1, "", "Timed out after ${timeoutMs}ms: ${timeoutEx.message ?: actionTitle}", currentTimeEpochMs() - started)
        } catch (cancelled: CancellationException) {
            recordUnfinishedAction(server, category, actionTitle, command, triggerSource, jobId, started, AuditOutcome.CANCELLED, cancelled.message)
            throw cancelled
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

    private suspend fun recordUnfinishedAction(
        server: Server,
        category: AuditCategory,
        actionTitle: String,
        command: String,
        triggerSource: TriggerSource,
        jobId: String,
        startedEpochMs: Long,
        outcome: AuditOutcome,
        reason: String?,
    ) {
        // NonCancellable: on a cancelled action the calling scope is already cancelled, and
        // without this the audit/job bookkeeping would itself be aborted by cancellation.
        withContext(NonCancellable) {
            val elapsed = currentTimeEpochMs() - startedEpochMs
            val phase = when (outcome) {
                AuditOutcome.TIMEOUT -> JobPhase.FAILED
                AuditOutcome.CANCELLED -> JobPhase.CANCELLED
                else -> JobPhase.FAILED
            }
            repository.finishJob(jobId, phase, -1, elapsed, reason)
            repository.recordAudit(
                timestampEpochMs = currentTimeEpochMs(),
                serverId = server.id,
                serverNameSnapshot = server.name,
                category = category,
                actionTitle = actionTitle,
                rawCommand = command,
                triggerSource = triggerSource,
                outcome = outcome,
                exitCode = -1,
                durationMs = elapsed,
                outputSnippet = reason.orEmpty(),
            )
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

    /**
     * Runs a command that may need root, resolving the privilege mode fresh on every call
     * (Phase 2, request #1). The caller supplies the intent: [command], [category],
     * [actionTitle] — this function never rewrites or filters verbs.
     *
     * Resolution order:
     * 1. Root login (`capabilities.isRoot`): run [command] as-is.
     * 2. Passwordless sudo (`sudo -n true` succeeded, cached [SUDO_PROBE_TTL_MS] per server):
     *    `sudo -n bash -c '<quoted>'`.
     * 3. A sudo credential is available ([SudoAuth.SEPARATE_PASSWORD] reads vault id
     *    `srv_sudo_<serverId>`, [SudoAuth.LOGIN_PASSWORD] reads the login credential vault id
     *    at use time): `sudo -S -p '' bash -c '<quoted>'` with the password delivered on
     *    stdin, then EOF — sudo would otherwise wait up to 5 minutes for more input.
     * 4. Otherwise today's fallback: `sudo -n bash -c '<quoted>' || bash -c '<quoted>'`.
     *
     * SECURITY: the sudo password exists only in [com.raulshma.grack.core.ssh.SshSessionClient.execute]'s
     * `stdinText` (sent then EOF, never a PTY). It is never part of the command string, the
     * job preview, the audit entry, the streamed output or any log line; `sudo -p ''`
     * suppresses the prompt so it cannot echo back either.
     */
    suspend fun executePrivileged(
        server: Server,
        command: String,
        category: AuditCategory,
        actionTitle: String,
        vault: SecureVault,
        triggerSource: TriggerSource = TriggerSource.UI,
        timeoutMs: Long = DEFAULT_ACTION_TIMEOUT_MS,
        streaming: Boolean = false,
    ): SshExecResult {
        return when (resolvePrivilegeMode(server, vault)) {
            PrivilegeMode.ROOT ->
                executeAuditedAction(server, category, actionTitle, command, triggerSource, streaming, timeoutMs)
            PrivilegeMode.PASSWORDLESS_SUDO ->
                executeAuditedAction(
                    server, category, actionTitle, "sudo -n bash -c ${shellQuote(command)}", triggerSource, streaming, timeoutMs,
                )
            PrivilegeMode.SUDO_WITH_CREDENTIAL -> {
                val sudoPassword = sudoPasswordFor(server, vault)
                if (sudoPassword.isNullOrEmpty()) {
                    // The credential vanished between resolution and use; fail safe to the fallback.
                    executeAuditedAction(
                        server, category, actionTitle, unprivilegedFallbackCommand(command), triggerSource, streaming, timeoutMs,
                    )
                } else {
                    executeAuditedAction(
                        server,
                        category,
                        actionTitle,
                        "sudo -S -p '' bash -c ${shellQuote(command)}",
                        triggerSource,
                        streaming,
                        timeoutMs,
                        stdinText = "$sudoPassword\n",
                    )
                }
            }
            PrivilegeMode.UNPRIVILEGED_FALLBACK ->
                executeAuditedAction(
                    server, category, actionTitle, unprivilegedFallbackCommand(command), triggerSource, streaming, timeoutMs,
                )
        }
    }

    /** Resolution order of [executePrivileged]; see its KDoc. */
    private suspend fun resolvePrivilegeMode(server: Server, vault: SecureVault): PrivilegeMode {
        if (server.capabilities.isRoot) return PrivilegeMode.ROOT
        if (hasFreshPasswordlessSudo(server)) return PrivilegeMode.PASSWORDLESS_SUDO
        if (!sudoPasswordFor(server, vault).isNullOrEmpty()) return PrivilegeMode.SUDO_WITH_CREDENTIAL
        return PrivilegeMode.UNPRIVILEGED_FALLBACK
    }

    /** Probes `sudo -n true` (no password, no prompt) and caches success for [SUDO_PROBE_TTL_MS]. */
    private suspend fun hasFreshPasswordlessSudo(server: Server): Boolean {
        val now = currentTimeEpochMs()
        sudoProbeMutex.withLock {
            val until = passwordlessSudoUntilMs[server.id]
            if (until != null && now < until) return true
        }
        val probe = runCatching {
            sshClient.execute(server, latestServers, SUDO_PROBE_COMMAND, SUDO_PROBE_TIMEOUT_MS)
        }.getOrNull()
        val passwordless = probe != null && probe.exitCode == 0
        sudoProbeMutex.withLock {
            if (passwordless) {
                passwordlessSudoUntilMs[server.id] = currentTimeEpochMs() + SUDO_PROBE_TTL_MS
            } else {
                passwordlessSudoUntilMs.remove(server.id)
            }
        }
        return passwordless
    }

    /**
     * Reads the sudo credential for [server] from the vault, or null when none is usable.
     * The returned string must never be embedded in a command, preview, audit entry or log.
     */
    private suspend fun sudoPasswordFor(server: Server, vault: SecureVault): String? {
        val vaultId = when (server.sudoAuth) {
            SudoAuth.SEPARATE_PASSWORD -> sudoSecretVaultId(server.id)
            SudoAuth.LOGIN_PASSWORD -> server.credentialVaultId
            SudoAuth.NONE -> null
        } ?: return null
        return runCatching { vault.getSecret(vaultId) }
            .onFailure { Logger.w(it) { "Sudo credential lookup failed for ${server.name}" } }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun unprivilegedFallbackCommand(command: String): String =
        "sudo -n bash -c ${shellQuote(command)} || bash -c ${shellQuote(command)}"

    suspend fun fetchContainers(server: Server): List<ContainerInfo> {
        val usePodman = !server.capabilities.hasDocker && server.capabilities.hasPodman
        val runtime = if (usePodman) ContainerRuntime.PODMAN else ContainerRuntime.DOCKER
        val res = sshClient.execute(server, latestServers, TelemetryProbes.dockerContainersScript(usePodman), 15_000L)
        val containers = TelemetryParsers.parseContainers(res.stdout, runtime)
        // Successful fetches refresh the widget cache; a failed run must not wipe the
        // last-known state widgets render from.
        if (res.exitCode == 0) {
            runCatching { containerStateCache?.recordContainers(server.id, containers, currentTimeEpochMs()) }
                .onFailure { Logger.w(it) { "Widget container cache update failed" } }
        }
        return containers
    }

    /** The container engine binary to drive for [server]: podman only when docker is absent. */
    fun containerRuntimeFor(server: Server): ContainerRuntime =
        if (!server.capabilities.hasDocker && server.capabilities.hasPodman) {
            ContainerRuntime.PODMAN
        } else {
            ContainerRuntime.DOCKER
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
                    "cd '$dir' && $bin compose pull '$svc' && $bin compose up -d '$svc'"
                } else {
                    "$bin pull '${container.image}'"
                }
            }
        }
        val result = executeAuditedAction(
            server = server,
            category = AuditCategory.CONTAINER,
            actionTitle = "Container ${action.label}: ${container.name}",
            command = cmd,
            triggerSource = triggerSource,
            streaming = true,
            timeoutMs = CONTAINER_ACTION_TIMEOUT_MS,
        )
        pollServerOnce(server)
        return result
    }

    /**
     * The single compose-stack execution path: one place that picks the runtime's compose
     * binary, honours the stack's working directory and `config_files` label, times updates
     * generously and records the audit entry. Every caller (UI verbs, Phase 3B stack Update,
     * Phase 4 AUTO updates) goes through this.
     *
     * [verb] is a plain compose verb (`ps`, `down`, `pull`, ...) or the pseudo-verb
     * `update`, which expands to `pull && compose up -d` (recreate updated services).
     * [configFiles], when set, comes from the stack's `config_files` compose label and is
     * expanded into `-f <file>` arguments on both halves of the update chain.
     * [timeoutMs] overrides the per-verb default (15 min for pull/up/update, 2 min otherwise).
     */
    suspend fun performComposeStackAction(
        server: Server,
        projectName: String,
        workingDir: String?,
        verb: String,
        triggerSource: TriggerSource = TriggerSource.UI,
        timeoutMs: Long? = null,
        configFiles: String? = null,
    ): SshExecResult {
        // Compose binary follows the node's detected runtime instead of a hardcoded docker.
        val composeBin = containerRuntimeFor(server).binaryName
        val fileArgs = configFiles
            ?.split(',')
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { "-f ${shellQuote(it)}" }
        val cdPrefix = if (!workingDir.isNullOrBlank()) "cd '$workingDir' && " else ""
        val composeBase = buildString {
            append(composeBin)
            append(" compose")
            if (workingDir.isNullOrBlank()) append(" -p '").append(projectName).append("'")
            if (fileArgs.isNotEmpty()) append(" ").append(fileArgs)
        }
        val isUpdateVerb = verb.trim().equals("update", ignoreCase = true)
        val cmd = if (isUpdateVerb) {
            "$cdPrefix${composeBase} pull && ${composeBase} up -d"
        } else {
            "$cdPrefix${composeBase} $verb"
        }
        val isStackUpdate = isUpdateVerb ||
            verb.trim().startsWith("pull") ||
            verb.trim().startsWith("up")
        val result = executeAuditedAction(
            server = server,
            category = AuditCategory.CONTAINER,
            actionTitle = "Compose $verb: $projectName",
            command = cmd,
            triggerSource = triggerSource,
            streaming = true,
            timeoutMs = timeoutMs ?: if (isStackUpdate) COMPOSE_UPDATE_TIMEOUT_MS else COMPOSE_ACTION_TIMEOUT_MS,
        )
        pollServerOnce(server)
        return result
    }

    /** Fetches container logs via the container's own runtime binary, not a hardcoded docker. */
    suspend fun fetchContainerLogs(server: Server, container: ContainerInfo, tail: Int = 200): String {
        val cmd = "${container.runtime.binaryName} logs --tail $tail '${container.name}' 2>&1"
        val res = sshClient.execute(server, latestServers, cmd, 15_000L)
        return res.stdout.ifBlank { res.stderr }
    }

    /**
     * Follows a container's live logs on `<runtime> logs -f --tail $tail --timestamps`,
     * streaming every chunk to [onChunk]. The stream is untimed (a follow only ends when
     * the caller cancels) but routed through [executeAuditedAction], so cancelling it
     * records a CANCELLED audit and closes its job — no RUNNING job and no leaked channel.
     */
    suspend fun followContainerLogs(
        server: Server,
        container: ContainerInfo,
        tail: Int = FOLLOW_LOGS_TAIL,
        onChunk: suspend (String) -> Unit,
    ): SshExecResult {
        val cmd = "${container.runtime.binaryName} logs -f --tail $tail --timestamps '${container.name}' 2>&1"
        return executeAuditedAction(
            server = server,
            category = AuditCategory.CONTAINER,
            actionTitle = "Follow logs: ${container.name}",
            command = cmd,
            streaming = true,
            timeoutMs = null, // untimed: `logs -f` runs until the caller cancels it
            onStreamChunk = onChunk,
        )
    }

    /** Lists the node's images via the runtime detected for [server] (docker unless podman-only). */
    suspend fun fetchImages(server: Server): List<ContainerImageInfo> {
        val usePodman = !server.capabilities.hasDocker && server.capabilities.hasPodman
        val res = sshClient.execute(server, latestServers, TelemetryProbes.dockerImagesScript(usePodman), 15_000L)
        return TelemetryParsers.parseImages(res.stdout)
    }

    /** Removes a single image (`<runtime> rmi <id>`); destructive and audited. */
    suspend fun performImageRemove(
        server: Server,
        image: ContainerImageInfo,
        triggerSource: TriggerSource = TriggerSource.UI,
    ): SshExecResult {
        val bin = containerRuntimeFor(server).binaryName
        return executeAuditedAction(
            server = server,
            category = AuditCategory.CONTAINER,
            actionTitle = "Remove image: ${image.repoTag}",
            command = "$bin rmi ${shellQuote(image.id)}",
            triggerSource = triggerSource,
        )
    }

    /** Prunes dangling (untagged) images; destructive and audited. */
    suspend fun performDanglingImagePrune(
        server: Server,
        triggerSource: TriggerSource = TriggerSource.UI,
    ): SshExecResult {
        val bin = containerRuntimeFor(server).binaryName
        return executeAuditedAction(
            server = server,
            category = AuditCategory.CONTAINER,
            actionTitle = "Prune dangling images",
            command = "$bin image prune -f",
            triggerSource = triggerSource,
        )
    }

    suspend fun fetchSystemdAndCron(server: Server): Pair<List<SystemdUnit>, List<CronEntry>> {
        val res = sshClient.execute(server, latestServers, TelemetryProbes.systemdUnitsScript(), 15_000L)
        return TelemetryParsers.parseSystemdAndCron(res.stdout)
    }

    /**
     * systemd unit verb (start/stop/restart/...), routed through [executePrivileged] so it
     * honours root logins, passwordless sudo and stored sudo credentials. The verb comes
     * from the caller; the destructive ones are gated upstream by [com.raulshma.grack.core.model.requiresConfirmation].
     */
    suspend fun performSystemdAction(
        server: Server,
        unitName: String,
        verb: String,
        vault: SecureVault,
    ): SshExecResult {
        return executePrivileged(
            server = server,
            command = "systemctl $verb ${shellQuote(unitName)}",
            category = AuditCategory.SERVICE,
            actionTitle = "Service $verb: $unitName",
            vault = vault,
        )
    }

    suspend fun fetchProcesses(server: Server): List<ProcessInfo> {
        val res = sshClient.execute(server, latestServers, TelemetryProbes.processesScript(), 10_000L)
        return TelemetryParsers.parseProcesses(res.stdout)
    }

    /** Signals a process (pid parsed as Int from the remote ps output); foreign-PID signals need root. */
    suspend fun sendProcessSignal(
        server: Server,
        process: ProcessInfo,
        signal: ProcessSignal,
        vault: SecureVault,
    ): SshExecResult {
        return executePrivileged(
            server = server,
            command = "kill -${signal.code} ${process.pid}",
            category = AuditCategory.PROCESS,
            actionTitle = "Signal ${signal.signalName} PID ${process.pid}",
            vault = vault,
        )
    }

    suspend fun fetchNetworkDetails(server: Server): Pair<List<NetworkInterfaceInfo>, List<NetworkPortInfo>> {
        val res = sshClient.execute(server, latestServers, TelemetryProbes.networkDetailScript(), 10_000L)
        return TelemetryParsers.parseNetworkDetails(res.stdout)
    }

    suspend fun listFiles(server: Server, remotePath: String): List<RemoteFileEntry> {
        return sshClient.listFiles(server, latestServers, remotePath)
    }

    suspend fun readFileText(server: Server, remotePath: String): String {
        return sshClient.readFileText(server, latestServers, remotePath)
    }

    /**
     * Saves [newContent] to [remotePath] with a `.bak` snapshot of the previous content.
     * Enforces Safe Read-Only Mode, records the real duration and outcome (a failed or
     * timed-out save is audited as such), and rethrows so callers can surface the failure.
     * The transport aborts without writing when the `.bak` snapshot itself fails.
     *
     * When the SFTP write is refused with permission denied and [vault] is supplied, the
     * save retries through [privilegedSave] (temp upload + `cp -p` backup + `install` that
     * preserves mode/owner/group). Without a vault the permission failure is recorded and
     * rethrown unchanged.
     */
    suspend fun saveFileWithBackup(server: Server, remotePath: String, newContent: String, vault: SecureVault? = null) {
        if (repository.preferences.first().safeReadOnlyMode) {
            val reason = "Blocked: Safe Read-Only Mode is active"
            repository.recordAudit(
                timestampEpochMs = currentTimeEpochMs(),
                serverId = server.id,
                serverNameSnapshot = server.name,
                category = AuditCategory.CONFIG,
                actionTitle = "Save SFTP File: $remotePath",
                rawCommand = "sftp put $remotePath (backup: $remotePath.bak)",
                triggerSource = TriggerSource.UI,
                outcome = AuditOutcome.BLOCKED,
                exitCode = null,
                durationMs = 0L,
                outputSnippet = reason,
            )
            throw IllegalStateException(reason)
        }
        val started = currentTimeEpochMs()
        try {
            val backupCreated = sshClient.writeFileTextWithBackup(server, latestServers, remotePath, newContent)
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
                durationMs = currentTimeEpochMs() - started,
                outputSnippet = if (backupCreated) {
                    "Updated ${newContent.length} bytes with .bak snapshot."
                } else {
                    "Updated ${newContent.length} bytes (new file, no prior content to back up)."
                },
            )
        } catch (denied: FilePermissionDeniedException) {
            if (vault == null) {
                recordFileSaveFailure(server, remotePath, started, AuditOutcome.FAILED, denied.message)
                throw denied
            }
            privilegedSave(server, remotePath, newContent, vault, started, denied)
            return
        } catch (timeoutEx: TimeoutCancellationException) {
            recordFileSaveFailure(server, remotePath, started, AuditOutcome.TIMEOUT, timeoutEx.message)
            throw timeoutEx
        } catch (cancelled: CancellationException) {
            recordFileSaveFailure(server, remotePath, started, AuditOutcome.CANCELLED, cancelled.message)
            throw cancelled
        } catch (t: Throwable) {
            recordFileSaveFailure(server, remotePath, started, AuditOutcome.FAILED, t.message)
            throw t
        }
    }

    /**
     * Permission-denied save retry: upload the payload to a `/tmp` staging path, then run
     * `cp -p <path> <path>.bak && install -m <mode> -o <owner> -g <group> <tmp> <path> &&
     * rm -f <tmp>` through [executePrivileged]. `cp -p` preserves the original mode, owner
     * and group on the backup, and the `&&` chain keeps the Phase 1B `.bak` semantics — a
     * failed backup aborts before anything is replaced. The audit ledger entry is the one
     * [executePrivileged] writes (real command, real outcome); only the pre-command abort
     * below records separately.
     */
    private suspend fun privilegedSave(
        server: Server,
        remotePath: String,
        newContent: String,
        vault: SecureVault,
        startedEpochMs: Long,
        cause: FilePermissionDeniedException,
    ) {
        val tempPath = "/tmp/grack-save-${randomHex(8)}.tmp"
        val stat = runCatching {
            sshClient.execute(
                server,
                latestServers,
                "stat -c '%a %U %G' -- ${shellQuote(remotePath)}",
                FILE_STAT_TIMEOUT_MS,
            )
        }.getOrNull()
        val attrs = stat?.stdout?.trim()
            ?.split(WHITESPACE_REGEX)
            ?.takeIf { it.size == 3 && it[0].matches(FILE_MODE_REGEX) }
        if (attrs == null) {
            val reason = "Privileged save aborted: could not stat $remotePath " +
                "(${stat?.stderr?.ifBlank { null } ?: cause.message})"
            recordFileSaveFailure(server, remotePath, startedEpochMs, AuditOutcome.FAILED, reason)
            throw IllegalStateException(reason, cause)
        }
        val (mode, owner, group) = attrs
        sshClient.uploadFileText(server, latestServers, tempPath, newContent)
        val installCommand = "cp -p ${shellQuote(remotePath)} ${shellQuote("$remotePath.bak")} && " +
            "install -m ${shellQuote(mode)} -o ${shellQuote(owner)} -g ${shellQuote(group)} " +
            "${shellQuote(tempPath)} ${shellQuote(remotePath)} && rm -f ${shellQuote(tempPath)}"
        val result = executePrivileged(
            server = server,
            command = installCommand,
            category = AuditCategory.CONFIG,
            actionTitle = "Saved SFTP File: $remotePath",
            vault = vault,
        )
        if (result.exitCode != 0) {
            runCatching { sshClient.execute(server, latestServers, "rm -f ${shellQuote(tempPath)}", FILE_STAT_TIMEOUT_MS) }
            throw IllegalStateException(
                "Privileged install failed for $remotePath (exit ${result.exitCode}): " +
                    result.stderr.ifBlank { result.stdout }.ifBlank { "no output" },
            )
        }
    }

    private suspend fun recordFileSaveFailure(
        server: Server,
        remotePath: String,
        startedEpochMs: Long,
        outcome: AuditOutcome,
        reason: String?,
    ) {
        // NonCancellable: the calling scope may already be cancelled; the audit entry must
        // still be written (same reasoning as recordUnfinishedAction).
        withContext(NonCancellable) {
            repository.recordAudit(
                timestampEpochMs = currentTimeEpochMs(),
                serverId = server.id,
                serverNameSnapshot = server.name,
                category = AuditCategory.CONFIG,
                actionTitle = "Save SFTP File: $remotePath",
                rawCommand = "sftp put $remotePath (backup: $remotePath.bak)",
                triggerSource = TriggerSource.UI,
                outcome = outcome,
                exitCode = -1,
                durationMs = currentTimeEpochMs() - startedEpochMs,
                outputSnippet = reason.orEmpty(),
            )
        }
    }

    suspend fun deployPublicKey(server: Server, publicKey: String): SshExecResult {
        return sshClient.deployPublicKey(server, latestServers, publicKey)
    }

    suspend fun sendWakeOnLan(server: Server, triggerSource: TriggerSource = TriggerSource.UI): Result<Unit> {
        if (server.macAddress.isBlank()) {
            return Result.failure(IllegalStateException("No MAC address configured for ${server.name}"))
        }
        return runCatching {
            val relayId = server.wolRelayServerId
            val relayServer = relayId?.let { id -> latestServers.firstOrNull { it.id == id } }
            if (relayServer != null) {
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
                sshClient.closePortForward(tunnelId)
                _activeTunnels.update { it - tunnelId }
                false
            } else {
                sshClient.openLocalPortForward(tunnelId, server, latestServers, localPort, remoteHost, remotePort)
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
        return sshClient.openShell(server, latestServers, cols, rows)
    }

    /** Reboot / shutdown, routed through [executePrivileged] so stored sudo credentials work. */
    suspend fun rebootOrShutdown(server: Server, reboot: Boolean, vault: SecureVault): SshExecResult {
        val command = if (reboot) "reboot" else "shutdown -h now"
        return executePrivileged(
            server = server,
            command = command,
            category = AuditCategory.POWER,
            actionTitle = if (reboot) "System Reboot" else "System Shutdown",
            vault = vault,
        )
    }

    /**
     * Last [tail] journal lines for a systemd unit. journalctl often requires root or the
     * adm group, so the read goes through [executePrivileged] like every other privileged verb.
     */
    suspend fun fetchJournalLogs(server: Server, unitName: String, vault: SecureVault, tail: Int = 80): String {
        val res = executePrivileged(
            server = server,
            command = "journalctl -u ${shellQuote(unitName)} -n $tail --no-pager",
            category = AuditCategory.SERVICE,
            actionTitle = "Inspect journalctl $unitName",
            vault = vault,
        )
        return res.stdout.ifBlank { res.stderr }
    }

    /** Cancels the controller's background loops (pollers, idle pruning) at teardown. */
    fun stop() {
        controllerJob.cancel()
    }

    private fun randomHex(byteCount: Int): String =
        Random.nextBytes(byteCount).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    companion object {
        /** Default budget for audited actions. */
        const val DEFAULT_ACTION_TIMEOUT_MS: Long = 120_000L

        /** Container verbs are streamed and can take a couple of minutes on slow nodes. */
        const val CONTAINER_ACTION_TIMEOUT_MS: Long = 120_000L

        /** Regular compose verbs (ps, stop, restart, down...). */
        const val COMPOSE_ACTION_TIMEOUT_MS: Long = 120_000L

        /** Compose pull/up download images; give them 15 minutes. */
        const val COMPOSE_UPDATE_TIMEOUT_MS: Long = 15 * 60_000L

        /** Tail length of the `logs -f` follow stream. */
        const val FOLLOW_LOGS_TAIL: Int = 200

        /** How often idle pooled sessions are pruned. */
        const val IDLE_PRUNE_INTERVAL_MS: Long = 15 * 60_000L

        /** A pooled session idle longer than this is disconnected (unless a tunnel or shell keeps it alive). */
        const val IDLE_PRUNE_MAX_IDLE_MS: Long = 15 * 60_000L

        /** SERVER_OFFLINE rules fire after this many consecutive failed polls. */
        const val OFFLINE_ALERT_FAILURE_THRESHOLD: Int = 3

        /** Passwordless-sudo probe command: no password is read, no prompt is printed. */
        const val SUDO_PROBE_COMMAND: String = "sudo -n true"

        /** Budget for the `sudo -n true` probe. */
        const val SUDO_PROBE_TIMEOUT_MS: Long = 5_000L

        /** A successful passwordless-sudo probe stays trusted for 10 minutes per server. */
        const val SUDO_PROBE_TTL_MS: Long = 10 * 60_000L

        /** Budget for the `stat` probe and the temp-file cleanup of the privileged save. */
        const val FILE_STAT_TIMEOUT_MS: Long = 5_000L

        private val WHITESPACE_REGEX = Regex("\\s+")

        /** `stat -c %a` output: 1-4 octal digits. */
        private val FILE_MODE_REGEX = Regex("[0-7]{1,4}")
    }
}
