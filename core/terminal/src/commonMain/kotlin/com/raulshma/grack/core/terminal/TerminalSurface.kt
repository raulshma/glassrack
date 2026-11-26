package com.raulshma.grack.core.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.raulshma.grack.core.model.TerminalAppearance
import com.raulshma.grack.core.ssh.SshShellSession

enum class ExtraKeyCode {
    ESC,
    TAB,
    CTRL,
    ALT,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
    CTRL_C,
    CTRL_D,
    PIPE,
    TILDE,
    SLASH,
    DASH,
    COLON,
}

expect class TerminalController(shellSession: SshShellSession, appearance: TerminalAppearance) {
    fun sendExtraKey(key: ExtraKeyCode)
    fun sendText(text: String)
    fun pasteText(text: String)
    /**
     * Swaps the underlying shell without touching the emulator, so scrollback and screen
     * state survive a reconnect (Phase 7): output is collected from [shellSession] from
     * now on and keyboard input is written to it.
     */
    fun reattach(shellSession: SshShellSession)
    fun clearScreen()
    fun updateAppearance(appearance: TerminalAppearance)
    fun close()
}

@Composable
expect fun TerminalSurface(
    controller: TerminalController,
    appearance: TerminalAppearance,
    modifier: Modifier = Modifier,
    onResize: (cols: Int, rows: Int) -> Unit = { _, _ -> },
)
