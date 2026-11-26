package com.raulshma.grack

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.raulshma.grack.core.security.ActivityHolder

class MainActivity : FragmentActivity() {

    private var deepLinkDest by mutableStateOf<String?>(null)
    private var deepLinkServerId by mutableStateOf<String?>(null)
    private var deepLinkQuery by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* user decision is surfaced later via Settings' notification row */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)
        parseIntent(intent)
        requestNotificationPermissionOnce()

        setContent {
            val ctx = LocalContext.current
            val systemDark = isSystemInDarkTheme()
            var isAppDark by remember(systemDark) { mutableStateOf(systemDark) }
            val view = LocalView.current

            if (!view.isInEditMode) {
                SideEffect {
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !isAppDark
                    controller.isAppearanceLightNavigationBars = !isAppDark
                }
            }

            val dynamicPrimary = if (isAppDark) {
                dynamicDarkColorScheme(ctx).primary
            } else {
                dynamicLightColorScheme(ctx).primary
            }

            App(
                deepLinkServerId = deepLinkServerId,
                deepLinkDestination = deepLinkDest,
                deepLinkQuery = deepLinkQuery,
                dynamicSeedColor = dynamicPrimary,
                onConsumeDeepLink = {
                    deepLinkDest = null
                    deepLinkServerId = null
                    deepLinkQuery = null
                },
                onDarkThemeChanged = { isAppDark = it },
            )
        }
    }

    /** Requests POST_NOTIFICATIONS exactly once per install; Settings offers a manual retry row. */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ActivityHolder.set(this)
    }

    override fun onPause() {
        ActivityHolder.set(null)
        super.onPause()
    }

    private fun parseIntent(intent: Intent?) {
        deepLinkDest = intent?.getStringExtra(EXTRA_DEST)
        deepLinkServerId = intent?.getStringExtra(EXTRA_SERVER_ID)
        deepLinkQuery = intent?.getStringExtra(EXTRA_QUERY)
    }

    companion object {
        const val EXTRA_DEST = "glassrack_dest"
        const val EXTRA_SERVER_ID = "glassrack_server_id"
        /** Free-text filter (audit search) accompanying an [EXTRA_DEST] deep link. */
        const val EXTRA_QUERY = "glassrack_query"
        private const val PREFERENCES = "glassrack_main"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
    }
}
