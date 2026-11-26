package com.raulshma.grack

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.raulshma.grack.di.initGlassrackKoin

@Composable
fun App(
    deepLinkServerId: String? = null,
    deepLinkDestination: String? = null,
    deepLinkQuery: String? = null,
    /** Destructive widget tap (Phase 8): container name + action name staged for in-app confirmation. */
    deepLinkPendingContainer: String? = null,
    deepLinkPendingAction: String? = null,
    dynamicSeedColor: Color? = null,
    onConsumeDeepLink: () -> Unit = {},
    onDarkThemeChanged: (Boolean) -> Unit = {},
) {
    initGlassrackKoin()
    GlassrackApp(
        deepLinkServerId = deepLinkServerId,
        deepLinkDestination = deepLinkDestination,
        deepLinkQuery = deepLinkQuery,
        deepLinkPendingContainer = deepLinkPendingContainer,
        deepLinkPendingAction = deepLinkPendingAction,
        dynamicSeedColor = dynamicSeedColor,
        onConsumeDeepLink = onConsumeDeepLink,
        onDarkThemeChanged = onDarkThemeChanged,
    )
}
