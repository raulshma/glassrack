package com.raulshma.grack.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.raulshma.grack.GlassrackApplication
import com.raulshma.grack.MainActivity
import com.raulshma.grack.core.terminal.SshSessionNotifier
import com.raulshma.grack.service.SshForegroundService

/**
 * Android implementation of [SshSessionNotifier] (Phase 7): owns the foreground-service
 * lifecycle for live terminal sessions (start/update via [SshForegroundService], stop when
 * the last session ends) and posts one-shot heads-ups when a session is lost or the system
 * times the service out. Follows the [AndroidAlertNotifier] pattern.
 */
class AndroidSshSessionNotifier(private val context: Context) : SshSessionNotifier {

    override fun updateSessionNotification(count: Int, nodeNames: List<String>) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SshForegroundService::class.java)
                    .putExtra(SshForegroundService.EXTRA_COUNT, count)
                    .putExtra(SshForegroundService.EXTRA_NODE_NAMES, nodeNames.toTypedArray()),
            )
        }.onFailure {
            // Foreground-service start restrictions (e.g. background start) must never
            // crash the session path; the terminal UI still works without the service.
        }
    }

    override fun clearSessionNotification() {
        runCatching {
            // The service is foreground at this point; a plain startService delivers the stop.
            context.startService(
                Intent(context, SshForegroundService::class.java)
                    .setAction(SshForegroundService.ACTION_STOP),
            )
        }
    }

    override fun notifySessionLost(serverName: String) {
        post(
            notificationId = ("session-lost-$serverName").hashCode(),
            title = "GRack · ${serverName.substringBefore('.')} disconnected",
            text = "All reconnect attempts failed. Open the SSH tab to reconnect.",
            ongoing = false,
        )
    }

    override fun notifyServiceTimedOut(nodeNames: List<String>) {
        post(
            notificationId = SERVICE_TIMED_OUT_ID,
            title = "GRack SSH service stopped",
            text = "The system stopped the SSH session service (${nodeNames.size} session(s)). Reopen the SSH tab to reconnect.",
            ongoing = false,
        )
    }

    private fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun post(notificationId: Int, title: String, text: String, ongoing: Boolean) {
        if (!canPost()) return
        val openIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_DEST, "terminal")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, GlassrackApplication.CHANNEL_SSH_SERVICE)
            .setSmallIcon(com.raulshma.grack.R.drawable.ic_tile_terminal)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setContentIntent(openIntent)
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }
    }

    private companion object {
        val SERVICE_TIMED_OUT_ID: Int = "ssh-service-timed-out".hashCode()
    }
}
