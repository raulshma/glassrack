package com.raulshma.grack.core.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raulshma.grack.core.model.TerminalAppearance
import com.raulshma.grack.core.ssh.SshShellSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

actual class TerminalController actual constructor(
    shellSession: SshShellSession,
    private var appearance: TerminalAppearance,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    /** Swapped by [reattach] on reconnect; the transcript buffer survives. */
    private var shellSession: SshShellSession = shellSession
    private var outputJob: Job? = null

    init {
        collectOutput(shellSession)
    }

    private fun collectOutput(session: SshShellSession) {
        outputJob?.cancel()
        outputJob = scope.launch {
            session.outputFlow.collect { bytes ->
                _transcript.value = (_transcript.value + bytes.decodeToString()).takeLast(16_000)
            }
        }
    }

    actual fun reattach(shellSession: SshShellSession) {
        this.shellSession = shellSession
        collectOutput(shellSession)
    }

    actual fun sendExtraKey(key: ExtraKeyCode) {
        val seq = when (key) {
            ExtraKeyCode.ESC -> "\u001B"
            ExtraKeyCode.TAB -> "\t"
            ExtraKeyCode.UP -> "\u001B[A"
            ExtraKeyCode.DOWN -> "\u001B[B"
            ExtraKeyCode.RIGHT -> "\u001B[C"
            ExtraKeyCode.LEFT -> "\u001B[D"
            ExtraKeyCode.CTRL_C -> "\u0003"
            ExtraKeyCode.CTRL_D -> "\u0004"
            ExtraKeyCode.PIPE -> "|"
            ExtraKeyCode.TILDE -> "~"
            ExtraKeyCode.SLASH -> "/"
            ExtraKeyCode.DASH -> "-"
            ExtraKeyCode.COLON -> ":"
            else -> ""
        }
        if (seq.isNotEmpty()) sendText(seq)
    }

    actual fun sendText(text: String) {
        scope.launch { shellSession.sendBytes(text.encodeToByteArray()) }
    }

    actual fun pasteText(text: String) = sendText(text)

    actual fun clearScreen() {
        _transcript.value = ""
    }

    actual fun updateAppearance(appearance: TerminalAppearance) {
        this.appearance = appearance
    }

    actual fun close() {
        scope.launch { shellSession.close() }
    }
}

@Composable
actual fun TerminalSurface(
    controller: TerminalController,
    appearance: TerminalAppearance,
    modifier: Modifier,
    onResize: (cols: Int, rows: Int) -> Unit,
) {
    val text by controller.transcript.collectAsState()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(appearance.themePreset.backgroundArgb))
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SelectionContainer {
            Text(
                text = text,
                color = Color(appearance.themePreset.foregroundArgb),
                fontFamily = FontFamily.Monospace,
                fontSize = appearance.fontSizeSp.sp,
            )
        }
    }
}
