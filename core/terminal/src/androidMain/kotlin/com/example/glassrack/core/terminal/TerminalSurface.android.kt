package com.example.glassrack.core.terminal

import android.graphics.Typeface
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.example.glassrack.core.model.TerminalAppearance
import com.example.glassrack.core.ssh.SshShellSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import org.connectbot.terminal.VTermKey

actual class TerminalController actual constructor(
    private val shellSession: SshShellSession,
    private var appearance: TerminalAppearance,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ctrlLatched = false
    private var altLatched = false
    internal var onResizeCallback: ((Int, Int) -> Unit)? = null

    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        looper = Looper.getMainLooper(),
        initialRows = 24,
        initialCols = 80,
        defaultForeground = Color(appearance.themePreset.foregroundArgb),
        defaultBackground = Color(appearance.themePreset.backgroundArgb),
        onKeyboardInput = { bytes ->
            scope.launch { shellSession.sendBytes(bytes) }
        },
        onResize = { dims ->
            scope.launch { shellSession.resizePty(dims.columns, dims.rows) }
            onResizeCallback?.invoke(dims.columns, dims.rows)
        },
        autoDetectUrls = true,
        boldAsBright = true,
    )

    init {
        scope.launch {
            shellSession.outputFlow.collect { bytes ->
                emulator.writeInput(bytes, 0, bytes.size)
            }
        }
    }

    actual fun sendExtraKey(key: ExtraKeyCode) {
        when (key) {
            ExtraKeyCode.CTRL -> {
                ctrlLatched = !ctrlLatched
                return
            }
            ExtraKeyCode.ALT -> {
                altLatched = !altLatched
                return
            }
            ExtraKeyCode.ESC -> emulator.dispatchKey(0, VTermKey.ESCAPE)
            ExtraKeyCode.TAB -> emulator.dispatchKey(0, VTermKey.TAB)
            ExtraKeyCode.UP -> emulator.dispatchKey(0, VTermKey.UP)
            ExtraKeyCode.DOWN -> emulator.dispatchKey(0, VTermKey.DOWN)
            ExtraKeyCode.LEFT -> emulator.dispatchKey(0, VTermKey.LEFT)
            ExtraKeyCode.RIGHT -> emulator.dispatchKey(0, VTermKey.RIGHT)
            ExtraKeyCode.HOME -> emulator.dispatchKey(0, VTermKey.HOME)
            ExtraKeyCode.END -> emulator.dispatchKey(0, VTermKey.END)
            ExtraKeyCode.PAGE_UP -> emulator.dispatchKey(0, VTermKey.PAGEUP)
            ExtraKeyCode.PAGE_DOWN -> emulator.dispatchKey(0, VTermKey.PAGEDOWN)
            ExtraKeyCode.CTRL_C -> sendText("\u0003")
            ExtraKeyCode.CTRL_D -> sendText("\u0004")
            ExtraKeyCode.PIPE -> sendText("|")
            ExtraKeyCode.TILDE -> sendText("~")
            ExtraKeyCode.SLASH -> sendText("/")
            ExtraKeyCode.DASH -> sendText("-")
            ExtraKeyCode.COLON -> sendText(":")
        }
    }

    actual fun sendText(text: String) {
        val payload = if (ctrlLatched && text.length == 1) {
            ctrlLatched = false
            val ch = text[0].uppercaseChar()
            if (ch in 'A'..'Z') byteArrayOf((ch.code - 'A'.code + 1).toByte()) else text.encodeToByteArray()
        } else if (altLatched) {
            altLatched = false
            "\u001B$text".encodeToByteArray()
        } else {
            text.encodeToByteArray()
        }
        scope.launch { shellSession.sendBytes(payload) }
    }

    actual fun pasteText(text: String) {
        emulator.pasteText(text)
    }

    actual fun clearScreen() {
        emulator.clearScreen()
    }

    actual fun updateAppearance(appearance: TerminalAppearance) {
        this.appearance = appearance
        emulator.setDefaultColors(
            appearance.themePreset.foregroundArgb.toInt(),
            appearance.themePreset.backgroundArgb.toInt(),
        )
    }

    actual fun close() {
        runCatching { emulator.close() }
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
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(controller, onResize) {
        controller.onResizeCallback = onResize
    }
    LaunchedEffect(appearance) {
        controller.updateAppearance(appearance)
    }
    Terminal(
        terminalEmulator = controller.emulator,
        modifier = modifier.fillMaxSize(),
        typeface = Typeface.MONOSPACE,
        initialFontSize = appearance.fontSizeSp.sp,
        minFontSize = 9.sp,
        maxFontSize = 24.sp,
        backgroundColor = Color(appearance.themePreset.backgroundArgb),
        foregroundColor = Color(appearance.themePreset.foregroundArgb),
        keyboardEnabled = true,
        showSoftKeyboard = true,
        focusRequester = focusRequester,
    )
}
