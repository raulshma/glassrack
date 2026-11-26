package com.raulshma.grack.core.security

actual class NotificationGate {
    actual fun areNotificationsEnabled(): Boolean = true

    actual fun openNotificationSettings() = Unit
}
