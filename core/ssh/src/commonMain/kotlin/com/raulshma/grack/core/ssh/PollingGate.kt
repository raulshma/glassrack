package com.raulshma.grack.core.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Pause gate for the controller's per-server polling loops. Pause reasons are tracked as a
 * set so independent owners (app foreground/background, metered network) can pause and
 * resume without stomping on each other: polling stays suspended until the last reason is
 * cleared. One-shot commands ([HomelabController.pollServerOnce]) bypass the gate — only
 * the continuous loops check it.
 */
class PollingGate {

    private val _pausedReasons = MutableStateFlow<Set<String>>(emptySet())

    /** Currently active pause reasons; polling is suspended while this is non-empty. */
    val pausedReasons: StateFlow<Set<String>> = _pausedReasons.asStateFlow()

    val isPaused: Boolean
        get() = _pausedReasons.value.isNotEmpty()

    /** Suspends the caller until every pause reason has been cleared. */
    suspend fun awaitOpen() {
        _pausedReasons.first { it.isEmpty() }
    }

    fun pause(reason: String) {
        _pausedReasons.update { it + reason }
    }

    fun resume(reason: String) {
        _pausedReasons.update { it - reason }
    }
}
