package com.raulshma.grack.core.terminal

import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.ssh.SshShellSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionManagerTest {

    // ---------------------------------------------------------------- fakes

    private class FakeShellSession : SshShellSession {
        override val outputFlow: Flow<ByteArray> = emptyFlow()
        private val _connected = MutableStateFlow(true)
        override val isConnected: StateFlow<Boolean> = _connected.asStateFlow()
        var closed = false
            private set

        fun dropConnection() {
            _connected.value = false
        }

        override suspend fun sendBytes(bytes: ByteArray) = Unit

        override suspend fun resizePty(cols: Int, rows: Int) = Unit

        override suspend fun close() {
            _connected.value = false
            closed = true
        }
    }

    /** The manager never reads [controller]; that getter explodes if it ever does. */
    private class RecordingHandle(private val shell: SshShellSession) : TerminalTabHandle {
        override val controller: TerminalController
            get() = error("TerminalController is never rendered in tests")

        val sentTexts = mutableListOf<String>()
        val reattachedTo = mutableListOf<SshShellSession>()
        var closed = false
            private set

        override fun sendText(text: String) {
            sentTexts += text
        }

        override fun sendExtraKey(key: ExtraKeyCode) = Unit

        override fun pasteText(text: String) = Unit

        override fun reattach(shell: SshShellSession) {
            reattachedTo += shell
        }

        override fun close() {
            closed = true
            // Mirror DefaultTerminalTabHandle: closing the handle closes the underlying
            // shell (the fake's close is instantaneous, so a blocking call is safe here).
            runBlocking { shell.close() }
        }
    }

    private class ScriptableOpener : TerminalShellOpener {
        val openedServers = mutableListOf<Server>()
        val shells = mutableListOf<FakeShellSession>()

        /** Default: every open succeeds with a fresh connected shell. */
        var handler: suspend (Server) -> SshShellSession = { _ ->
            FakeShellSession().also { shells += it }
        }

        override suspend fun open(server: Server, cols: Int, rows: Int): SshShellSession {
            openedServers += server
            return handler(server)
        }
    }

    private class RecordingNotifier : SshSessionNotifier {
        data class Event(val kind: Kind, val count: Int? = null, val names: List<String> = emptyList())

        enum class Kind { UPDATE, CLEAR, LOST, TIMED_OUT }

        val events = mutableListOf<Event>()

        override fun updateSessionNotification(count: Int, nodeNames: List<String>) {
            events += Event(Kind.UPDATE, count, nodeNames)
        }

        override fun clearSessionNotification() {
            events += Event(Kind.CLEAR)
        }

        override fun notifySessionLost(serverName: String) {
            events += Event(Kind.LOST, names = listOf(serverName))
        }

        override fun notifyServiceTimedOut(nodeNames: List<String>) {
            events += Event(Kind.TIMED_OUT, names = nodeNames)
        }
    }

    private class Harness(testScope: TestScope, initialPrefs: UserPreferences = UserPreferences()) {
        val preferences = MutableStateFlow(initialPrefs)
        val opener = ScriptableOpener()
        val notifier = RecordingNotifier()
        val handles = mutableListOf<RecordingHandle>()
        val server = Server(id = "srv-1", name = "node1.example.com", primaryHost = "10.0.0.1")

        val manager = TerminalSessionManager(
            shellOpener = opener,
            handleFactory = TerminalTabHandleFactory { shell, _ ->
                RecordingHandle(shell).also { handles += it }
            },
            preferences = preferences,
            notifier = notifier,
            // Standalone supervisor on the test scheduler: virtual time drives the manager,
            // but runTest does not await its long-lived watcher coroutines.
            backgroundDispatcher = testScope.coroutineContext + SupervisorJob(),
        )
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun tmuxCommandConstructionUsesGuardedAttachOrCreate() {
        val command = tmuxAttachCommand()
        // Missing tmux must not spam the prompt: guard with `command -v`, silently skip.
        assertTrue(command.startsWith("command -v tmux"))
        assertTrue(command.endsWith("|| true"))
        // `tmux new -A` = attach to the session or create it (`tmux attach || tmux new` style).
        assertTrue(command.contains("tmux new -A -s grack"))
    }

    @Test
    fun reconnectPolicyRunsThreeAttemptsWithExponentialBackoff() {
        assertEquals(3, TerminalReconnectPolicy.MAX_ATTEMPTS)
        assertEquals(1_000L, TerminalReconnectPolicy.delayBeforeAttemptMs(1))
        assertEquals(2_000L, TerminalReconnectPolicy.delayBeforeAttemptMs(2))
        assertEquals(4_000L, TerminalReconnectPolicy.delayBeforeAttemptMs(3))
    }

    @Test
    fun sessionSurvivesLeavingTabAndDisconnectClosesIt() = runTest {
        val h = Harness(this)
        h.manager.openSession(h.server)
        runCurrent()

        // The ViewModel would be destroyed on leaving the SSH tab here; ownership lives in
        // the manager, so the session is untouched.
        runCurrent()
        assertEquals(1, h.manager.tabs.value.size)
        assertEquals(TerminalConnectionState.CONNECTED, h.manager.tabs.value.single().state)
        val handle = h.handles.single()
        val shell = h.opener.shells.single()
        assertFalse(handle.closed)
        assertFalse(shell.closed)

        // Re-opening the same node activates the existing tab instead of a second shell.
        h.manager.openSession(h.server)
        runCurrent()
        assertEquals(1, h.manager.tabs.value.size)
        assertEquals(1, h.opener.openedServers.size)

        // Explicit Disconnect: handle + shell close (which de-registers the shell from the
        // transport's openShellsByServer, so disconnectIdle prunes the pool again).
        h.manager.disconnect(h.manager.tabs.value.single().tabId)
        runCurrent()

        assertEquals(0, h.manager.tabs.value.size)
        assertTrue(handle.closed)
        assertTrue(shell.closed)

        // Notifier contract: session count up, then service stopped when empty.
        assertEquals(
            listOf(
                RecordingNotifier.Event(RecordingNotifier.Kind.UPDATE, 1, listOf("node1.example.com")),
                RecordingNotifier.Event(RecordingNotifier.Kind.CLEAR),
            ),
            h.notifier.events,
        )
        h.manager.shutdown()
    }

    @Test
    fun reconnectMakesThreeBackoffAttemptsThenSurfacesBannerAndManualRetrySucceeds() = runTest {
        val h = Harness(this)
        h.manager.openSession(h.server)
        runCurrent()
        val tabId = h.manager.tabs.value.single().tabId
        val handle = h.handles.single()

        h.opener.handler = { _ -> error("node unreachable") }
        h.opener.shells.single().dropConnection()

        // The watcher starts the cycle and parks on the first backoff delay.
        runCurrent()
        assertEquals(TerminalConnectionState.RECONNECTING, h.manager.tabs.value.single().state)
        assertEquals(1, h.manager.tabs.value.single().reconnectAttempt)
        assertEquals(1, h.opener.openedServers.size) // initial open only, attempt not yet run

        advanceTimeBy(TerminalReconnectPolicy.delayBeforeAttemptMs(1))
        runCurrent()
        assertEquals(2, h.opener.openedServers.size) // + attempt 1 (failed)
        assertEquals(TerminalConnectionState.RECONNECTING, h.manager.tabs.value.single().state)
        assertEquals(2, h.manager.tabs.value.single().reconnectAttempt)

        advanceTimeBy(
            TerminalReconnectPolicy.delayBeforeAttemptMs(2) +
                TerminalReconnectPolicy.delayBeforeAttemptMs(3),
        )
        runCurrent()
        assertEquals(4, h.opener.openedServers.size) // + attempts 2 and 3 (both failed)
        assertEquals(TerminalConnectionState.DISCONNECTED, h.manager.tabs.value.single().state)
        assertEquals(null, h.manager.tabs.value.single().reconnectAttempt)
        assertEquals(
            listOf(RecordingNotifier.Event(RecordingNotifier.Kind.LOST, names = listOf("node1.example.com"))),
            h.notifier.events.filter { it.kind == RecordingNotifier.Kind.LOST },
        )

        // Manual retry from the banner against a reachable node restores the session on the
        // same emulator handle (reattach), preserving scrollback.
        h.opener.handler = { _ -> FakeShellSession().also { h.opener.shells += it } }
        h.manager.reconnect(tabId)
        runCurrent()
        advanceTimeBy(TerminalReconnectPolicy.delayBeforeAttemptMs(1))
        runCurrent()

        assertEquals(TerminalConnectionState.CONNECTED, h.manager.tabs.value.single().state)
        assertEquals(5, h.opener.openedServers.size)
        assertEquals(h.opener.shells.last(), handle.reattachedTo.single())
        h.manager.shutdown()
    }

    @Test
    fun reconnectSucceedsMidCycleAndSkipsRemainingAttempts() = runTest {
        val h = Harness(this)
        h.manager.openSession(h.server)
        runCurrent()

        var attempts = 0
        h.opener.handler = { _ ->
            attempts += 1
            if (attempts <= 1) error("still unreachable")
            FakeShellSession().also { h.opener.shells += it }
        }
        h.opener.shells.single().dropConnection()

        advanceTimeBy(
            TerminalReconnectPolicy.delayBeforeAttemptMs(1) +
                TerminalReconnectPolicy.delayBeforeAttemptMs(2),
        )
        runCurrent()

        // Attempt 1 (after 1s) fails; attempt 2 (after 2s) succeeds.
        assertEquals(TerminalConnectionState.CONNECTED, h.manager.tabs.value.single().state)
        assertEquals(3, h.opener.openedServers.size) // initial + 2 attempts

        // Attempt 3 must never run after the cycle succeeded.
        advanceTimeBy(TerminalReconnectPolicy.delayBeforeAttemptMs(3) * 2)
        runCurrent()
        assertEquals(3, h.opener.openedServers.size)
        assertEquals(0, h.notifier.events.count { it.kind == RecordingNotifier.Kind.LOST })
        h.manager.shutdown()
    }

    @Test
    fun manualReconnectIsIgnoredWhileConnected() = runTest {
        val h = Harness(this)
        h.manager.openSession(h.server)
        runCurrent()
        val tabId = h.manager.tabs.value.single().tabId

        h.manager.reconnect(tabId)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(1, h.opener.openedServers.size)
        assertEquals(TerminalConnectionState.CONNECTED, h.manager.tabs.value.single().state)
        h.manager.shutdown()
    }

    @Test
    fun shellDropWithoutReachableNodeLeavesOldSessionClosedStateIntact() = runTest {
        // Pins the runtime-swap contract: after a successful reconnect the manager watches
        // the NEW shell; dropping the OLD (dead) shell must not re-trigger a cycle.
        val h = Harness(this)
        h.manager.openSession(h.server)
        runCurrent()
        val oldShell = h.opener.shells.single()

        var attempts = 0
        h.opener.handler = { _ ->
            attempts += 1
            FakeShellSession().also { h.opener.shells += it }
        }
        oldShell.dropConnection()
        advanceTimeBy(TerminalReconnectPolicy.delayBeforeAttemptMs(1))
        runCurrent()

        assertEquals(TerminalConnectionState.CONNECTED, h.manager.tabs.value.single().state)
        val newShell = h.opener.shells.last()

        // Dropping the old shell again: its watcher was cancelled with the cycle done.
        oldShell.dropConnection()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(TerminalConnectionState.CONNECTED, h.manager.tabs.value.single().state)
        assertEquals(2, h.opener.openedServers.size)
        assertFalse(newShell.closed)
        h.manager.shutdown()
    }

    @Test
    fun commandHistoryIsRecordedPerServerOnlyWhenThePreferenceAllows() = runTest {
        val h = Harness(this, UserPreferences(recordTerminalCommands = true))
        h.manager.openSession(h.server)
        runCurrent()
        val tabId = h.manager.tabs.value.single().tabId

        h.manager.sendText(tabId, "ls -la\n")
        h.manager.sendText(tabId, "echo hi") // no trailing newline: not a command submit
        runCurrent()
        assertEquals(listOf("ls -la"), h.manager.commandHistory.value["srv-1"])

        h.manager.sendText(tabId, "df -h\n")
        runCurrent()
        assertEquals(listOf("ls -la", "df -h"), h.manager.commandHistory.value["srv-1"])

        // Recording disabled: history freezes.
        h.preferences.value = UserPreferences(recordTerminalCommands = false)
        runCurrent()
        h.manager.sendText(tabId, "cat /etc/shadow\n")
        runCurrent()
        assertEquals(listOf("ls -la", "df -h"), h.manager.commandHistory.value["srv-1"])
        h.manager.shutdown()
    }

    @Test
    fun commandHistoryIsCappedPerServer() = runTest {
        val h = Harness(this, UserPreferences(recordTerminalCommands = true))
        h.manager.openSession(h.server)
        runCurrent()
        val tabId = h.manager.tabs.value.single().tabId

        repeat(60) { index -> h.manager.sendText(tabId, "cmd-$index\n") }
        runCurrent()

        val history = h.manager.commandHistory.value["srv-1"].orEmpty()
        assertEquals(50, history.size)
        assertEquals("cmd-10", history.first())
        assertEquals("cmd-59", history.last())
        h.manager.shutdown()
    }

    @Test
    fun tmuxAutoAttachSendsCommandOnSessionStartAndRespectsDisabledPref() = runTest {
        val enabled = Harness(this, UserPreferences(tmuxAutoAttach = true))
        enabled.manager.openSession(enabled.server)
        runCurrent()
        assertEquals(
            listOf(tmuxAttachCommand() + "\n"),
            enabled.handles.single().sentTexts,
        )
        enabled.manager.shutdown()

        val disabled = Harness(this, UserPreferences(tmuxAutoAttach = false))
        disabled.manager.openSession(disabled.server)
        runCurrent()
        assertTrue(disabled.handles.single().sentTexts.none { it.contains("tmux") })
        disabled.manager.shutdown()
    }

    @Test
    fun notifierContractTracksSessionCountsAcrossTwoSessions() = runTest {
        val h = Harness(this)
        val second = Server(id = "srv-2", name = "node2.example.com", primaryHost = "10.0.0.2")
        h.manager.openSession(h.server)
        runCurrent()
        h.manager.openSession(second)
        runCurrent()

        // One UPDATE per tab-list change: 1 session, then 2 with distinct node names.
        assertEquals(
            listOf(
                RecordingNotifier.Event(RecordingNotifier.Kind.UPDATE, 1, listOf("node1.example.com")),
                RecordingNotifier.Event(RecordingNotifier.Kind.UPDATE, 2, listOf("node1.example.com", "node2.example.com")),
            ),
            h.notifier.events,
        )

        h.manager.disconnectAll()
        runCurrent()
        assertEquals(RecordingNotifier.Event(RecordingNotifier.Kind.CLEAR), h.notifier.events.last())
        assertTrue(h.handles.all { it.closed })
        assertEquals(null, h.manager.activeTabId.value)
        h.manager.shutdown()
    }

    @Test
    fun openFailureSurfacesErrorAndKeepsTabListEmpty() = runTest {
        val h = Harness(this)
        h.opener.handler = { _ -> error("connection refused") }
        h.manager.openSession(h.server)
        runCurrent()

        assertEquals(0, h.manager.tabs.value.size)
        assertEquals("SSH Shell failed: connection refused", h.manager.errors.value)
        assertEquals(0, h.notifier.events.size)
        h.manager.clearError()
        runCurrent()
        assertEquals(null, h.manager.errors.value)
        h.manager.shutdown()
    }
}
