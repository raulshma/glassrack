package com.example.glassrack.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.glassrack.core.model.DensityMode
import com.example.glassrack.core.model.ThemeVariant
import com.example.glassrack.core.model.UserPreferences
import com.materialkolor.DynamicMaterialExpressiveTheme
import com.materialkolor.PaletteStyle
import com.materialkolor.ktx.harmonize

@Immutable
data class GlassrackDesignTokens(
    val statusOk: Color,
    val statusWarn: Color,
    val statusFault: Color,
    val statusActive: Color,
    val rackSurface: Color,
    val rackSurfaceElevated: Color,
    val glassBorder: Color,
    val densityMode: DensityMode,
    val cardPadding: Dp,
    val cardSpacing: Dp,
    val isAmoled: Boolean,
)

val LocalGlassrackDesign = staticCompositionLocalOf {
    GlassrackDesignTokens(
        statusOk = Color(0xFF2DD4BF),
        statusWarn = Color(0xFFFBBF24),
        statusFault = Color(0xFFFB7185),
        statusActive = Color(0xFF38BDF8),
        rackSurface = Color(0xFF0E1420),
        rackSurfaceElevated = Color(0xFF151D2E),
        glassBorder = Color(0x26FFFFFF),
        densityMode = DensityMode.EXPRESSIVE,
        cardPadding = 16.dp,
        cardSpacing = 12.dp,
        isAmoled = false,
    )
}

private val GlassrackShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val GlassrackTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GlassrackTheme(
    preferences: UserPreferences,
    dynamicSeedOverride: Color? = null,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = preferences.darkModeOverride ?: systemDark
    val isAmoled = preferences.themeVariant == ThemeVariant.AMOLED_OBSIDIAN && isDark
    val seedColor = when (preferences.themeVariant) {
        ThemeVariant.DYNAMIC_SYSTEM -> dynamicSeedOverride ?: Color(preferences.seedColorArgb)
        ThemeVariant.AMOLED_OBSIDIAN -> Color(0xFF00E5FF)
        ThemeVariant.PRISMATIC_RACK -> Color(preferences.seedColorArgb)
    }
    val style = when (preferences.themeVariant) {
        ThemeVariant.PRISMATIC_RACK -> PaletteStyle.Vibrant
        ThemeVariant.AMOLED_OBSIDIAN -> PaletteStyle.TonalSpot
        ThemeVariant.DYNAMIC_SYSTEM -> PaletteStyle.Expressive
    }
    val motionScheme = if (preferences.useExpressiveMotion) {
        MotionScheme.expressive()
    } else {
        MotionScheme.standard()
    }

    DynamicMaterialExpressiveTheme(
        seedColor = seedColor,
        motionScheme = motionScheme,
        isDark = isDark,
        isAmoled = isAmoled,
        style = style,
        shapes = GlassrackShapes,
        typography = GlassrackTypography,
        animate = true,
    ) {
        val scheme = MaterialTheme.colorScheme
        val ok = Color(0xFF22D3A6).harmonize(scheme.primary)
        val warn = Color(0xFFF59E0B).harmonize(scheme.primary)
        val fault = Color(0xFFF43F5E).harmonize(scheme.primary)
        val active = Color(0xFF38BDF8).harmonize(scheme.primary)

        val (pad, gap) = when (preferences.densityMode) {
            DensityMode.EXPRESSIVE -> 16.dp to 14.dp
            DensityMode.BALANCED -> 13.dp to 10.dp
            DensityMode.COMPACT -> 10.dp to 8.dp
        }
        val tokens = GlassrackDesignTokens(
            statusOk = ok,
            statusWarn = warn,
            statusFault = fault,
            statusActive = active,
            rackSurface = if (isAmoled) Color(0xFF06080C) else scheme.surfaceContainerLow,
            rackSurfaceElevated = if (isAmoled) Color(0xFF10141D) else scheme.surfaceContainerHigh,
            glassBorder = if (isDark) Color(0x26E2E8F0) else Color(0x220F172A),
            densityMode = preferences.densityMode,
            cardPadding = pad,
            cardSpacing = gap,
            isAmoled = isAmoled,
        )
        CompositionLocalProvider(LocalGlassrackDesign provides tokens) {
            content()
        }
    }
}
