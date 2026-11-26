package com.raulshma.grack.feature.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.AuthType
import com.raulshma.grack.core.model.HostKeyChallenge
import com.raulshma.grack.core.model.NetworkInterfaceInfo
import com.raulshma.grack.core.model.NetworkPortInfo
import com.raulshma.grack.core.model.PortForwardTunnel
import com.raulshma.grack.core.model.RemoteFileEntry
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.ServerRuntimeState
import com.raulshma.grack.core.model.SshKeyRecord
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.security.BiometricGate
import com.raulshma.grack.core.security.SecureVault
import com.raulshma.grack.core.ssh.HomelabController
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
    val testStatusMessage: String? = null,
    val isTestingConnection: Boolean = false,
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

        val all = runtimeMap.values.sortedBy { it.server.sortOrder }
        ServersUiState(
            servers = all,
            sshKeys = keys,
            tunnels = tunnels,
            activeTunnelIds = activeTunnels,
            preferences = prefs,
            searchQuery = search,
            selectedTag = tag,
            hostKeyChallenge = challenge ?: all.firstNotNullOfOrNull { it.pendingHostKeyChallenge },
            testStatusMessage = testMsg,
            isTestingConnection = testing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ServersUiState())

    fun setSearch(q: String) { _search.value = q }
    fun setSelectedTag(t: String) { _selectedTag.value = t }

    fun saveServer(draft: Server, passwordPlain: String?) {
        viewModelScope.launch {
            var updated = draft
            if (draft.authType == AuthType.PASSWORD && !passwordPlain.isNullOrEmpty()) {
                val vaultId = draft.credentialVaultId ?: "srv_pw_${draft.id}"
                vault.putSecret(vaultId, passwordPlain)
                updated = draft.copy(credentialVaultId = vaultId)
            }
            repository.upsertServer(updated)
            controller.pollServerOnce(updated)
        }
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            repository.deleteServer(serverId)
        }
    }

    fun testConnection(server: Server, passwordPlain: String?, trustFirst: Boolean = false) {
        viewModelScope.launch {
            _testing.value = true
            _testMsg.value = "Handshaking with ${server.primaryHost}:${server.port}..."
            if (server.authType == AuthType.PASSWORD && !passwordPlain.isNullOrEmpty()) {
                val vaultId = server.credentialVaultId ?: "srv_pw_${server.id}"
                vault.putSecret(vaultId, passwordPlain)
            }
            val result = controller.testServerConnection(server, trustFirst)
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
            _testMsg.value = "Trusted fingerprint ${challenge.fingerprintSha256}"
        }
    }

    fun dismissChallenge() {
        _challenge.value = null
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
        }
    }

    fun rebootOrShutdown(server: Server, reboot: Boolean) {
        viewModelScope.launch {
            val prefs = uiState.value.preferences
            if (prefs.safeReadOnlyMode) {
                _testMsg.value = "Blocked: Safe Read-Only Mode active"
                return@launch
            }
            if (biometricGate.canAuthenticate()) {
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
            val res = controller.rebootOrShutdown(server, reboot)
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
        viewModelScope.launch {
            runCatching {
                controller.saveFileWithBackup(server, path, draft)
                _netFiles.update {
                    it.copy(
                        openedFileOriginal = draft,
                        openedFilePath = null,
                        statusMessage = "Saved $path (backup at $path.bak)",
                    )
                }
            }.onFailure { err ->
                _netFiles.update { it.copy(statusMessage = err.message) }
            }
        }
    }

    fun closeRemoteFile() = _netFiles.update { it.copy(openedFilePath = null) }

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
