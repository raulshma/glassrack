package com.raulshma.grack

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.raulshma.grack.di.initGlassrackKoin

@Composable
fun App(
    deepLinkServerId: String? = null,
    deepLinkDestination: String? = null,
    dynamicSeedColor: Color? = null,
    onConsumeDeepLink: () -> Unit = {},
    onDarkThemeChanged: (Boolean) -> Unit = {},
) {
    initGlassrackKoin()
    GlassrackApp(
        deepLinkServerId = deepLinkServerId,
        deepLinkDestination = deepLinkDestination,
        dynamicSeedColor = dynamicSeedColor,
        onConsumeDeepLink = onConsumeDeepLink,
        onDarkThemeChanged = onDarkThemeChanged,
    )
}
