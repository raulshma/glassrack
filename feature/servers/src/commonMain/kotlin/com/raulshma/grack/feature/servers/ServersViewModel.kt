package com.raulshma.grack.feature.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.AuthType
import com.raulshma.grack.core.model.ConfirmationLevel
import com.raulshma.grack.core.model.HostKeyChallenge
import com.raulshma.grack.core.model.NetworkInterfaceInfo
import com.raulshma.grack.core.model.NetworkPortInfo
import com.raulshma.grack.core.model.PortForwardTunnel
import com.raulshma.grack.core.model.RemoteFileEntry
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.ServerRuntimeState
import com.raulshma.grack.core.model.SshKeyRecord
import com.raulshma.grack.core.model.SudoAuth
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.model.sudoSecretVaultId
import com.raulshma.grack.core.security.BiometricGate
import com.raulshma.grack.core.security.SecureVault
import com.raulshma.grack.core.ssh.HomelabController
import com.raulshma.grack.core.ssh.TextFileGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

data class ServersUiState(
    val servers: List<ServerRuntimeState> = emptyList(),
    val sshKeys: List<SshKeyRecord> = emptyList(),
    val tunnels: List<PortForwardTunnel> = emptyList(),
    val activeTunnelIds: Set<String> = emptySet(),
    val preferences: UserPreferences = UserPreferences(),
    val searchQuery: String = "",
    val selectedTag: String = "All",
    val hostKeyChallenge: HostKeyChallenge? = null,
    val dismissedHostKeyChallenge: HostKeyChallenge? = null,
    val testStatusMessage: String? = null,
    val isTestingConnection: Boolean = false,
    /** Node ids that have a dedicated sudo password stored at `srv_sudo_<serverId>`. */
    val sudoSecretServerIds: Set<String> = emptySet(),
)

/**
 * Credential edits collected by [AddEditServerSheet]. Passwords are persisted through the
 * vault only on Save (mirroring the Phase 1B login-credential semantics) and are never
 * embedded in a [Server] — the model carries references and modes only.
 */
data class ServerSecretEdits(
    val loginPassword: String? = null,
    val clearLoginPassword: Boolean = false,
    val sudoPassword: String? = null,
    val clearSudoPassword: Boolean = false,
)

data class NetworkAndFilesState(
    val interfaces: List<NetworkInterfaceInfo> = emptyList(),
    val ports: List<NetworkPortInfo> = emptyList(),
    val currentPath: String = "/",
    val files: List<RemoteFileEntry> = emptyList(),
    val openedFilePath: String? = null,
    val openedFileOriginal: String = "",
    val openedFileDraft: String = "",
    val statusMessage: String? = null,
)

class ServersViewModel(
    private val repository: GlassrackRepository,
    private val controller: HomelabController,
    private val vault: SecureVault,
    private val biometricGate: BiometricGate,
) : ViewModel() {

    private val _search = MutableStateFlow("")
    private val _selectedTag = MutableStateFlow("All")
    private val _challenge = MutableStateFlow<HostKeyChallenge?>(null)
    private val _testMsg = MutableStateFlow<String?>(null)
    private val _testing = MutableStateFlow(false)

    /** Servers whose host-key challenge the user explicitly blocked ("Block" button). */
    private val _dismissedHostKeyServerIds = MutableStateFlow<Set<String>>(emptySet())

    /** Node ids with a stored dedicated sudo password (drives the sheet's clear affordance). */
    private val _sudoSecretServerIds = MutableStateFlow<Set<String>>(emptySet())

    private val _netFiles = MutableStateFlow(NetworkAndFilesState())
    val netFilesState: StateFlow<NetworkAndFilesState> = _netFiles.asStateFlow()

    val uiState: StateFlow<ServersUiState> = combine(
        controller.runtimeStates,
        repository.sshKeys,
        repository.tunnels,
        controller.activeTunnels,
        repository.preferences,
        _search,
        _selectedTag,
        _challenge,
        _testMsg,
        _testing,
        _dismissedHostKeyServerIds,
        _sudoSecretServerIds,
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        val runtimeMap = arr[0] as Map<String, ServerRuntimeState>
        @Suppress("UNCHECKED_CAST")
        val keys = arr[1] as List<SshKeyRecord>
        @Suppress("UNCHECKED_CAST")
        val tunnels = arr[2] as List<PortForwardTunnel>
        @Suppress("UNCHECKED_CAST")
        val activeTunnels = arr[3] as Set<String>
        val prefs = arr[4] as UserPreferences
        val search = arr[5] as String
        val tag = arr[6] as String
        val challenge = arr[7] as HostKeyChallenge?
        val testMsg = arr[8] as String?
        val testing = arr[9] as Boolean
        val dismissedIds = arr[10] as Set<String>
        val sudoSecretIds = arr[11] as Set<String>

        val all = runtimeMap.values.sortedBy { it.server.sortOrder }
        val visibleChallenge = (challenge ?: all.firstNotNullOfOrNull { it.pendingHostKeyChallenge })
            ?.takeIf { it.serverId !in dismissedIds }
        val dismissedChallenge = all.firstNotNullOfOrNull { rt ->
            rt.pendingHostKeyChallenge?.takeIf { it.serverId in dismissedIds }
        }
        ServersUiState(
            servers = all,
            sshKeys = keys,
            tunnels = tunnels,
            activeTunnelIds = activeTunnels,
            preferences = prefs,
            searchQuery = search,
            selectedTag = tag,
            hostKeyChallenge = visibleChallenge,
            dismissedHostKeyChallenge = dismissedChallenge,
            testStatusMessage = testMsg,
            isTestingConnection = testing,
            sudoSecretServerIds = sudoSecretIds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ServersUiState())

    init {
        // Refresh which nodes hold a dedicated sudo password whenever the inventory changes
        // (save, import, delete) so the edit sheet's set/change/clear UI stays truthful.
        viewModelScope.launch {
            repository.servers.collect { servers ->
                val ids = servers.map { it.id }.toSet()
                val stored = ids.filter { id ->
                    runCatching { vault.containsSecret(sudoSecretVaultId(id)) }.getOrDefault(false)
                }.toSet()
                _sudoSecretServerIds.value = stored
            }
        }
    }

    fun setSearch(q: String) { _search.value = q }
    fun setSelectedTag(t: String) { _selectedTag.value = t }

    fun saveServer(draft: Server, secrets: ServerSecretEdits = ServerSecretEdits()) {
        viewModelScope.launch {
            var updated = draft
            val vaultId = draft.credentialVaultId ?: "srv_pw_${draft.id}"
            if (draft.authType == AuthType.PASSWORD && secrets.clearLoginPassword) {
                vault.deleteSecret(vaultId)
                updated = draft.copy(credentialVaultId = null)
            } else if (draft.authType == AuthType.PASSWORD && !secrets.loginPassword.isNullOrEmpty()) {
                // Set (new node) or change (rotate): the Save action is what persists it.
                vault.putSecret(vaultId, secrets.loginPassword)
                updated = draft.copy(credentialVaultId = vaultId)
            }
            // Dedicated sudo password lives at srv_sudo_<id>; there is no column — the
            // secret's existence is the state. Clearing (or leaving the mode) removes it
            // so no stale credential outlives its selection.
            val sudoVaultId = sudoSecretVaultId(draft.id)
            when {
                draft.sudoAuth == SudoAuth.SEPARATE_PASSWORD && secrets.clearSudoPassword ->
                    vault.deleteSecret(sudoVaultId)
                draft.sudoAuth == SudoAuth.SEPARATE_PASSWORD && !secrets.sudoPassword.isNullOrEmpty() ->
                    vault.putSecret(sudoVaultId, secrets.sudoPassword)
                draft.sudoAuth != SudoAuth.SEPARATE_PASSWORD ->
                    vault.deleteSecret(sudoVaultId)
            }
            repository.upsertServer(updated)
            // The node was edited: a previously blocked host-key challenge is relevant again.
            _dismissedHostKeyServerIds.update { it - draft.id }
            controller.pollServerOnce(updated)
        }
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            // Close any live forwards for this node before its tunnels are removed.
            val server = repository.getServerById(serverId)
            val state = uiState.value
            state.tunnels
                .filter { it.serverId == serverId && it.id in state.activeTunnelIds }
                .forEach { tun ->
                    if (server != null) {
                        runCatching {
                            controller.toggleTunnel(tun.id, server, tun.localPort, tun.remoteHost, tun.remotePort)
                        }.onFailure { err ->
                            Logger.w(err) { "Failed to close tunnel ${tun.label} while deleting server" }
                        }
                    }
                }
            repository.deleteServer(serverId)
        }
    }

    fun testConnection(server: Server, passwordPlain: String?, trustFirst: Boolean = false) {
        viewModelScope.launch {
            _testing.value = true
            _testMsg.value = "Handshaking with ${server.primaryHost}:${server.port}..."
            // The typed password is used for this handshake only; it is persisted when the
            // node is saved, never by testing.
            val result = controller.testServerConnection(server, trustFirst, transientPassword = passwordPlain)
            _testing.value = false
            result.fold(
                onSuccess = { challenge ->
                    if (challenge != null) {
                        _challenge.value = challenge
                        _testMsg.value = "Host key fingerprint check required"
                    } else {
                        _testMsg.value = "Connection verified cleanly!"
                    }
                },
                onFailure = { err ->
                    _testMsg.value = "Failed: ${err.message}"
                },
            )
        }
    }

    fun acceptChallenge(challenge: HostKeyChallenge) {
        viewModelScope.launch {
            controller.acceptHostKeyChallenge(challenge)
            _challenge.value = null
            _dismissedHostKeyServerIds.update { it - challenge.serverId }
            _testMsg.value = "Trusted fingerprint ${challenge.fingerprintSha256}"
        }
    }

    /** "Block" on the challenge dialog: silence it (banner instead) until the node is edited. */
    fun dismissChallenge(challenge: HostKeyChallenge?) {
        _challenge.value = null
        if (challenge != null) {
            _dismissedHostKeyServerIds.update { it + challenge.serverId }
        }
    }

    /** Re-opens the challenge dialog for a challenge that was previously blocked. */
    fun reviewHostKey(challenge: HostKeyChallenge) {
        _dismissedHostKeyServerIds.update { it - challenge.serverId }
        _challenge.value = challenge
    }

    fun wakeServer(server: Server) {
        viewModelScope.launch {
            val res = controller.sendWakeOnLan(server)
            _testMsg.value = res.fold(
                onSuccess = { "Magic packet sent for ${server.macAddress}" },
                onFailure = { "WoL error: ${it.message}" },
            )
        }
    }

    fun pollServer(server: Server) {
        viewModelScope.launch {
            controller.pollServerOnce(server)
            // Surface the outcome on the detail screen (status banner), not just inside
            // the edit sheet — pollServerOnce records failures on the runtime state.
            val state = controller.runtimeStates.value[server.id]
            _testMsg.value = if (state?.lastError != null) {
                "Poll failed for ${server.name}: ${state.lastError}"
            } else {
                "Poll finished — ${server.name} is ${state?.health?.name ?: "UNKNOWN"}"
            }
        }
    }

    fun rebootOrShutdown(server: Server, reboot: Boolean) {
        viewModelScope.launch {
            val prefs = uiState.value.preferences
            if (prefs.safeReadOnlyMode) {
                _testMsg.value = "Blocked: Safe Read-Only Mode active"
                return@launch
            }
            // The biometric prompt is part of the confirmation gate: only when the user
            // actually chose BIOMETRIC, not on every power action regardless of the setting.
            if (prefs.destructiveConfirmation == ConfirmationLevel.BIOMETRIC && biometricGate.canAuthenticate()) {
                val ok = biometricGate.authenticate(
                    title = if (reboot) "Reboot ${server.name}" else "Shutdown ${server.name}",
                    subtitle = "Hardware confirmation required for power control",
                )
                if (!ok) {
                    controller.recordBlockedAction(
                        server,
                        AuditCategory.POWER,
                        "Power action cancelled",
                        if (reboot) "reboot" else "shutdown -h now",
                        "Biometric check cancelled",
                    )
                    return@launch
                }
            }
            val res = controller.rebootOrShutdown(server, reboot, vault)
            _testMsg.value = "Power command sent (exit ${res.exitCode})"
        }
    }

    fun loadNetworkAndFiles(server: Server, path: String = _netFiles.value.currentPath) {
        viewModelScope.launch {
            runCatching {
                val (ifaces, ports) = controller.fetchNetworkDetails(server)
                val files = controller.listFiles(server, path)
                _netFiles.update {
                    it.copy(interfaces = ifaces, ports = ports, currentPath = path, files = files)
                }
            }.onFailure { err ->
                _netFiles.update { it.copy(statusMessage = err.message) }
            }
        }
    }

    fun openRemoteFile(server: Server, path: String) {
        viewModelScope.launch {
            runCatching {
                val text = controller.readFileText(server, path)
                _netFiles.update {
                    it.copy(openedFilePath = path, openedFileOriginal = text, openedFileDraft = text)
                }
            }.onFailure { err ->
                _netFiles.update { it.copy(statusMessage = err.message) }
            }
        }
    }

    fun updateFileDraft(text: String) {
        _netFiles.update { it.copy(openedFileDraft = text) }
    }

    fun saveRemoteFile(server: Server) {
        val path = _netFiles.value.openedFilePath ?: return
        val draft = _netFiles.value.openedFileDraft
        // Fail fast on binary-looking or oversized drafts before any SSH round-trip; the
        // transport enforces the same guard so the editor can never corrupt a file.
        runCatching { TextFileGuard.validateText(draft, path) }
            .onFailure { err ->
                _netFiles.update { it.copy(statusMessage = err.message) }
                return
            }
        viewModelScope.launch {
            runCatching {
                // Vault supplied so a permission-denied SFTP write escalates to the
                // privileged save (temp upload + cp -p backup + install) when possible.
                controller.saveFileWithBackup(server, path, draft, vault)
                _netFiles.update {
                    it.copy(
                        openedFileOriginal = draft,
                        openedFilePath = null,
                        statusMessage = "Saved $path (backup at $path.bak)",
                    )
                }
            }.onFailure { err ->
                _netFiles.update { it.copy(statusMessage = "Save failed: ${err.message}") }
            }
        }
    }

    fun closeRemoteFile() = _netFiles.update { it.copy(openedFilePath = null) }

    /** Deletes a saved tunnel, closing its live forward first. */
    fun deleteTunnel(tunnel: PortForwardTunnel, server: Server) {
        viewModelScope.launch {
            if (tunnel.id in uiState.value.activeTunnelIds) {
                val closed = runCatching {
                    controller.toggleTunnel(tunnel.id, server, tunnel.localPort, tunnel.remoteHost, tunnel.remotePort)
                }
                closed.onFailure { err ->
                    _netFiles.update { it.copy(statusMessage = "Tunnel error: ${err.message}") }
                    return@launch
                }
            }
            repository.deleteTunnel(tunnel.id)
            _netFiles.update { it.copy(statusMessage = "Deleted tunnel ${tunnel.label}") }
        }
    }

    fun createTunnel(serverId: String, label: String, localPort: Int, remoteHost: String, remotePort: Int) {
        viewModelScope.launch {
            repository.upsertTunnel(
                PortForwardTunnel(
                    id = "tun-${Random.nextInt(10000, 99999)}",
                    serverId = serverId,
                    label = label.ifBlank { "Port $remotePort" },
                    localPort = localPort,
                    remoteHost = remoteHost.ifBlank { "127.0.0.1" },
                    remotePort = remotePort,
                ),
            )
        }
    }

    fun toggleTunnel(tunnel: PortForwardTunnel, server: Server) {
        viewModelScope.launch {
            val res = controller.toggleTunnel(tunnel.id, server, tunnel.localPort, tunnel.remoteHost, tunnel.remotePort)
            res.onFailure { err -> _netFiles.update { it.copy(statusMessage = "Tunnel error: ${err.message}") } }
        }
    }

    fun clearMessage() {
        _testMsg.value = null
        _netFiles.update { it.copy(statusMessage = null) }
    }
}
