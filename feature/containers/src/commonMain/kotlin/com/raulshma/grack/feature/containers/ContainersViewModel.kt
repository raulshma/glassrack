package com.raulshma.grack.feature.containers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.ComposeStack
import com.raulshma.grack.core.model.ConfirmationLevel
import com.raulshma.grack.core.model.ContainerAction
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.ContainerState
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.security.BiometricGate
import com.raulshma.grack.core.ssh.HomelabController
import com.raulshma.grack.core.ssh.TelemetryParsers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ContainerFilter {
    ALL,
    RUNNING,
    STOPPED,
    STACKS_ONLY,
}

data class ContainersUiState(
    val isLoading: Boolean = false,
    val stacks: List<ComposeStack> = emptyList(),
    val standalone: List<ContainerInfo> = emptyList(),
    val searchQuery: String = "",
    val filter: ContainerFilter = ContainerFilter.ALL,
    val activeTransitionIds: Set<String> = emptySet(),
    val logsContainer: ContainerInfo? = null,
    val logsText: String = "",
    val inspectContainer: ContainerInfo? = null,
    val pendingConfirmAction: Pair<ContainerInfo, ContainerAction>? = null,
    val feedbackMessage: String? = null,
)

class ContainersViewModel(
    private val controller: HomelabController,
    private val repository: GlassrackRepository,
    private val biometricGate: BiometricGate,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = repository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    private val _uiState = MutableStateFlow(ContainersUiState())
    val uiState: StateFlow<ContainersUiState> = _uiState.asStateFlow()

    fun loadContainers(server: Server) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                val all = controller.fetchContainers(server)
                val (stacks, standalone) = TelemetryParsers.groupComposeStacks(all)
                _uiState.update { it.copy(isLoading = false, stacks = stacks, standalone = standalone) }
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, feedbackMessage = err.message) }
            }
        }
    }

    fun setSearch(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setFilter(filter: ContainerFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun requestContainerAction(server: Server, container: ContainerInfo, action: ContainerAction) {
        val prefs = preferences.value
        if (prefs.safeReadOnlyMode) {
            viewModelScope.launch {
                controller.recordBlockedAction(
                    server = server,
                    category = AuditCategory.CONTAINER,
                    actionTitle = "Blocked ${action.label} on ${container.name}",
                    command = "docker ${action.name.lowercase()} ${container.name}",
                    reason = "Safe Read-Only Mode is active",
                )
            }
            _uiState.update { it.copy(feedbackMessage = "Blocked: Safe Read-Only Mode is enabled") }
            return
        }

        val requiresGate = (action.isDestructive || container.isCritical) &&
            prefs.destructiveConfirmation != ConfirmationLevel.NONE

        if (requiresGate) {
            if (prefs.destructiveConfirmation == ConfirmationLevel.BIOMETRIC && biometricGate.canAuthenticate()) {
                viewModelScope.launch {
                    val ok = biometricGate.authenticate(
                        title = "${action.label} ${container.name}",
                        subtitle = "Confirm container operation on ${server.name}",
                    )
                    if (ok) {
                        executeContainerAction(server, container, action)
                    } else {
                        controller.recordBlockedAction(
                            server = server,
                            category = AuditCategory.CONTAINER,
                            actionTitle = "Cancelled ${action.label} on ${container.name}",
                            command = "docker ${action.name.lowercase()} ${container.name}",
                            reason = "Biometric confirmation cancelled",
                        )
                        _uiState.update { it.copy(feedbackMessage = "Biometric confirmation cancelled") }
                    }
                }
            } else {
                _uiState.update { it.copy(pendingConfirmAction = container to action) }
            }
        } else {
            executeContainerAction(server, container, action)
        }
    }

    fun confirmPendingAction(server: Server) {
        val pending = _uiState.value.pendingConfirmAction ?: return
        _uiState.update { it.copy(pendingConfirmAction = null) }
        executeContainerAction(server, pending.first, pending.second)
    }

    fun dismissPendingAction() {
        _uiState.update { it.copy(pendingConfirmAction = null) }
    }

    private fun executeContainerAction(server: Server, container: ContainerInfo, action: ContainerAction) {
        viewModelScope.launch {
            _uiState.update { it.copy(activeTransitionIds = it.activeTransitionIds + container.id) }
            val result = controller.performContainerAction(server, container, action)
            val updated = controller.fetchContainers(server)
            val (stacks, standalone) = TelemetryParsers.groupComposeStacks(updated)
            _uiState.update {
                it.copy(
                    activeTransitionIds = it.activeTransitionIds - container.id,
                    stacks = stacks,
                    standalone = standalone,
                    feedbackMessage = if (result.exitCode == 0) {
                        "${container.name}: ${action.label} succeeded (${result.durationMs}ms)"
                    } else {
                        "${container.name}: ${result.stderr.ifBlank { "Exit ${result.exitCode}" }}"
                    },
                )
            }
        }
    }

    fun performComposeStackVerb(server: Server, stack: ComposeStack, verb: String) {
        if (preferences.value.safeReadOnlyMode) {
            _uiState.update { it.copy(feedbackMessage = "Blocked: Safe Read-Only Mode is enabled") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val res = controller.performComposeStackAction(server, stack.projectName, stack.workingDir, verb)
            val updated = controller.fetchContainers(server)
            val (stacks, standalone) = TelemetryParsers.groupComposeStacks(updated)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    stacks = stacks,
                    standalone = standalone,
                    feedbackMessage = "Stack ${stack.projectName} ($verb): exit ${res.exitCode}",
                )
            }
        }
    }

    fun pruneUnused(server: Server) {
        if (preferences.value.safeReadOnlyMode) {
            _uiState.update { it.copy(feedbackMessage = "Blocked: Safe Read-Only Mode is enabled") }
            return
        }
        viewModelScope.launch {
            val res = controller.executeAuditedAction(
                server = server,
                category = AuditCategory.CONTAINER,
                actionTitle = "Docker System Prune",
                command = "docker system prune -f",
            )
            _uiState.update { it.copy(feedbackMessage = "Prune completed (exit ${res.exitCode})") }
        }
    }

    fun openLogs(server: Server, container: ContainerInfo) {
        viewModelScope.launch {
            _uiState.update { it.copy(logsContainer = container, logsText = "Streaming logs for ${container.name}...") }
            val logs = controller.fetchContainerLogs(server, container.name)
            _uiState.update { it.copy(logsText = logs.ifBlank { "(no log output)" }) }
        }
    }

    fun closeLogs() {
        _uiState.update { it.copy(logsContainer = null, logsText = "") }
    }

    fun openInspect(container: ContainerInfo) {
        _uiState.update { it.copy(inspectContainer = container) }
    }

    fun closeInspect() {
        _uiState.update { it.copy(inspectContainer = null) }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }
}
