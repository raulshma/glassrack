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
import com.raulshma.grack.core.ssh.AutoUpdateRunSummary

/**
 * Posts the AUTO update summary notification after an unattended run did something worth
 * reporting (applied updates, or an enabled apply failed) — mirroring the
 * [AndroidAlertNotifier] patterns: permission-checked, deep-linked into the audit ledger
 * filtered by the node, silent when there is nothing to report.
 */
object AutoUpdateNotifier {

    /** Notification ids live in a dedicated range so they never collide with alert ids. */
    private const val ID_PREFIX = "grack-auto-update-"

    fun notificationId(serverId: String): Int = (ID_PREFIX + serverId).hashCode()

    /**
     * Posts when the run applied something or failed; "no updates were pending" stays
     * silent — unattended runs that did nothing must not nag.
     */
    fun postIfNeeded(context: Context, summary: AutoUpdateRunSummary) {
        if (!summary.appliedAnything && !summary.failedAnything) return
        post(context, summary)
    }

    private fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun deepLinkToAudit(context: Context, serverName: String): PendingIntent = PendingIntent.getActivity(
        context,
        serverName.hashCode(),
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_DEST, "audit")
            putExtra(MainActivity.EXTRA_QUERY, serverName)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(context: Context, summary: AutoUpdateRunSummary) {
        if (!canPost(context)) return
        val title = if (summary.failedAnything) {
            "GRack auto-update issues · ${summary.serverName}"
        } else {
            "GRack auto-update · ${summary.serverName}"
        }
        val text = summary.toNotificationText()
        val builder = NotificationCompat.Builder(context, GlassrackApplication.CHANNEL_SCHEDULED)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(deepLinkToAudit(context, summary.serverName))
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(summary.serverId), builder.build())
        }
    }
}
