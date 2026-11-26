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
import com.raulshma.grack.core.model.AppAlert
import com.raulshma.grack.core.ssh.HomelabController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Posts one notification per alert, with a deep link into the affected server; cancels the
 * notification when the alert clears and posts a "back to normal" follow-up. Replaces the
 * worker's inline last-alert-only posting.
 */
interface AlertNotifier {
    fun notifyAlert(alert: AppAlert)

    fun cancelAlert(alert: AppAlert)

    fun notifyBackToNormal(serverId: String, serverName: String)
}

class AndroidAlertNotifier(private val context: Context) : AlertNotifier {

    private fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun deepLinkToServer(serverId: String): PendingIntent = PendingIntent.getActivity(
        context,
        serverId.hashCode(),
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SERVER_ID, serverId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(
        notificationId: Int,
        title: String,
        text: String,
        serverId: String?,
        ongoing: Boolean,
    ) {
        if (!canPost()) return
        val builder = NotificationCompat.Builder(context, GlassrackApplication.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
        serverId?.let { builder.setContentIntent(deepLinkToServer(it)) }
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }
    }

    override fun notifyAlert(alert: AppAlert) {
        post(
            notificationId = alert.id.hashCode(),
            title = "GRack Alert · ${alert.serverName}",
            text = alert.message,
            serverId = alert.serverId,
            ongoing = false,
        )
    }

    override fun cancelAlert(alert: AppAlert) {
        NotificationManagerCompat.from(context).cancel(alert.id.hashCode())
    }

    override fun notifyBackToNormal(serverId: String, serverName: String) {
        post(
            notificationId = backToNormalId(serverId),
            title = "GRack · ${serverName} back to normal",
            text = "All alerts for ${serverName} have cleared.",
            serverId = serverId,
            ongoing = false,
        )
    }

    private fun backToNormalId(serverId: String): Int = ("back-to-normal-$serverId").hashCode()
}

/**
 * Watches the controller's alerts and drives the notifier so every alert transition
 * (raised or cleared) maps to exactly one notification action.
 */
class AlertNotificationCoordinator(
    private val scope: CoroutineScope,
    private val controller: HomelabController,
    private val notifier: AlertNotifier,
) {
    private var previouslyActive: Map<String, AppAlert> = emptyMap()

    fun start() {
        scope.launch {
            controller.activeAlerts.collect { alerts -> reconcile(alerts) }
        }
    }

    private fun reconcile(current: List<AppAlert>) {
        val currentById = current.associateBy { it.id }
        currentById.values
            .filter { it.id !in previouslyActive }
            .forEach { notifier.notifyAlert(it) }
        previouslyActive
            .filterKeys { id -> id !in currentById }
            .forEach { (_, cleared) ->
                notifier.cancelAlert(cleared)
                notifier.notifyBackToNormal(cleared.serverId, cleared.serverName)
            }
        previouslyActive = currentById
    }
}
