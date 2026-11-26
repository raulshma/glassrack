package com.raulshma.grack.polling

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.ssh.HomelabController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Polling hygiene wiring (Phase 1C item 1):
 * - pauses the controller's continuous poll loops while the app is backgrounded
 *   ([ProcessLifecycleOwner]; a cold start in the background stays paused until the
 *   first activity starts), and
 * - pauses them on metered networks whenever [com.raulshma.grack.core.model.UserPreferences]
 *   `pausePollingOnMetered` is set.
 *
 * The WorkManager telemetry worker is intentionally unaffected: it drives the 15-minute
 * background refresh with its own constraints. [onMeteredPrefChanged] lets the app
 * re-enqueue that worker with an UNMETERED constraint when the preference flips.
 */
class PollingPauseCoordinator(
    private val context: Context,
    private val controller: HomelabController,
    private val preferences: Flow<UserPreferences>,
    private val scope: CoroutineScope,
    private val onMeteredPrefChanged: (pauseOnMetered: Boolean) -> Unit,
) {
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var pauseOnMetered: Boolean = false

    @Volatile
    private var networkIsMetered: Boolean = false

    fun start() {
        observeProcessLifecycle()
        observeNetworkMeteredState()
        observePreferences()
    }

    private fun observeProcessLifecycle() {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        // A process started in the background (e.g. by the worker) has not received
        // ON_START yet: begin paused so the 5-second loops never free-run off-foreground.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            controller.pollingGate.pause(Gate.REASON_BACKGROUND)
        }
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    controller.pollingGate.resume(Gate.REASON_BACKGROUND)
                }

                override fun onStop(owner: LifecycleOwner) {
                    controller.pollingGate.pause(Gate.REASON_BACKGROUND)
                }
            },
        )
    }

    private fun observeNetworkMeteredState() {
        val manager = connectivityManager ?: return
        networkIsMetered = manager.activeNetwork?.let { active ->
            manager.getNetworkCapabilities(active)?.let { caps ->
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            } ?: true
        } ?: false
        reevaluateMetered()
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    networkIsMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    reevaluateMetered()
                }

                override fun onLost(network: Network) {
                    // Conservative default: assume metered until the next capability update.
                    networkIsMetered = true
                    reevaluateMetered()
                }
            },
        )
    }

    private fun observePreferences() {
        scope.launch {
            preferences
                .map { it.pausePollingOnMetered }
                .distinctUntilChanged()
                .collect { pref ->
                    pauseOnMetered = pref
                    reevaluateMetered()
                    onMeteredPrefChanged(pref)
                }
        }
    }

    private fun reevaluateMetered() {
        if (pauseOnMetered && networkIsMetered) {
            controller.pollingGate.pause(Gate.REASON_METERED)
        } else {
            controller.pollingGate.resume(Gate.REASON_METERED)
        }
    }

    private object Gate {
        const val REASON_BACKGROUND = "background"
        const val REASON_METERED = "metered"
    }
}
