package com.raulshma.grack.core.security

/**
 * Platform window into the OS notification permission state. On Android the permission is
 * requested once from MainActivity; Settings uses this gate to surface a row when the user
 * denied it, with a tap-through to the system notification settings.
 */
expect class NotificationGate {
    fun areNotificationsEnabled(): Boolean

    /** Opens the platform screen where notification permission can be granted. */
    fun openNotificationSettings()
}
