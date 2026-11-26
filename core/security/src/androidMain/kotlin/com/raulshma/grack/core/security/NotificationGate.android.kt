package com.raulshma.grack.core.security

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

actual class NotificationGate(private val context: Context) {
    actual fun areNotificationsEnabled(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled()
    }

    actual fun openNotificationSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
    }
}
