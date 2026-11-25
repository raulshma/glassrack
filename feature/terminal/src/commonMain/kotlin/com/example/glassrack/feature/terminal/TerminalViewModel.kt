package com.example.glassrack.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.glassrack.core.database.GlassrackRepository
import com.example.glassrack.core.model.Server
import com.example.glassrack.core.model.Snippet
import com.example.glassrack.core.model.UserPreferences
import com.example.glassrack.core.ssh.HomelabController
import com.example.glassrack.core.terminal.ExtraKeyCode
import com.example.glassrack.core.terminal.TerminalController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

data class TerminalTabSession(
    val tabId: String,
    val server: Server,
    val title: String,
    val controller: TerminalController,
)

data class TerminalUiState(
    val tabs: List<TerminalTabSession> = emptyList(),
    val activeTabId: String? = null,
    val servers: List<Server> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val preferences: UserPreferences = UserPreferences(),
    val broadcastEnabled: Boolean = false,
    val showSnippetsDrawer: Boolean = false,
    val showServerPicker: Boolean = false,
    val pendingSnippetVariables: Pair<Snippet, List<String>>? = null,
    val errorMessage: String? = null,
)

class TerminalViewModel(
    private val repository: GlassrackRepository,
    private val sshController: HomelabController,
) : ViewModel() {

    private val _tabs = MutableStateFlow<List<TerminalTabSession>>(emptyList())
    private val _activeTabId = MutableStateFlow<String?>(null)
    private val _broadcast = MutableStateFlow(false)
    private val _showSnippets = MutableStateFlow(false)
    private val _showPicker = MutableStateFlow(false)
    private val _pendingVars = MutableStateFlow<Pair<Snippet, List<String>>?>(null)
    private val _error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TerminalUiState> = combine(
        _tabs,
        _activeTabId,
        repository.servers,
        repository.snippets,
        repository.preferences,
        _broadcast,
        _showSnippets,
        _showPicker,
        _pendingVars,
        _error,
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        val tabs = arr[0] as List<TerminalTabSession>
        val activeId = arr[1] as String?
        @Suppress("UNCHECKED_CAST")
        val servers = arr[2] as List<Server>
        @Suppress("UNCHECKED_CAST")
        val snippets = arr[3] as List<Snippet>
        val prefs = arr[4] as UserPreferences
        val broadcast = arr[5] as Boolean
        val showSnip = arr[6] as Boolean
        val showPick = arr[7] as Boolean
        @Suppress("UNCHECKED_CAST")
        val pendingVars = arr[8] as Pair<Snippet, List<String>>?
        val err = arr[9] as String?
        TerminalUiState(
            tabs = tabs,
            activeTabId = activeId,
            servers = servers,
            snippets = snippets,
            preferences = prefs,
            broadcastEnabled = broadcast,
            showSnippetsDrawer = showSnip,
            showServerPicker = showPick,
            pendingSnippetVariables = pendingVars,
            errorMessage = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TerminalUiState())

    fun openSessionForServer(serverId: String, initialCommand: String? = null) {
        viewModelScope.launch {
            val server = repository.getServerById(serverId) ?: return@launch
            runCatching {
                val shell = sshController.openInteractiveShell(server)
                val termCtrl = TerminalController(shell, uiState.value.preferences.terminalAppearance)
                val tabId = "tab-${Random.nextInt(10000, 99999)}"
                val tab = TerminalTabSession(
                    tabId = tabId,
                    server = server,
                    title = server.name.substringBefore('.'),
                    controller = termCtrl,
                )
                _tabs.update { it + tab }
                _activeTabId.value = tabId
                if (!initialCommand.isNullOrBlank()) {
                    termCtrl.sendText(initialCommand)
                }
            }.onFailure { err ->
                _error.value = "SSH Shell failed: ${err.message}"
            }
        }
    }

    fun selectTab(tabId: String) { _activeTabId.value = tabId }

    fun closeTab(tabId: String) {
        val current = _tabs.value
        val target = current.firstOrNull { it.tabId == tabId } ?: return
        target.controller.close()
        val remaining = current.filterNot { it.tabId == tabId }
        _tabs.value = remaining
        if (_activeTabId.value == tabId) {
            _activeTabId.value = remaining.lastOrNull()?.tabId
        }
    }

    fun sendExtraKey(code: ExtraKeyCode) {
        if (_broadcast.value) {
            _tabs.value.forEach { it.controller.sendExtraKey(code) }
        } else {
            activeTab()?.controller?.sendExtraKey(code)
        }
    }

    fun sendText(text: String) {
        if (_broadcast.value) {
            _tabs.value.forEach { it.controller.sendText(text) }
        } else {
            activeTab()?.controller?.sendText(text)
        }
    }

    fun toggleBroadcast() { _broadcast.update { !it } }
    fun setShowSnippets(show: Boolean) { _showSnippets.value = show }
    fun setShowServerPicker(show: Boolean) { _showPicker.value = show }

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

    private fun activeTab(): TerminalTabSession? =
        _tabs.value.firstOrNull { it.tabId == _activeTabId.value }
}
