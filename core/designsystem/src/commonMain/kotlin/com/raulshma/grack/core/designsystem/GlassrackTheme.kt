package com.raulshma.grack.core.designsystem

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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raulshma.grack.core.model.DensityMode
import com.raulshma.grack.core.model.ThemeVariant
import com.raulshma.grack.core.model.UserPreferences
import com.materialkolor.DynamicMaterialExpressiveTheme
import com.materialkolor.PaletteStyle
import com.materialkolor.ktx.harmonize

/**
 * Curated vibrant seed colors for Material Design 3 Expressive dynamic palette generation.
 */
val ExpressiveSeedSwatches: List<Pair<String, Long>> = listOf(
    "Deep Teal" to 0xFF006B7D,
    "Coral Berry" to 0xFFD8435E,
    "Cobalt" to 0xFF3B5BDB,
    "Emerald" to 0xFF15803D,
    "Indigo" to 0xFF5B43D6,
    "Amber" to 0xFFC26A0A,
    "Plum" to 0xFF8E24AA,
    "Rose" to 0xFFBE185D,
    "Sky Cyan" to 0xFF0284C7,
)

/**
 * Semantic status colors, tonal surface roles, and density metrics exposed via [LocalGlassrackDesign].
 */
@Immutable
data class GlassrackDesignTokens(
    val statusOk: Color,
    val statusOkContainer: Color,
    val onStatusOkContainer: Color,
    val statusWarn: Color,
    val statusWarnContainer: Color,
    val onStatusWarnContainer: Color,
    val statusFault: Color,
    val statusFaultContainer: Color,
    val onStatusFaultContainer: Color,
    val statusActive: Color,
    val statusActiveContainer: Color,
    val onStatusActiveContainer: Color,
    val rackSurface: Color,
    val rackSurfaceElevated: Color,
    val rackSurfaceContainer: Color,
    val glassBorder: Color,
    val densityMode: DensityMode,
    val cardPadding: Dp,
    val cardSpacing: Dp,
    val isAmoled: Boolean,
)

/**
 * Monospace text style with tabular numerals (`tnum`) for crisp telemetry alignment.
 */
val GlassrackDesignTokens.monoNumericStyle: TextStyle
    get() = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum",
    )

val LocalGlassrackDesign = staticCompositionLocalOf {
    GlassrackDesignTokens(
        statusOk = Color(0xFF2DD4BF),
        statusOkContainer = Color(0xFF0F2E2B),
        onStatusOkContainer = Color(0xFFA7F3D0),
        statusWarn = Color(0xFFFBBF24),
        statusWarnContainer = Color(0xFF33260C),
        onStatusWarnContainer = Color(0xFFFDE68A),
        statusFault = Color(0xFFFB7185),
        statusFaultContainer = Color(0xFF36121B),
        onStatusFaultContainer = Color(0xFFFECDD3),
        statusActive = Color(0xFF38BDF8),
        statusActiveContainer = Color(0xFF0E293B),
        onStatusActiveContainer = Color(0xFFBAE6FD),
        rackSurface = Color(0xFF0E1420),
        rackSurfaceElevated = Color(0xFF151D2E),
        rackSurfaceContainer = Color(0xFF1C263B),
        glassBorder = Color(0x26FFFFFF),
        densityMode = DensityMode.EXPRESSIVE,
        cardPadding = 16.dp,
        cardSpacing = 12.dp,
        isAmoled = false,
    )
}

/**
 * Computes M3 Expressive grouped vertical list item shape with rounded outer corners and tighter inner corners.
 */
fun expressiveGroupItemShape(
    index: Int,
    count: Int,
    outerRadius: Dp = 24.dp,
    innerRadius: Dp = 6.dp,
): Shape {
    if (count <= 1) return RoundedCornerShape(outerRadius)
    return when (index) {
        0 -> RoundedCornerShape(
            topStart = outerRadius,
            topEnd = outerRadius,
            bottomStart = innerRadius,
            bottomEnd = innerRadius,
        )
        count - 1 -> RoundedCornerShape(
            topStart = innerRadius,
            topEnd = innerRadius,
            bottomStart = outerRadius,
            bottomEnd = outerRadius,
        )
        else -> RoundedCornerShape(innerRadius)
    }
}

/**
 * Computes M3 Expressive connected horizontal pill group shape with full pill ends and softer inner corners.
 */
fun expressiveHorizontalGroupShape(
    index: Int,
    count: Int,
    outerRadius: Dp = 999.dp,
    innerRadius: Dp = 8.dp,
): Shape {
    if (count <= 1) return RoundedCornerShape(outerRadius)
    return when (index) {
        0 -> RoundedCornerShape(
            topStart = outerRadius,
            bottomStart = outerRadius,
            topEnd = innerRadius,
            bottomEnd = innerRadius,
        )
        count - 1 -> RoundedCornerShape(
            topStart = innerRadius,
            bottomStart = innerRadius,
            topEnd = outerRadius,
            bottomEnd = outerRadius,
        )
        else -> RoundedCornerShape(innerRadius)
    }
}

private val GlassrackShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val GlassrackTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
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
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

private data class SemanticColorTriplet(
    val accent: Color,
    val container: Color,
    val onContainer: Color,
)

private fun harmonizeSemanticTriplet(
    seed: Color,
    harmonizeTarget: Color,
    surfaceBase: Color,
    isDark: Boolean,
): SemanticColorTriplet {
    val harmonized = seed.harmonize(harmonizeTarget)
    return if (isDark) {
        SemanticColorTriplet(
            accent = lerp(harmonized, Color.White, 0.22f),
            container = lerp(surfaceBase, harmonized, 0.24f),
            onContainer = lerp(harmonized, Color.White, 0.74f),
        )
    } else {
        SemanticColorTriplet(
            accent = lerp(harmonized, Color.Black, 0.34f),
            container = lerp(Color.White, harmonized, 0.16f),
            onContainer = lerp(harmonized, Color.Black, 0.62f),
        )
    }
}

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
        ThemeVariant.AMOLED_OBSIDIAN -> Color(preferences.seedColorArgb)
        ThemeVariant.PRISMATIC_RACK -> Color(preferences.seedColorArgb)
    }
    val style = when (preferences.themeVariant) {
        ThemeVariant.PRISMATIC_RACK -> PaletteStyle.Expressive
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
        val surfaceContainerBase = if (isAmoled) Color(0xFF0E121A) else scheme.surfaceContainer
        val ok = harmonizeSemanticTriplet(Color(0xFF16A34A), scheme.primary, surfaceContainerBase, isDark)
        val warn = harmonizeSemanticTriplet(Color(0xFFD97706), scheme.primary, surfaceContainerBase, isDark)
        val fault = harmonizeSemanticTriplet(Color(0xFFE11D48), scheme.primary, surfaceContainerBase, isDark)
        val active = harmonizeSemanticTriplet(Color(0xFF0284C7), scheme.primary, surfaceContainerBase, isDark)

        val (pad, gap) = when (preferences.densityMode) {
            DensityMode.EXPRESSIVE -> 16.dp to 14.dp
            DensityMode.BALANCED -> 13.dp to 10.dp
            DensityMode.COMPACT -> 10.dp to 8.dp
        }
        val tokens = GlassrackDesignTokens(
            statusOk = ok.accent,
            statusOkContainer = ok.container,
            onStatusOkContainer = ok.onContainer,
            statusWarn = warn.accent,
            statusWarnContainer = warn.container,
            onStatusWarnContainer = warn.onContainer,
            statusFault = fault.accent,
            statusFaultContainer = fault.container,
            onStatusFaultContainer = fault.onContainer,
            statusActive = active.accent,
            statusActiveContainer = active.container,
            onStatusActiveContainer = active.onContainer,
            rackSurface = if (isAmoled) Color(0xFF05070B) else scheme.surfaceContainerLow,
            rackSurfaceElevated = if (isAmoled) Color(0xFF0E121A) else scheme.surfaceContainer,
            rackSurfaceContainer = if (isAmoled) Color(0xFF151A24) else scheme.surfaceContainerHigh,
            glassBorder = scheme.outlineVariant.copy(alpha = 0.25f),
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
