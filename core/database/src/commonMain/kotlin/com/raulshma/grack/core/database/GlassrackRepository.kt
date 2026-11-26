package com.raulshma.grack.core.database

import com.raulshma.grack.core.model.ActiveJob
import com.raulshma.grack.core.model.AlertMetric
import com.raulshma.grack.core.model.AlertRule
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.AuditChainVerification
import com.raulshma.grack.core.model.AuditEntry
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.AuthType
import com.raulshma.grack.core.model.CustomProbeDefinition
import com.raulshma.grack.core.model.DashboardProfile
import com.raulshma.grack.core.model.DashboardTile
import com.raulshma.grack.core.model.EncryptedBackupBundle
import com.raulshma.grack.core.model.GlassrackBackupPayload
import com.raulshma.grack.core.model.JobPhase
import com.raulshma.grack.core.model.MetricKind
import com.raulshma.grack.core.model.MetricSnapshot
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.PortForwardTunnel
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.Snippet
import com.raulshma.grack.core.model.SshKeyAlgorithm
import com.raulshma.grack.core.model.SshKeyRecord
import com.raulshma.grack.core.model.SudoAuth
import com.raulshma.grack.core.model.TileType
import com.raulshma.grack.core.model.TriggerSource
import com.raulshma.grack.core.model.UpdatePolicy
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.model.currentTimeEpochMs
import com.raulshma.grack.core.model.sudoSecretVaultId
import com.raulshma.grack.core.security.CommandSanitizer
import com.raulshma.grack.core.security.SecureVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

class GlassrackRepository(
    private val db: GlassrackDatabase,
    private val vault: SecureVault,
    private val preferencesStore: PreferencesStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val auditMutex = Mutex()
    private val hasher = AuditChainHasher(vault)

    private val _activeJobs = MutableStateFlow<List<ActiveJob>>(emptyList())
    val activeJobs: StateFlow<List<ActiveJob>> = _activeJobs.asStateFlow()

    val preferences: Flow<UserPreferences> = preferencesStore.preferences

    val servers: Flow<List<Server>> = db.serverDao().observeAll().map { list ->
        list.map { it.toDomain() }
    }

    val sshKeys: Flow<List<SshKeyRecord>> = db.sshKeyDao().observeAll().map { list ->
        list.map { it.toDomain() }
    }

    val dashboards: Flow<List<DashboardProfile>> = db.dashboardDao().observeDashboards().map { list ->
        list.map { DashboardProfile(id = it.id, title = it.title, sortOrder = it.sortOrder) }
    }

    val dashboardTiles: Flow<List<DashboardTile>> = db.dashboardDao().observeAllTiles().map { list ->
        list.map { it.toDomain() }
    }

    val auditEntries: Flow<List<AuditEntry>> = db.auditDao().observeAll().map { list ->
        list.map { it.toDomain() }
    }

    val snippets: Flow<List<Snippet>> = db.snippetDao().observeAll().map { list ->
        list.map { it.toDomain() }
    }

    val alertRules: Flow<List<AlertRule>> = db.alertRuleDao().observeAll().map { list ->
        list.map { it.toDomain() }
    }

    val tunnels: Flow<List<PortForwardTunnel>> = db.tunnelDao().observeAll().map { list ->
        list.map {
            PortForwardTunnel(
                id = it.id,
                serverId = it.serverId,
                label = it.label,
                localPort = it.localPort,
                remoteHost = it.remoteHost,
                remotePort = it.remotePort,
            )
        }
    }

    val recentMetricsByServer: Flow<Map<String, List<MetricSnapshot>>> =
        db.metricSampleDao().observeRecentSamples().map { entities ->
            entities
                .mapNotNull { entity ->
                    runCatching { json.decodeFromString<MetricSnapshot>(entity.payloadJson) }.getOrNull()
                }
                .groupBy { it.serverId }
                .mapValues { (_, items) -> items.sortedBy { it.timestampEpochMs }.takeLast(40) }
        }

    suspend fun ensureSeeded() {
        if (db.dashboardDao().getDashboardsOnce().isEmpty()) {
            db.dashboardDao().upsertDashboard(
                DashboardProfileEntity(
                    id = "dash-main",
                    title = "Main Rack",
                    sortOrder = 0,
                ),
            )
        }
    }

    suspend fun getServersOnce(): List<Server> = db.serverDao().getAllOnce().map { it.toDomain() }

    suspend fun getServerById(id: String): Server? = db.serverDao().getById(id)?.toDomain()

    suspend fun upsertServer(server: Server) {
        db.serverDao().upsert(server.toEntity())
    }

    suspend fun deleteServer(serverId: String) {
        val existing = db.serverDao().getById(serverId)
        if (existing?.credentialVaultId != null) {
            vault.deleteSecret(existing.credentialVaultId)
        }
        // Cascade: a dedicated sudo password must not outlive its node either.
        vault.deleteSecret(sudoSecretVaultId(serverId))
        // Cascade: the node's port-forward tunnels must not outlive it.
        db.tunnelDao().deleteForServer(serverId)
        db.serverDao().deleteById(serverId)
    }

    suspend fun updateHostFingerprint(serverId: String, fingerprintSha256: String) {
        val existing = db.serverDao().getById(serverId) ?: return
        db.serverDao().upsert(existing.copy(hostKeyFingerprint = fingerprintSha256))
    }

    suspend fun saveSshKey(record: SshKeyRecord, privateKeyPem: String, passphrase: String?) {
        vault.putSecret(record.privateKeyVaultRef, privateKeyPem)
        if (!passphrase.isNullOrEmpty()) {
            vault.putSecret("${record.privateKeyVaultRef}_pass", passphrase)
        }
        db.sshKeyDao().upsert(record.toEntity())
    }

    suspend fun deleteSshKey(keyId: String) {
        val existing = db.sshKeyDao().getById(keyId)
        if (existing != null) {
            vault.deleteSecret(existing.privateKeyVaultRef)
            vault.deleteSecret("${existing.privateKeyVaultRef}_pass")
        }
        db.sshKeyDao().deleteById(keyId)
    }

    suspend fun getSshKeyById(keyId: String): SshKeyRecord? = db.sshKeyDao().getById(keyId)?.toDomain()

    suspend fun upsertDashboard(profile: DashboardProfile) {
        db.dashboardDao().upsertDashboard(
            DashboardProfileEntity(profile.id, profile.title, profile.sortOrder),
        )
    }

    suspend fun deleteDashboard(dashboardId: String) {
        db.dashboardDao().deleteTilesForDashboard(dashboardId)
        db.dashboardDao().deleteDashboard(dashboardId)
    }

    suspend fun upsertTile(tile: DashboardTile) {
        db.dashboardDao().upsertTile(tile.toEntity())
    }

    suspend fun reorderTiles(tiles: List<DashboardTile>) {
        val reindexed = tiles.mapIndexed { idx, tile -> tile.copy(positionIndex = idx).toEntity() }
        db.dashboardDao().upsertTiles(reindexed)
    }

    suspend fun deleteTile(tileId: String) {
        db.dashboardDao().deleteTile(tileId)
    }

    suspend fun recordMetricSnapshot(snapshot: MetricSnapshot) {
        db.metricSampleDao().insert(
            MetricSampleEntity(
                serverId = snapshot.serverId,
                timestampEpochMs = snapshot.timestampEpochMs,
                cpuPct = snapshot.cpuPct,
                memUsedBytes = snapshot.memUsedBytes,
                memTotalBytes = snapshot.memTotalBytes,
                netRxBps = snapshot.netRxBps,
                netTxBps = snapshot.netTxBps,
                tempC = snapshot.tempC,
                load1m = snapshot.load1m,
                runningContainers = snapshot.runningContainers,
                totalContainers = snapshot.totalContainers,
                payloadJson = json.encodeToString(snapshot),
            ),
        )
        // Keep rolling 24h window
        val cutoff = snapshot.timestampEpochMs - 24L * 60L * 60L * 1000L
        db.metricSampleDao().pruneOlderThan(cutoff)
    }

    suspend fun getRecentMetrics(serverId: String, limit: Int = 40): List<MetricSnapshot> {
        return db.metricSampleDao().getRecentForServer(serverId, limit)
            .mapNotNull { runCatching { json.decodeFromString<MetricSnapshot>(it.payloadJson) }.getOrNull() }
            .sortedBy { it.timestampEpochMs }
    }

    suspend fun recordAudit(
        timestampEpochMs: Long,
        serverId: String?,
        serverNameSnapshot: String,
        category: AuditCategory,
        actionTitle: String,
        rawCommand: String,
        triggerSource: TriggerSource,
        outcome: AuditOutcome,
        exitCode: Int? = null,
        durationMs: Long = 0L,
        outputSnippet: String = "",
    ): AuditEntry = auditMutex.withLock {
        val latest = db.auditDao().getLatest()
        val prevHash = latest?.entryHash ?: AuditChainHasher.GENESIS_HASH
        val id = generateId("aud")
        val sanitized = CommandSanitizer.sanitize(rawCommand)
        val cleanOutput = CommandSanitizer.sanitize(outputSnippet).take(1500)
        val draft = AuditEntry(
            id = id,
            timestampEpochMs = timestampEpochMs,
            serverId = serverId,
            serverNameSnapshot = serverNameSnapshot,
            category = category,
            actionTitle = actionTitle,
            commandSanitized = sanitized,
            triggerSource = triggerSource,
            outcome = outcome,
            exitCode = exitCode,
            durationMs = durationMs,
            outputSnippet = cleanOutput,
            prevHash = prevHash,
        )
        val entryHash = hasher.computeEntryHash(draft, prevHash)
        val finalized = draft.copy(entryHash = entryHash)
        db.auditDao().insert(finalized.toEntity())
        finalized
    }

    suspend fun verifyAuditChain(): AuditChainVerification {
        val chronological = db.auditDao().getAllChronological().map { it.toDomain() }
        return hasher.verifyChain(chronological)
    }

    /** Prunes audit entries older than [retentionDays] and re-anchors remaining chain links if any were removed. */
    suspend fun pruneAuditLogs(retentionDays: Int): Int = auditMutex.withLock {
        val safeDays = retentionDays.coerceAtLeast(1)
        val cutoff = currentTimeEpochMs() - safeDays.toLong() * 86_400_000L
        val before = db.auditDao().getAllChronological()
        db.auditDao().pruneOlderThan(cutoff)
        val remaining = db.auditDao().getAllChronological().map { it.toDomain() }
        val removedCount = (before.size - remaining.size).coerceAtLeast(0)
        if (removedCount > 0 && remaining.isNotEmpty()) {
            var prevHash = AuditChainHasher.GENESIS_HASH
            for (entry in remaining) {
                val newHash = hasher.computeEntryHash(entry, prevHash)
                if (entry.prevHash != prevHash || entry.entryHash != newHash) {
                    db.auditDao().updateHashes(entry.id, prevHash, newHash)
                }
                prevHash = newHash
            }
        }
        removedCount
    }

    suspend fun exportAuditAsJson(): String {
        val entries = db.auditDao().getAllChronological().map { it.toDomain() }
        return json.encodeToString(entries)
    }

    suspend fun exportAuditAsCsv(): String {
        val entries = db.auditDao().getAllChronological().map { it.toDomain() }
        return buildString {
            appendLine("id,timestampEpochMs,server,category,action,command,source,outcome,exitCode,durationMs,entryHash")
            entries.forEach { e ->
                fun csv(s: String): String = "\"${s.replace("\"", "\"\"")}\""
                appendLine(
                    listOf(
                        csv(e.id),
                        e.timestampEpochMs.toString(),
                        csv(e.serverNameSnapshot),
                        e.category.name,
                        csv(e.actionTitle),
                        csv(e.commandSanitized),
                        e.triggerSource.name,
                        e.outcome.name,
                        e.exitCode?.toString().orEmpty(),
                        e.durationMs.toString(),
                        csv(e.entryHash),
                    ).joinToString(","),
                )
            }
        }
    }

    suspend fun exportAuditAsMarkdown(): String {
        val entries = db.auditDao().getAllChronological().map { it.toDomain() }.reversed()
        return buildString {
            appendLine("# GRack Audit Ledger")
            appendLine()
            entries.forEach { e ->
                appendLine("- **${e.actionTitle}** (`${e.outcome.name}`) on `${e.serverNameSnapshot}` via `${e.triggerSource.name}`")
                appendLine("  - Command: `${e.commandSanitized}` | Exit: `${e.exitCode ?: "—"}` | Duration: `${e.durationMs}ms`")
                if (e.outputSnippet.isNotBlank()) {
                    appendLine("  - Output: `${e.outputSnippet.replace("\n", " ")}`")
                }
            }
        }
    }

    suspend fun upsertSnippet(snippet: Snippet) {
        db.snippetDao().upsert(snippet.toEntity())
    }

    suspend fun deleteSnippet(id: String) {
        db.snippetDao().deleteById(id)
    }

    suspend fun upsertAlertRule(rule: AlertRule) {
        db.alertRuleDao().upsert(rule.toEntity())
    }

    suspend fun deleteAlertRule(id: String) {
        db.alertRuleDao().deleteById(id)
    }

    suspend fun upsertTunnel(tunnel: PortForwardTunnel) {
        db.tunnelDao().upsert(
            TunnelEntity(
                id = tunnel.id,
                serverId = tunnel.serverId,
                label = tunnel.label,
                localPort = tunnel.localPort,
                remoteHost = tunnel.remoteHost,
                remotePort = tunnel.remotePort,
            ),
        )
    }

    suspend fun deleteTunnel(id: String) {
        db.tunnelDao().deleteById(id)
    }

    suspend fun updatePreferences(transform: (UserPreferences) -> UserPreferences) {
        preferencesStore.update(transform)
    }

    suspend fun exportEncryptedBackup(passphrase: String, nowEpochMs: Long): String {
        val payload = GlassrackBackupPayload(
            exportedAtEpochMs = nowEpochMs,
            servers = db.serverDao().getAllOnce().map { it.toDomain() },
            dashboards = db.dashboardDao().getDashboardsOnce().map {
                DashboardProfile(it.id, it.title, it.sortOrder)
            },
            tiles = db.dashboardDao().getAllTilesOnce().map { it.toDomain() },
            snippets = db.snippetDao().getAllOnce().map { it.toDomain() },
            alertRules = db.alertRuleDao().getAllOnce().map { it.toDomain() },
            preferences = preferencesStore.current(),
            sshKeyMetadata = db.sshKeyDao().getAllOnce().map { it.toDomain() },
            tunnels = db.tunnelDao().getAllOnce().map { tunnel ->
                PortForwardTunnel(
                    id = tunnel.id,
                    serverId = tunnel.serverId,
                    label = tunnel.label,
                    localPort = tunnel.localPort,
                    remoteHost = tunnel.remoteHost,
                    remotePort = tunnel.remotePort,
                )
            },
        )
        val bundle = vault.encryptBackup(json.encodeToString(payload), passphrase)
        return json.encodeToString(bundle)
    }

    suspend fun importEncryptedBackup(bundleJson: String, passphrase: String) {
        val bundle = json.decodeFromString<EncryptedBackupBundle>(bundleJson)
        val plain = vault.decryptBackup(bundle, passphrase)
        val payload = json.decodeFromString<GlassrackBackupPayload>(plain)
        db.serverDao().upsertAll(payload.servers.map { it.toEntity() })
        db.dashboardDao().upsertDashboards(
            payload.dashboards.map { DashboardProfileEntity(it.id, it.title, it.sortOrder) },
        )
        db.dashboardDao().upsertTiles(payload.tiles.map { it.toEntity() })
        db.snippetDao().upsertAll(payload.snippets.map { it.toEntity() })
        db.alertRuleDao().upsertAll(payload.alertRules.map { it.toEntity() })
        // v1 payloads have no tunnels; empty is the default, so both versions restore.
        db.tunnelDao().upsertAll(
            payload.tunnels.map { tunnel ->
                TunnelEntity(
                    id = tunnel.id,
                    serverId = tunnel.serverId,
                    label = tunnel.label,
                    localPort = tunnel.localPort,
                    remoteHost = tunnel.remoteHost,
                    remotePort = tunnel.remotePort,
                )
            },
        )
        // Key metadata was exported but previously dropped on import; restore it so key
        // references on restored servers stay resolvable. (Private key material itself
        // never leaves the vault; it must be re-imported on a fresh device.)
        db.sshKeyDao().upsertAll(payload.sshKeyMetadata.map { it.toEntity() })
        preferencesStore.update { payload.preferences }
    }

    fun startJob(
        serverId: String,
        serverName: String,
        title: String,
        commandPreview: String,
        startedEpochMs: Long,
    ): String {
        val jobId = generateId("job")
        val job = ActiveJob(
            id = jobId,
            serverId = serverId,
            serverName = serverName,
            title = title,
            commandPreview = CommandSanitizer.sanitize(commandPreview),
            phase = JobPhase.RUNNING,
            startedEpochMs = startedEpochMs,
        )
        _activeJobs.update { current -> (listOf(job) + current).take(25) }
        return jobId
    }

    fun appendJobOutput(jobId: String, chunk: String, elapsedMs: Long) {
        val sanitized = CommandSanitizer.sanitize(chunk)
        _activeJobs.update { list ->
            list.map { job ->
                if (job.id == jobId) {
                    val combined = (job.liveOutput + sanitized).takeLast(6000)
                    job.copy(liveOutput = combined, elapsedMs = elapsedMs)
                } else {
                    job
                }
            }
        }
    }

    fun finishJob(jobId: String, phase: JobPhase, exitCode: Int?, durationMs: Long, outputTail: String? = null) {
        _activeJobs.update { list ->
            list.map { job ->
                if (job.id == jobId) {
                    job.copy(
                        phase = phase,
                        exitCode = exitCode,
                        elapsedMs = durationMs,
                        liveOutput = outputTail?.let { CommandSanitizer.sanitize(it) } ?: job.liveOutput,
                    )
                } else {
                    job
                }
            }
        }
    }

    fun clearCompletedJobs() {
        _activeJobs.update { list ->
            list.filter { it.phase == JobPhase.QUEUED || it.phase == JobPhase.CONNECTING || it.phase == JobPhase.RUNNING }
        }
    }

    private fun generateId(prefix: String): String {
        val suffix = Random.nextBytes(6).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        return "$prefix-$suffix"
    }

    private fun ServerEntity.toDomain(): Server = Server(
        id = id,
        name = name,
        groupTag = groupTag,
        tags = runCatching { json.decodeFromString<List<String>>(tagsJson) }.getOrDefault(emptyList()),
        accentColorHex = accentColorHex,
        primaryHost = primaryHost,
        fallbackHost = fallbackHost,
        port = port,
        username = username,
        authType = runCatching { AuthType.valueOf(authType) }.getOrDefault(AuthType.KEY),
        credentialVaultId = credentialVaultId,
        sshKeyId = sshKeyId,
        jumpHostServerId = jumpHostServerId,
        macAddress = macAddress,
        wolRelayServerId = wolRelayServerId,
        hostKeyFingerprint = hostKeyFingerprint,
        pollIntervalSec = pollIntervalSec,
        preferredWifiSsids = runCatching { json.decodeFromString<List<String>>(preferredWifiSsidsJson) }.getOrDefault(emptyList()),
        notes = notes,
        sortOrder = sortOrder,
        capabilities = runCatching { json.decodeFromString<OsCapabilities>(capabilitiesJson) }.getOrDefault(OsCapabilities()),
        customProbes = runCatching { json.decodeFromString<List<CustomProbeDefinition>>(customProbesJson) }.getOrDefault(emptyList()),
        sudoAuth = runCatching { SudoAuth.valueOf(sudoAuth) }.getOrDefault(SudoAuth.NONE),
        updatePolicy = runCatching { UpdatePolicy.valueOf(updatePolicy) }.getOrDefault(UpdatePolicy.MANUAL),
        updateOs = updateOs,
        updateContainers = updateContainers,
        updateReportJson = updateReportJson,
    )

    private fun Server.toEntity(): ServerEntity = ServerEntity(
        id = id,
        name = name,
        groupTag = groupTag,
        tagsJson = json.encodeToString(tags),
        accentColorHex = accentColorHex,
        primaryHost = primaryHost,
        fallbackHost = fallbackHost,
        port = port,
        username = username,
        authType = authType.name,
        credentialVaultId = credentialVaultId,
        sshKeyId = sshKeyId,
        jumpHostServerId = jumpHostServerId,
        macAddress = macAddress,
        wolRelayServerId = wolRelayServerId,
        hostKeyFingerprint = hostKeyFingerprint,
        pollIntervalSec = pollIntervalSec,
        preferredWifiSsidsJson = json.encodeToString(preferredWifiSsids),
        notes = notes,
        sortOrder = sortOrder,
        isSimulated = false,
        capabilitiesJson = json.encodeToString(capabilities),
        customProbesJson = json.encodeToString(customProbes),
        sudoAuth = sudoAuth.name,
        updatePolicy = updatePolicy.name,
        updateOs = updateOs,
        updateContainers = updateContainers,
        updateReportJson = updateReportJson,
    )

    private fun SshKeyEntity.toDomain(): SshKeyRecord = SshKeyRecord(
        id = id,
        alias = alias,
        algorithm = runCatching { SshKeyAlgorithm.valueOf(algorithm) }.getOrDefault(SshKeyAlgorithm.ED25519),
        publicKeyOpenSsh = publicKeyOpenSsh,
        fingerprintSha256 = fingerprintSha256,
        privateKeyVaultRef = privateKeyVaultRef,
        hasPassphrase = hasPassphrase,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun SshKeyRecord.toEntity(): SshKeyEntity = SshKeyEntity(
        id = id,
        alias = alias,
        algorithm = algorithm.name,
        publicKeyOpenSsh = publicKeyOpenSsh,
        fingerprintSha256 = fingerprintSha256,
        privateKeyVaultRef = privateKeyVaultRef,
        hasPassphrase = hasPassphrase,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun DashboardTileEntity.toDomain(): DashboardTile = DashboardTile(
        id = id,
        dashboardId = dashboardId,
        serverId = serverId,
        tileType = runCatching { TileType.valueOf(tileType) }.getOrDefault(TileType.SINGLE_METRIC),
        metricKind = runCatching { MetricKind.valueOf(metricKind) }.getOrDefault(MetricKind.CPU_PCT),
        titleOverride = titleOverride,
        containerName = containerName,
        customCommand = customCommand,
        spanX = spanX,
        spanY = spanY,
        positionIndex = positionIndex,
    )

    private fun DashboardTile.toEntity(): DashboardTileEntity = DashboardTileEntity(
        id = id,
        dashboardId = dashboardId,
        serverId = serverId,
        tileType = tileType.name,
        metricKind = metricKind.name,
        titleOverride = titleOverride,
        containerName = containerName,
        customCommand = customCommand,
        spanX = spanX,
        spanY = spanY,
        positionIndex = positionIndex,
    )

    private fun AuditEntryEntity.toDomain(): AuditEntry = AuditEntry(
        id = id,
        timestampEpochMs = timestampEpochMs,
        serverId = serverId,
        serverNameSnapshot = serverNameSnapshot,
        category = runCatching { AuditCategory.valueOf(category) }.getOrDefault(AuditCategory.SSH),
        actionTitle = actionTitle,
        commandSanitized = commandSanitized,
        triggerSource = runCatching { TriggerSource.valueOf(triggerSource) }.getOrDefault(TriggerSource.UI),
        outcome = runCatching { AuditOutcome.valueOf(outcome) }.getOrDefault(AuditOutcome.SUCCESS),
        exitCode = exitCode,
        durationMs = durationMs,
        outputSnippet = outputSnippet,
        prevHash = prevHash,
        entryHash = entryHash,
    )

    private fun AuditEntry.toEntity(): AuditEntryEntity = AuditEntryEntity(
        id = id,
        timestampEpochMs = timestampEpochMs,
        serverId = serverId,
        serverNameSnapshot = serverNameSnapshot,
        category = category.name,
        actionTitle = actionTitle,
        commandSanitized = commandSanitized,
        triggerSource = triggerSource.name,
        outcome = outcome.name,
        exitCode = exitCode,
        durationMs = durationMs,
        outputSnippet = outputSnippet,
        prevHash = prevHash,
        entryHash = entryHash,
    )

    private fun SnippetEntity.toDomain(): Snippet = Snippet(
        id = id,
        name = name,
        description = description,
        commandTemplate = commandTemplate,
        targetTags = runCatching { json.decodeFromString<List<String>>(targetTagsJson) }.getOrDefault(emptyList()),
        requiresConfirmation = requiresConfirmation,
        sortOrder = sortOrder,
        scheduleSpec = scheduleSpec,
        scheduleTargets = scheduleTargetsJson?.let { parsed ->
            runCatching { json.decodeFromString<List<String>>(parsed) }.getOrNull()
        },
        lastRunEpochMs = lastRunEpochMs,
        lastRunStatus = lastRunStatus,
    )

    private fun Snippet.toEntity(): SnippetEntity = SnippetEntity(
        id = id,
        name = name,
        description = description,
        commandTemplate = commandTemplate,
        targetTagsJson = json.encodeToString(targetTags),
        requiresConfirmation = requiresConfirmation,
        sortOrder = sortOrder,
        scheduleSpec = scheduleSpec,
        scheduleTargetsJson = scheduleTargets?.let { json.encodeToString(it) },
        lastRunEpochMs = lastRunEpochMs,
        lastRunStatus = lastRunStatus,
    )

    private fun AlertRuleEntity.toDomain(): AlertRule = AlertRule(
        id = id,
        name = name,
        serverId = serverId,
        metric = runCatching { AlertMetric.valueOf(metric) }.getOrDefault(AlertMetric.CPU_PCT),
        threshold = threshold,
        enabled = enabled,
    )

    private fun AlertRule.toEntity(): AlertRuleEntity = AlertRuleEntity(
        id = id,
        name = name,
        serverId = serverId,
        metric = metric.name,
        threshold = threshold,
        enabled = enabled,
    )
}
