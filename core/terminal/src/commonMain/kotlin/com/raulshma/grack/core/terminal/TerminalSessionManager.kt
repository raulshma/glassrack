package com.raulshma.grack.core.terminal

import co.touchlab.kermit.Logger
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.TerminalAppearance
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.ssh.SshShellSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * Notification surface for the SSH foreground-service feature (Phase 7). The session
 * manager reports session-count changes, lost sessions and foreground-service timeouts;
 * only the Android app provides an implementation (bound in Koin), mirroring the
 * `AlertNotifier` pattern. Tests observe the contract through a fake.
 */
interface SshSessionNotifier {
    /** Session set changed: start or update the ongoing "N sessions" foreground notification. */
    fun updateSessionNotification(count: Int, nodeNames: List<String>)

    /** Last session ended: stop the foreground service cleanly. */
    fun clearSessionNotification()

    /** A session dropped and all reconnect attempts failed. */
    fun notifySessionLost(serverName: String)

    /** The system called the foreground service's `onTimeout` — the service is stopping. */
    fun notifyServiceTimedOut(nodeNames: List<String>)
}

enum class TerminalConnectionState {
    /** Shell opening / handle creation in flight. */
    CONNECTING,
    CONNECTED,

    /** Automatic reconnect cycle in flight ([ManagedTerminalTab.reconnectAttempt] is 1-based). */
    RECONNECTING,

    /** All reconnect attempts failed; the Reconnect banner offers a manual retry. */
    DISCONNECTED,
}

/**
 * Manager-facing end of a terminal tab. Wraps the platform [TerminalController] so the
 * session manager (and its tests) never touch the platform emulator; the UI reaches the
 * emulator through [controller] to render [TerminalSurface]. Production binds
 * [DefaultTerminalTabHandle]; tests bind a recording fake whose [controller] is never read.
 */
interface TerminalTabHandle {
    /** The platform controller, rendered by [TerminalSurface]. */
    val controller: TerminalController

    fun sendText(text: String)
    fun sendExtraKey(key: ExtraKeyCode)
    fun pasteText(text: String)

    /** Swaps the underlying shell after a successful reconnect, keeping the emulator. */
    fun reattach(shell: SshShellSession)

    /** Closes the emulator and the underlying SSH shell channel. */
    fun close()
}

class DefaultTerminalTabHandle(
    shell: SshShellSession,
    appearance: TerminalAppearance,
) : TerminalTabHandle {
    override val controller: TerminalController = TerminalController(shell, appearance)

    override fun sendText(text: String) = controller.sendText(text)

    override fun sendExtraKey(key: ExtraKeyCode) = controller.sendExtraKey(key)

    override fun pasteText(text: String) = controller.pasteText(text)

    override fun reattach(shell: SshShellSession) = controller.reattach(shell)

    override fun close() = controller.close()
}

/** Opens the interactive PTY shell for a tab; production routes through HomelabController so the shell stays audited. */
fun interface TerminalShellOpener {
    suspend fun open(server: Server, cols: Int, rows: Int): SshShellSession
}

fun interface TerminalTabHandleFactory {
    fun create(shell: SshShellSession, appearance: TerminalAppearance): TerminalTabHandle
}

/**
 * Reconnect cadence (Phase 7 item 3): three attempts with exponential backoff before the
 * tab surfaces the manual Reconnect banner. Delays run before each attempt: 1s, 2s, 4s.
 */
object TerminalReconnectPolicy {
    const val MAX_ATTEMPTS: Int = 3

    fun delayBeforeAttemptMs(attempt: Int): Long = 1_000L shl (attempt - 1).coerceIn(0, 5)
}

/**
 * Command sent to a fresh shell when `tmuxAutoAttach` is on: the `command -v` guard
 * respects nodes without tmux (silently skipping instead of littering the prompt with
 * "command not found"), and `tmux new -A` attaches to the named session or creates it —
 * the canonical `tmux attach || tmux new` spelling.
 */
fun tmuxAttachCommand(sessionName: String = "grack"): String =
    "command -v tmux >/dev/null 2>&1 && tmux new -A -s $sessionName || true"

/**
 * One interactive shell tab owned by [TerminalSessionManager]; survives leaving the SSH
 * tab. Plain class (not a data class): [handle] is a heavyweight runtime reference that
 * must never be duplicated or compared by value.
 */
class ManagedTerminalTab(
    val tabId: String,
    val server: Server,
    val title: String,
    internal val handle: TerminalTabHandle,
    val state: TerminalConnectionState = TerminalConnectionState.CONNECTING,
    /** 1-based attempt index while [state] is RECONNECTING, null otherwise. */
    val reconnectAttempt: Int? = null,
) {
    /** Rendered by [TerminalSurface]; backed by the tab's platform controller. */
    val controller: TerminalController
        get() = handle.controller

    internal fun with(
        state: TerminalConnectionState = this.state,
        reconnectAttempt: Int? = this.reconnectAttempt,
    ): ManagedTerminalTab = ManagedTerminalTab(tabId, server, title, handle, state, reconnectAttempt)
}

internal class TabRuntime(
    val handle: TerminalTabHandle,
    /** The live shell; swapped by a successful reconnect. */
    @kotlin.concurrent.Volatile var shell: SshShellSession,
    var watcherJob: Job? = null,
)

/**
 * App-scoped owner of the interactive terminal tabs (Phase 7 item 1). Previously the tabs
 * lived in the terminal ViewModel and were torn down in its `onCleared`, so leaving the
 * SSH tab killed every shell. Now the manager is a Koin single: sessions survive leaving
 * the tab, and die only on an explicit [disconnect] (which stops the foreground service
 * when the last session goes) or process teardown.
 *
 * Reconnect (item 3): each tab watches its shell's `isConnected`; on drop the manager runs
 * [TerminalReconnectPolicy.MAX_ATTEMPTS] opens with backoff, reattaching the same emulator
 * on success (scrollback preserved). After the final failure the tab reports DISCONNECTED
 * and a manual [reconnect] retry re-runs the cycle.
 *
 * The open shells keep registering in the transport's `openShellsByServer` (Phase 1A), so
 * `disconnectIdle` never prunes a pooled session that backs a live tab.
 */
class TerminalSessionManager(
    private val shellOpener: TerminalShellOpener,
    private val handleFactory: TerminalTabHandleFactory,
    preferences: Flow<UserPreferences>,
    private val notifier: SshSessionNotifier? = null,
    backgroundDispatcher: CoroutineContext = Dispatchers.Default,
    private val maxHistoryPerServer: Int = DEFAULT_COMMAND_HISTORY_SIZE,
) {
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + backgroundDispatcher)
    private val tabMutex = Mutex()
    private val runtimes = mutableMapOf<String, TabRuntime>()

    private val _tabs = MutableStateFlow<List<ManagedTerminalTab>>(emptyList())
    val tabs: StateFlow<List<ManagedTerminalTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    /** serverId -> most recent commands (oldest first); recorded only when the pref allows. */
    private val _commandHistory = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val commandHistory: StateFlow<Map<String, List<String>>> = _commandHistory.asStateFlow()

    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors.asStateFlow()

    @kotlin.concurrent.Volatile
    private var latestPreferences: UserPreferences = UserPreferences()

    init {
        scope.launch {
            preferences.collect { latestPreferences = it }
        }
        // The notifier owns the foreground-service lifecycle: every session-count change
        // starts/updates the ongoing notification; the last disconnect stops the service.
        // The StateFlow's initial empty value is skipped — there is no service to stop
        // before the first session ever opens.
        scope.launch {
            var hasSeenSessions = false
            _tabs.collect { tabList ->
                if (tabList.isEmpty() && !hasSeenSessions) return@collect
                hasSeenSessions = hasSeenSessions || tabList.isNotEmpty()
                runCatching {
                    if (tabList.isEmpty()) {
                        notifier?.clearSessionNotification()
                    } else {
                        notifier?.updateSessionNotification(
                            tabList.size,
                            tabList.map { it.server.name }.distinct(),
                        )
                    }
                }.onFailure { Logger.w(it) { "SSH session notifier failed" } }
            }
        }
    }

    /**
     * Opens a shell for [server] (reusing an existing tab for the same node). Sends the
     * tmux attach command first when the pref is on, then [initialCommand]. Nothing here
     * is tied to the terminal ViewModel's lifecycle: leaving the SSH tab afterwards keeps
     * the shell open.
     */
    fun openSession(server: Server, initialCommand: String? = null) {
        scope.launch {
            tabMutex.withLock {
                _tabs.value.firstOrNull { it.server.id == server.id }?.let { existing ->
                    _activeTabId.value = existing.tabId
                    return@withLock
                }
                val prefs = latestPreferences
                val shell = try {
                    shellOpener.open(server, DEFAULT_COLS, DEFAULT_ROWS)
                } catch (err: Exception) {
                    Logger.w(err) { "Interactive shell failed for ${server.name}" }
                    _errors.value = "SSH Shell failed: ${err.message}"
                    return@withLock
                }
                val handle = handleFactory.create(shell, prefs.terminalAppearance)
                val tabId = "tab-${Random.nextInt(10_000, 99_999)}"
                val tab = ManagedTerminalTab(
                    tabId = tabId,
                    server = server,
                    title = server.name.substringBefore('.'),
                    handle = handle,
                    state = TerminalConnectionState.CONNECTED,
                )
                runtimes[tabId] = TabRuntime(handle = handle, shell = shell)
                _tabs.update { it + tab }
                _activeTabId.value = tabId
                if (prefs.tmuxAutoAttach) {
                    handle.sendText(tmuxAttachCommand() + "\n")
                }
                if (!initialCommand.isNullOrBlank()) {
                    sendText(tabId, initialCommand)
                }
                scheduleWatcher(tabId)
            }
        }
    }

    fun selectTab(tabId: String) {
        _activeTabId.value = tabId
    }

    /** Explicit Disconnect: closes the shell channel and drops the tab; idempotent. */
    fun disconnect(tabId: String) {
        scope.launch {
            tabMutex.withLock {
                val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return@withLock
                closeTabLocked(tab)
            }
        }
    }

    /** Closes every session — the foreground service's Disconnect action and app teardown. */
    fun disconnectAll() {
        scope.launch {
            tabMutex.withLock {
                _tabs.value.forEach { closeTabLocked(it) }
            }
        }
    }

    /** Manual retry from the Reconnect banner: a fresh full attempt cycle. */
    fun reconnect(tabId: String) {
        scope.launch {
            val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return@launch
            if (tab.state != TerminalConnectionState.DISCONNECTED) return@launch
            runReconnectCycle(tab.tabId, tab.server.name)
        }
    }

    /**
     * Sends [text] to a tab, recording it in the per-server history when
     * `recordTerminalCommands` is on (Phase 7 item 4). Only newline-terminated input
     * counts as a command; character-level typing flows through the emulator, not here.
     */
    fun sendText(tabId: String, text: String) {
        scope.launch {
            val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return@launch
            tab.handle.sendText(text)
            if (latestPreferences.recordTerminalCommands && text.endsWith('\n')) {
                val command = text.trim()
                if (command.isNotEmpty()) {
                    _commandHistory.update { current ->
                        val updated = (current[tab.server.id].orEmpty() + command)
                            .takeLast(maxHistoryPerServer)
                        current + (tab.server.id to updated)
                    }
                }
            }
        }
    }

    fun sendExtraKey(tabId: String, key: ExtraKeyCode) {
        scope.launch {
            _tabs.value.firstOrNull { it.tabId == tabId }?.handle?.sendExtraKey(key)
        }
    }

    /** Clipboard paste routed through the emulator's `pasteText` (bracketed-paste aware). */
    fun pasteText(tabId: String, text: String) {
        scope.launch {
            _tabs.value.firstOrNull { it.tabId == tabId }?.handle?.pasteText(text)
        }
    }

    fun clearError() {
        _errors.value = null
    }

    /** App teardown / tests: closes every session and stops the manager scope. */
    fun shutdown() {
        val drained = scope.launch {
            tabMutex.withLock {
                _tabs.value.forEach { closeTabLocked(it) }
            }
        }
        drained.invokeOnCompletion { supervisorJob.cancel() }
    }

    private suspend fun closeTabLocked(tab: ManagedTerminalTab) {
        runtimes.remove(tab.tabId)?.watcherJob?.cancel()
        runCatching { tab.handle.close() }
            .onFailure { Logger.w(it) { "Failed to close terminal tab ${tab.tabId}" } }
        _tabs.update { current -> current.filterNot { it.tabId == tab.tabId } }
        if (_activeTabId.value == tab.tabId) {
            _activeTabId.value = _tabs.value.lastOrNull()?.tabId
        }
    }

    private fun scheduleWatcher(tabId: String) {
        val runtime = runtimes[tabId] ?: return
        val serverName = _tabs.value.firstOrNull { it.tabId == tabId }?.server?.name ?: return
        runtime.watcherJob = scope.launch {
            // Fires when the shell dies (network drop, server reboot); a user Disconnect
            // cancels this job first, so it never races a deliberate close.
            runtime.shell.isConnected.first { connected -> !connected }
            runReconnectCycle(tabId, serverName)
        }
    }

    private suspend fun runReconnectCycle(tabId: String, serverName: String) {
        val runtime = runtimes[tabId] ?: return
        val server = _tabs.value.firstOrNull { it.tabId == tabId }?.server ?: return
        updateTab(tabId) { it.with(state = TerminalConnectionState.RECONNECTING, reconnectAttempt = 0) }
        for (attempt in 1..TerminalReconnectPolicy.MAX_ATTEMPTS) {
            updateTab(tabId) { it.with(reconnectAttempt = attempt) }
            delay(TerminalReconnectPolicy.delayBeforeAttemptMs(attempt))
            val opened = runCatching { shellOpener.open(server, DEFAULT_COLS, DEFAULT_ROWS) }
                .onFailure { Logger.d(it) { "Reconnect attempt $attempt failed for $serverName" } }
                .getOrNull()
            if (opened != null && opened.isConnected.value) {
                runCatching { runtime.handle.reattach(opened) }
                    .onFailure { Logger.w(it) { "Reattach failed for $serverName" } }
                runtime.shell = opened
                updateTab(tabId) { it.with(state = TerminalConnectionState.CONNECTED, reconnectAttempt = null) }
                scheduleWatcher(tabId)
                return
            }
            opened?.let { dead -> runCatching { dead.close() } }
        }
        updateTab(tabId) { it.with(state = TerminalConnectionState.DISCONNECTED, reconnectAttempt = null) }
        runCatching { notifier?.notifySessionLost(serverName) }
    }

    private fun updateTab(tabId: String, transform: (ManagedTerminalTab) -> ManagedTerminalTab) {
        _tabs.update { current ->
            current.map { if (it.tabId == tabId) transform(it) else it }
        }
    }

    private companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COMMAND_HISTORY_SIZE = 50
    }
}
