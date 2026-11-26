package com.raulshma.grack.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.Snippet
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.ssh.HomelabController
import com.raulshma.grack.core.terminal.ExtraKeyCode
import com.raulshma.grack.core.terminal.ManagedTerminalTab
import com.raulshma.grack.core.terminal.TerminalSessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TerminalUiState(
    val tabs: List<ManagedTerminalTab> = emptyList(),
    val activeTabId: String? = null,
    val servers: List<Server> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val preferences: UserPreferences = UserPreferences(),
    val broadcastEnabled: Boolean = false,
    val ctrlLatched: Boolean = false,
    val altLatched: Boolean = false,
    val showSnippetsDrawer: Boolean = false,
    val showServerPicker: Boolean = false,
    val showHistoryDrawer: Boolean = false,
    /** Most recent commands for the active tab's node, oldest first. */
    val commandHistory: List<String> = emptyList(),
    val pendingSnippetVariables: Pair<Snippet, List<String>>? = null,
    val errorMessage: String? = null,
)

/**
 * Thin view onto the app-scoped [TerminalSessionManager] (Phase 7): the tabs and their
 * shells live in the manager, so leaving the SSH tab (which destroys this ViewModel)
 * no longer closes anything. Teardown happens only through the manager's explicit
 * Disconnect paths — per-tab [closeTab], the foreground service's Disconnect action
 * ([TerminalSessionManager.disconnectAll]), or process death. There is intentionally no
 * `onCleared` teardown any more.
 */
class TerminalViewModel(
    private val repository: GlassrackRepository,
    private val sshController: HomelabController,
    private val sessionManager: TerminalSessionManager,
) : ViewModel() {

    private val _broadcast = MutableStateFlow(false)
    private val _modifiers = MutableStateFlow(false to false)
    private val _showSnippets = MutableStateFlow(false)
    private val _showPicker = MutableStateFlow(false)
    private val _showHistory = MutableStateFlow(false)
    private val _pendingVars = MutableStateFlow<Pair<Snippet, List<String>>?>(null)
    private val _error = MutableStateFlow<String?>(null)

    init {
        // The manager surfaces shell-open failures; re-home them into the view's error card.
        viewModelScope.launch {
            sessionManager.errors.collect { err ->
                if (err != null) {
                    _error.value = err
                    sessionManager.clearError()
                }
            }
        }
    }

    val uiState: StateFlow<TerminalUiState> = combine(
        sessionManager.tabs,
        sessionManager.activeTabId,
        sessionManager.commandHistory,
        repository.servers,
        repository.snippets,
        repository.preferences,
        _broadcast,
        _modifiers,
        _showSnippets,
        _showPicker,
        _showHistory,
        _pendingVars,
        _error,
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        val tabs = arr[0] as List<ManagedTerminalTab>
        val activeId = arr[1] as String?
        @Suppress("UNCHECKED_CAST")
        val historyByServer = arr[2] as Map<String, List<String>>
        @Suppress("UNCHECKED_CAST")
        val servers = arr[3] as List<Server>
        @Suppress("UNCHECKED_CAST")
        val snippets = arr[4] as List<Snippet>
        val prefs = arr[5] as UserPreferences
        val broadcast = arr[6] as Boolean
        @Suppress("UNCHECKED_CAST")
        val modifiers = arr[7] as Pair<Boolean, Boolean>
        val showSnip = arr[8] as Boolean
        val showPick = arr[9] as Boolean
        val showHistory = arr[10] as Boolean
        @Suppress("UNCHECKED_CAST")
        val pendingVars = arr[11] as Pair<Snippet, List<String>>?
        val err = arr[12] as String?
        TerminalUiState(
            tabs = tabs,
            activeTabId = activeId,
            servers = servers,
            snippets = snippets,
            preferences = prefs,
            broadcastEnabled = broadcast,
            ctrlLatched = modifiers.first,
            altLatched = modifiers.second,
            showSnippetsDrawer = showSnip,
            showServerPicker = showPick,
            showHistoryDrawer = showHistory,
            commandHistory = activeId?.let { id ->
                tabs.firstOrNull { it.tabId == id }?.server?.id?.let { serverId -> historyByServer[serverId].orEmpty() }
            }.orEmpty(),
            pendingSnippetVariables = pendingVars,
            errorMessage = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TerminalUiState())

    fun openSessionForServer(serverId: String, initialCommand: String? = null) {
        viewModelScope.launch {
            val server = repository.getServerById(serverId) ?: return@launch
            sessionManager.openSession(server, initialCommand)
        }
    }

    fun selectTab(tabId: String) {
        sessionManager.selectTab(tabId)
        _modifiers.value = false to false
    }

    /** Explicit Disconnect of one tab; the shell channel closes here and only here. */
    fun closeTab(tabId: String) {
        sessionManager.disconnect(tabId)
        _modifiers.value = false to false
    }

    /** Retry from the Reconnect banner. */
    fun reconnectActive() {
        stateActiveTabId()?.let { sessionManager.reconnect(it) }
        _modifiers.value = false to false
    }

    fun sendExtraKey(code: ExtraKeyCode) {
        when (code) {
            ExtraKeyCode.CTRL -> _modifiers.update { (ctrl, alt) -> !ctrl to alt }
            ExtraKeyCode.ALT -> _modifiers.update { (ctrl, alt) -> ctrl to !alt }
            else -> Unit
        }
        if (_broadcast.value) {
            _tabs().forEach { sessionManager.sendExtraKey(it.tabId, code) }
        } else {
            stateActiveTabId()?.let { sessionManager.sendExtraKey(it, code) }
        }
    }

    fun sendText(text: String) {
        _modifiers.value = false to false
        if (_broadcast.value) {
            _tabs().forEach { sessionManager.sendText(it.tabId, text) }
        } else {
            stateActiveTabId()?.let { sessionManager.sendText(it, text) }
        }
    }

    /**
     * Clipboard paste routed through the emulator's `pasteText` (Phase 7 item 4): the
     * emulator handles bracketed paste and large payloads instead of blind byte writes.
     */
    fun pasteFromClipboard(text: String) {
        _modifiers.value = false to false
        stateActiveTabId()?.let { sessionManager.pasteText(it, text) }
    }

    fun toggleBroadcast() { _broadcast.update { !it } }
    fun setShowSnippets(show: Boolean) { _showSnippets.value = show }
    fun setShowServerPicker(show: Boolean) { _showPicker.value = show }
    fun setShowHistory(show: Boolean) { _showHistory.value = show }

    fun triggerSnippet(snippet: Snippet) {
        val vars = Regex("""\{\{([a-zA-Z0-9_]+)\}\}""")
            .findAll(snippet.commandTemplate)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        _showSnippets.value = false
        if (vars.isEmpty()) {
            sendText("${snippet.commandTemplate}\n")
        } else {
            _pendingVars.value = snippet to vars
        }
    }

    fun submitSnippetWithVars(values: Map<String, String>) {
        val (snippet, _) = _pendingVars.value ?: return
        var cmd = snippet.commandTemplate
        values.forEach { (k, v) ->
            cmd = cmd.replace("{{$k}}", v)
        }
        _pendingVars.value = null
        sendText("$cmd\n")
    }

    fun dismissSnippetVars() { _pendingVars.value = null }
    fun clearError() { _error.value = null }

    private fun _tabs(): List<ManagedTerminalTab> = sessionManager.tabs.value

    private fun stateActiveTabId(): String? = sessionManager.activeTabId.value
}
