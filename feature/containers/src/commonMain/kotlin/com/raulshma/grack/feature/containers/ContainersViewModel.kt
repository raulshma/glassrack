package com.raulshma.grack.feature.containers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.ComposeStack
import com.raulshma.grack.core.model.ConfirmationLevel
import com.raulshma.grack.core.model.ContainerAction
import com.raulshma.grack.core.model.ContainerImageInfo
import com.raulshma.grack.core.model.ContainerInfo
import com.raulshma.grack.core.model.ContainerState
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.model.isDestructiveComposeVerb
import com.raulshma.grack.core.model.requiresConfirmation
import com.raulshma.grack.core.security.BiometricGate
import com.raulshma.grack.core.ssh.HomelabController
import com.raulshma.grack.core.ssh.TelemetryParsers
import com.raulshma.grack.core.ssh.binaryName
import kotlinx.coroutines.Job
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
    val images: List<ContainerImageInfo> = emptyList(),
    val searchQuery: String = "",
    val filter: ContainerFilter = ContainerFilter.ALL,
    val activeTransitionIds: Set<String> = emptySet(),
    val logsContainer: ContainerInfo? = null,
    val logsText: String = "",
    val isFollowingLogs: Boolean = false,
    val logsRegexEnabled: Boolean = false,
    val logsWrapLines: Boolean = true,
    val inspectContainer: ContainerInfo? = null,
    val pendingConfirmAction: Pair<ContainerInfo, ContainerAction>? = null,
    val pendingConfirmStackVerb: Pair<ComposeStack, String>? = null,
    val pendingConfirmPrune: Boolean = false,
    val pendingConfirmImageRemove: ContainerImageInfo? = null,
    val pendingConfirmDanglingPrune: Boolean = false,
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

    /** Live `logs -f` follow stream; cancelled when the pill toggles off or the sheet closes. */
    private var followJob: Job? = null

    fun loadContainers(server: Server) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                val all = controller.fetchContainers(server)
                val (stacks, standalone) = TelemetryParsers.groupComposeStacks(all)
                // A failed image listing must not blank the container list.
                val images = runCatching { controller.fetchImages(server) }
                    .onFailure { Logger.w(it) { "Image listing failed" } }
                    .getOrDefault(_uiState.value.images)
                _uiState.update {
                    it.copy(isLoading = false, stacks = stacks, standalone = standalone, images = images)
                }
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
                    command = "${container.runtime.binaryName} ${action.name.lowercase()} ${container.name}",
                    reason = "Safe Read-Only Mode is active",
                )
            }
            _uiState.update { it.copy(feedbackMessage = "Blocked: Safe Read-Only Mode is enabled") }
            return
        }

        val requiresGate = requiresConfirmation(
            actionIsDestructive = action.isDestructive || container.isCritical,
            prefs = prefs,
        )

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
                            command = "${container.runtime.binaryName} ${action.name.lowercase()} ${container.name}",
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
        val prefs = preferences.value
        if (prefs.safeReadOnlyMode) {
            _uiState.update { it.copy(feedbackMessage = "Blocked: Safe Read-Only Mode is enabled") }
            return
        }
        if (requiresConfirmation(actionIsDestructive = isDestructiveComposeVerb(verb), prefs = prefs)) {
            _uiState.update { it.copy(pendingConfirmStackVerb = stack to verb) }
            return
        }
        executeStackVerb(server, stack, verb)
    }

    fun confirmStackVerb(server: Server) {
        val pending = _uiState.value.pendingConfirmStackVerb ?: return
        _uiState.update { it.copy(pendingConfirmStackVerb = null) }
        executeStackVerb(server, pending.first, pending.second)
    }

    fun dismissStackVerb() {
        _uiState.update { it.copy(pendingConfirmStackVerb = null) }
    }

    private fun executeStackVerb(server: Server, stack: ComposeStack, verb: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val res = controller.performComposeStackAction(
                server = server,
                projectName = stack.projectName,
                workingDir = stack.workingDir,
                verb = verb,
                configFiles = stack.configFiles,
            )
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
        val prefs = preferences.value
        if (prefs.safeReadOnlyMode) {
            _uiState.update { it.copy(feedbackMessage = "Blocked: Safe Read-Only Mode is enabled") }
            return
        }
        if (requiresConfirmation(actionIsDestructive = true, prefs = prefs)) {
            _uiState.update { it.copy(pendingConfirmPrune = true) }
            return
        }
        executePrune(server)
    }

    fun confirmPrune(server: Server) {
        _uiState.update { it.copy(pendingConfirmPrune = false) }
        executePrune(server)
    }

    fun dismissPrune() {
        _uiState.update { it.copy(pendingConfirmPrune = false) }
    }

    private fun executePrune(server: Server) {
        viewModelScope.launch {
            // Prune the runtime the node actually uses instead of a hardcoded docker.
            val bin = controller.containerRuntimeFor(server).binaryName
            val res = controller.executeAuditedAction(
                server = server,
                category = AuditCategory.CONTAINER,
                actionTitle = "${bin.replaceFirstChar { it.uppercase() }} System Prune",
                command = "$bin system prune -f",
            )
            _uiState.update { it.copy(feedbackMessage = "Prune completed (exit ${res.exitCode})") }
        }
    }

    fun requestImageRemove(server: Server, image: ContainerImageInfo) {
        val prefs = preferences.value
        if (prefs.safeReadOnlyMode) {
            _uiState.update { it.copy(feedbackMessage = "Blocked: Safe Read-Only Mode is enabled") }
            return
        }
        if (requiresConfirmation(actionIsDestructive = true, prefs = prefs)) {
            _uiState.update { it.copy(pendingConfirmImageRemove = image) }
            return
        }
        executeImageRemove(server, image)
    }

    fun confirmImageRemove(server: Server) {
        val pending = _uiState.value.pendingConfirmImageRemove ?: return
        _uiState.update { it.copy(pendingConfirmImageRemove = null) }
        executeImageRemove(server, pending)
    }

    fun dismissImageRemove() {
        _uiState.update { it.copy(pendingConfirmImageRemove = null) }
    }

    private fun executeImageRemove(server: Server, image: ContainerImageInfo) {
        viewModelScope.launch {
            val res = controller.performImageRemove(server, image)
            val images = runCatching { controller.fetchImages(server) }.getOrDefault(_uiState.value.images)
            _uiState.update {
                it.copy(
                    images = images,
                    feedbackMessage = if (res.exitCode == 0) {
                        "Removed image ${image.repoTag}"
                    } else {
                        "Remove ${image.repoTag}: ${res.stderr.ifBlank { "Exit ${res.exitCode}" }}"
                    },
                )
            }
        }
    }

    fun requestDanglingImagePrune(server: Server) {
        val prefs = preferences.value
        if (prefs.safeReadOnlyMode) {
            _uiState.update { it.copy(feedbackMessage = "Blocked: Safe Read-Only Mode is enabled") }
            return
        }
        if (requiresConfirmation(actionIsDestructive = true, prefs = prefs)) {
            _uiState.update { it.copy(pendingConfirmDanglingPrune = true) }
            return
        }
        executeDanglingImagePrune(server)
    }

    fun confirmDanglingImagePrune(server: Server) {
        _uiState.update { it.copy(pendingConfirmDanglingPrune = false) }
        executeDanglingImagePrune(server)
    }

    fun dismissDanglingImagePrune() {
        _uiState.update { it.copy(pendingConfirmDanglingPrune = false) }
    }

    private fun executeDanglingImagePrune(server: Server) {
        viewModelScope.launch {
            val res = controller.performDanglingImagePrune(server)
            val images = runCatching { controller.fetchImages(server) }.getOrDefault(_uiState.value.images)
            _uiState.update {
                it.copy(
                    images = images,
                    feedbackMessage = if (res.exitCode == 0) {
                        "Dangling images pruned"
                    } else {
                        "Dangling prune: ${res.stderr.ifBlank { "Exit ${res.exitCode}" }}"
                    },
                )
            }
        }
    }

    fun openLogs(server: Server, container: ContainerInfo) {
        followJob?.cancel()
        followJob = null
        viewModelScope.launch {
            _uiState.update { it.copy(logsContainer = container, logsText = "Streaming logs for ${container.name}...", isFollowingLogs = false) }
            val logs = controller.fetchContainerLogs(server, container)
            _uiState.update { it.copy(logsText = logs.ifBlank { "(no log output)" }) }
        }
    }

    /** Toggles the untimed `logs -f` follow stream for the open logs sheet. */
    fun toggleFollowLogs(server: Server, container: ContainerInfo) {
        val current = followJob
        if (current?.isActive == true) {
            current.cancel()
            return
        }
        var job: Job? = null
        job = viewModelScope.launch {
            _uiState.update { it.copy(isFollowingLogs = true) }
            try {
                controller.followContainerLogs(server, container) { chunk ->
                    _uiState.update { state ->
                        val combined = state.logsText + chunk
                        // Keep the follow buffer bounded; the transport already caps retention.
                        val bounded = if (combined.length > MAX_FOLLOW_BUFFER_CHARS) {
                            combined.takeLast(MAX_FOLLOW_BUFFER_CHARS)
                        } else {
                            combined
                        }
                        state.copy(logsText = bounded)
                    }
                }
            } finally {
                // Only clear when this job is still the active follow (a new follow may
                // have started while this one was being cancelled).
                if (followJob === job) followJob = null
                _uiState.update { it.copy(isFollowingLogs = false) }
            }
        }
    }

    fun closeLogs() {
        followJob?.cancel()
        followJob = null
        _uiState.update { it.copy(logsContainer = null, logsText = "", isFollowingLogs = false) }
    }

    fun setLogsRegexEnabled(enabled: Boolean) {
        _uiState.update { it.copy(logsRegexEnabled = enabled) }
    }

    fun setLogsWrapLines(wrap: Boolean) {
        _uiState.update { it.copy(logsWrapLines = wrap) }
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

    fun setFeedback(message: String) {
        _uiState.update { it.copy(feedbackMessage = message) }
    }

    private companion object {
        /** Bound for the accumulated follow-log text kept in UI state. */
        const val MAX_FOLLOW_BUFFER_CHARS: Int = 400_000
    }
}
