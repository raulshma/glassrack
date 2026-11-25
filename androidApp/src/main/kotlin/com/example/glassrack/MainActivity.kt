package com.example.glassrack

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.example.glassrack.core.security.ActivityHolder

class MainActivity : FragmentActivity() {

    private var deepLinkDest by mutableStateOf<String?>(null)
    private var deepLinkServerId by mutableStateOf<String?>(null)

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
                dynamicSeedColor = dynamicPrimary,
                onConsumeDeepLink = {
                    deepLinkDest = null
                    deepLinkServerId = null
                },
                onDarkThemeChanged = { isAppDark = it },
            )
        }
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
    }

    companion object {
        const val EXTRA_DEST = "glassrack_dest"
        const val EXTRA_SERVER_ID = "glassrack_server_id"
    }
}
