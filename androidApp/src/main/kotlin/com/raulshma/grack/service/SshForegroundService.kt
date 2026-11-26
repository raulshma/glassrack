package com.raulshma.grack.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.raulshma.grack.GlassrackApplication
import com.raulshma.grack.MainActivity
import com.raulshma.grack.R
import com.raulshma.grack.core.terminal.SshSessionNotifier
import com.raulshma.grack.core.terminal.TerminalSessionManager
import org.koin.core.context.GlobalContext

/**
 * Foreground service for live interactive SSH sessions (Phase 7). Runs as
 * `connectedDevice` (manifest + FOREGROUND_SERVICE_CONNECTED_DEVICE + CHANGE_NETWORK_STATE
 * eligibility): unlike the previous `dataSync` type it has no 6h/24h Android 15+ runtime
 * cap. [onTimeout] is still implemented defensively — the system calls it for timed
 * foreground-service types, and a service that fails to stop within a few seconds is
 * killed with `ForegroundServiceDidNotStopInTimeException`.
 *
 * Lifecycle: started by AndroidSshSessionNotifier whenever the session manager's tab set
 * becomes non-empty; the notification's Disconnect action closes every session through
 * the manager, whose empty-tab callback stops this service again.
 */
class SshForegroundService : Service() {

    /** Last known session names, so onTimeout can report what was interrupted. */
    private var lastNodeNames: List<String> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT_ALL -> {
                sessionManager()?.disconnectAll()
                // The manager's empty-tab callback clears the notification (ACTION_STOP).
                return START_NOT_STICKY
            }
        }

        val sessionCount = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        val nodeNames = intent?.getStringArrayExtra(EXTRA_NODE_NAMES)?.toList().orEmpty()
        lastNodeNames = nodeNames.ifEmpty { listOf("$sessionCount session(s)") }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_DEST, "terminal")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SshForegroundService::class.java).setAction(ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val summary = when {
            nodeNames.isEmpty() -> "$sessionCount active SSH session(s) — Tap to resume"
            else -> nodeNames.joinToString(", ") { it.substringBefore('.') } + " — Tap to resume"
        }

        val notification = NotificationCompat.Builder(this, GlassrackApplication.CHANNEL_SSH_SERVICE)
            .setSmallIcon(R.drawable.ic_tile_terminal)
            .setContentTitle("GRack SSH Active")
            .setContentText(summary)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Disconnect", disconnectIntent)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        return START_STICKY
    }

    /**
     * Android 15+ (API 35) calls this when a timed foreground-service type exhausts its
     * budget (`dataSync` did; `connectedDevice` has no cap). Stop cleanly within the
     * grace window and tell the user why the sessions stopped — ignoring the callback
     * raises `ForegroundServiceDidNotStopInTimeException`.
     */
    override fun onTimeout(startId: Int) {
        runCatching {
            sshSessionNotifier()?.notifyServiceTimedOut(lastNodeNames)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sessionManager(): TerminalSessionManager? =
        runCatching { GlobalContext.get().get<TerminalSessionManager>() }.getOrNull()

    private fun sshSessionNotifier(): SshSessionNotifier? =
        runCatching { GlobalContext.get().get<SshSessionNotifier>() }.getOrNull()

    companion object {
        const val ACTION_STOP = "com.raulshma.grack.STOP_SSH_FG"
        const val ACTION_DISCONNECT_ALL = "com.raulshma.grack.DISCONNECT_ALL_SSH_FG"
        const val EXTRA_COUNT = "session_count"
        const val EXTRA_NODE_NAMES = "session_node_names"
        const val NOTIFICATION_ID = 4401
    }
}
