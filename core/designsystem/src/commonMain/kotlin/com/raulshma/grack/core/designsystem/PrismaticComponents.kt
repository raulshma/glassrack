package com.raulshma.grack.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raulshma.grack.core.model.ConfirmationLevel
import com.raulshma.grack.core.model.ServerHealth
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Geometric Material 3 Expressive shape presets backed by [MaterialShapes].
 */
enum class ExpressiveBadgeShape {
    Cookie9Sided,
    Cookie7Sided,
    Cookie12Sided,
    SoftBurst,
    Sunny,
    Diamond,
    Circle,
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveBadgeShape.toMaterialShape(): Shape = when (this) {
    ExpressiveBadgeShape.Cookie9Sided -> MaterialShapes.Cookie9Sided.toShape()
    ExpressiveBadgeShape.Cookie7Sided -> MaterialShapes.Cookie7Sided.toShape()
    ExpressiveBadgeShape.Cookie12Sided -> MaterialShapes.Cookie12Sided.toShape()
    ExpressiveBadgeShape.SoftBurst -> MaterialShapes.SoftBurst.toShape()
    ExpressiveBadgeShape.Sunny -> MaterialShapes.Sunny.toShape()
    ExpressiveBadgeShape.Diamond -> MaterialShapes.Diamond.toShape()
    ExpressiveBadgeShape.Circle -> MaterialShapes.Circle.toShape()
}

/**
 * Expressive polygonal status indicator mapped from [ServerHealth] to harmonized semantic tokens.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusBeacon(
    health: ServerHealth,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    val design = LocalGlassrackDesign.current
    val targetColor = when (health) {
        ServerHealth.ONLINE -> design.statusOk
        ServerHealth.WARNING -> design.statusWarn
        ServerHealth.OFFLINE -> design.statusFault
        ServerHealth.CONNECTING -> design.statusActive
        ServerHealth.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
    val color by animateColorAsState(targetColor)
    val shape: Shape = when (health) {
        ServerHealth.ONLINE -> MaterialShapes.Cookie9Sided.toShape()
        ServerHealth.WARNING -> MaterialShapes.Triangle.toShape()
        ServerHealth.OFFLINE -> MaterialShapes.Diamond.toShape()
        ServerHealth.CONNECTING -> MaterialShapes.Sunny.toShape()
        ServerHealth.UNKNOWN -> MaterialShapes.Circle.toShape()
    }
    val pulse = if (health == ServerHealth.CONNECTING || health == ServerHealth.WARNING) {
        val inf = rememberInfiniteTransition()
        val s by inf.animateFloat(
            initialValue = 0.88f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        )
        s
    } else 1f

    Box(
        modifier = modifier
            .size(size)
            .scale(pulse)
            .clip(shape)
            .background(color),
    )
}

/**
 * Pure Material 3 Expressive tonal surface card with 24dp outer radii and no borders or gradients.
 */
@Composable
fun ExpressiveCard(
    modifier: Modifier = Modifier,
    tonalContainerColor: Color = LocalGlassrackDesign.current.rackSurface,
    shape: Shape = RoundedCornerShape(24.dp),
    contentPadding: PaddingValues = PaddingValues(LocalGlassrackDesign.current.cardPadding),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.contentColorFor(tonalContainerColor)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = tonalContainerColor,
            contentColor = contentColor,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content,
            )
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = tonalContainerColor,
            contentColor = contentColor,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content,
            )
        }
    }
}

/**
 * Pure tonal card wrapper preserving legacy [accentColor] and [highlightedBorder] parameters
 * for source compatibility while rendering clean M3 Expressive tonal surfaces.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun RackBladeCard(
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    highlightedBorder: Color? = null,
    tonalContainerColor: Color = LocalGlassrackDesign.current.rackSurface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ExpressiveCard(
        modifier = modifier,
        tonalContainerColor = tonalContainerColor,
        shape = RoundedCornerShape(24.dp),
        onClick = onClick,
        content = content,
    )
}

/**
 * Vertical container for grouped list items separated by tight 2-3dp tonal gaps.
 */
@Composable
fun ExpressiveGroupedCard(
    modifier: Modifier = Modifier,
    itemSpacing: Dp = 3.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(itemSpacing),
        content = content,
    )
}

/**
 * Individual item inside an [ExpressiveGroupedCard] using 24dp outer corners and 6dp inner corners.
 */
@Composable
fun ExpressiveGroupedItem(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = LocalGlassrackDesign.current.rackSurface,
    contentPadding: PaddingValues = PaddingValues(LocalGlassrackDesign.current.cardPadding),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ExpressiveCard(
        modifier = modifier,
        tonalContainerColor = containerColor,
        shape = expressiveGroupItemShape(index = index, count = count),
        contentPadding = contentPadding,
        onClick = onClick,
        content = content,
    )
}

/**
 * Geometric Material 3 Expressive badge displaying either an [icon], short metric [text], or custom [content].
 */
@Composable
fun ExpressiveShapeBadge(
    modifier: Modifier = Modifier,
    shape: ExpressiveBadgeShape = ExpressiveBadgeShape.Cookie9Sided,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Dp = 44.dp,
    icon: ImageVector? = null,
    text: String? = null,
    contentDescription: String? = null,
    content: (@Composable BoxScope.() -> Unit)? = null,
) {
    val design = LocalGlassrackDesign.current
    Surface(
        modifier = modifier.size(size),
        shape = shape.toMaterialShape(),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                content != null -> content()
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(size * 0.5f),
                    tint = contentColor,
                )
                text != null -> Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge.merge(design.monoNumericStyle),
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Rounded-square tonal icon badge (`14.dp` radius) for settings rows, categories, and tool tiles.
 */
@Composable
fun ExpressiveIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    contentDescription: String? = null,
) {
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
                tint = contentColor,
            )
        }
    }
}

/**
 * Bold 28dp tonal hero banner with layered `Cookie12Sided`/`Cookie9Sided` geometric badge and pill action row.
 */
@Composable
fun ExpressiveHeroCard(
    headline: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    eyebrow: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    badgeIcon: ImageVector? = null,
    badgeText: String? = null,
    badgeShape: ExpressiveBadgeShape = ExpressiveBadgeShape.Cookie9Sided,
    actions: (@Composable RowScope.() -> Unit)? = null,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (eyebrow != null) {
                        Text(
                            text = eyebrow,
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.headlineMedium,
                        color = contentColor,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor.copy(alpha = 0.84f),
                        )
                    }
                }
                if (badgeIcon != null || badgeText != null) {
                    Box(
                        modifier = Modifier.padding(start = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ExpressiveShapeBadge(
                            shape = ExpressiveBadgeShape.Cookie12Sided,
                            containerColor = contentColor.copy(alpha = 0.12f),
                            contentColor = contentColor,
                            size = 64.dp,
                        )
                        ExpressiveShapeBadge(
                            shape = badgeShape,
                            containerColor = contentColor.copy(alpha = 0.18f),
                            contentColor = contentColor,
                            size = 46.dp,
                            icon = badgeIcon,
                            text = badgeText,
                        )
                    }
                }
            }
            if (body != null) {
                body()
            }
            if (actions != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

/**
 * Editorial section header with optional tabular pill count badge and trailing action button.
 */
@Composable
fun ExpressiveSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val design = LocalGlassrackDesign.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (count != null) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelMedium.merge(design.monoNumericStyle),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * Connected M3 Expressive pill toggle group using [ToggleButton] with morphing outer/inner corners.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ExpressiveSegmentedPillGroup(
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector)? = null,
) {
    val design = LocalGlassrackDesign.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            val baseShape = expressiveHorizontalGroupShape(index = index, count = options.size)
            val pillShape = RoundedCornerShape(999.dp)
            ToggleButton(
                checked = isSelected,
                onCheckedChange = { checked ->
                    if (checked || !isSelected) {
                        onSelected(option)
                    }
                },
                modifier = Modifier.weight(1f),
                shapes = ToggleButtonShapes(
                    shape = baseShape,
                    pressedShape = RoundedCornerShape(14.dp),
                    checkedShape = pillShape,
                ),
                colors = ToggleButtonDefaults.colors(
                    containerColor = design.rackSurfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon(option),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(16.dp),
                    )
                }
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Soft pill-shaped search input field rendered on `surfaceContainerHigh` without borders.
 */
@Composable
fun ExpressiveSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search...",
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = { onQueryChange("") },
) {
    val textFieldState = rememberTextFieldState(initialText = query)
    val latestQuery by rememberUpdatedState(query)
    val latestOnQueryChange by rememberUpdatedState(onQueryChange)

    LaunchedEffect(query) {
        if (textFieldState.text.toString() != query) {
            textFieldState.setTextAndPlaceCursorAtEnd(query)
        }
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect { current ->
            if (current != latestQuery) {
                latestOnQueryChange(current)
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (textFieldState.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    state = textFieldState,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (textFieldState.text.isNotEmpty() && onClear != null) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Layered concentric geometric empty state with `Cookie12Sided` + `SoftBurst` badge and optional CTA pill.
 */
@Composable
fun ExpressiveEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    outerShape: ExpressiveBadgeShape = ExpressiveBadgeShape.Cookie12Sided,
) {
    ExpressiveCard(
        modifier = modifier.fillMaxWidth(),
        tonalContainerColor = LocalGlassrackDesign.current.rackSurface,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                ExpressiveShapeBadge(
                    shape = outerShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    size = 80.dp,
                )
                ExpressiveShapeBadge(
                    shape = ExpressiveBadgeShape.SoftBurst,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    size = 54.dp,
                    icon = icon,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/**
 * Wavy M3 Expressive linear progress bar paired with tabular monospace readout.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WavyMetricBar(
    label: String,
    valueText: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val design = LocalGlassrackDesign.current
    val clamped = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(clamped)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium.merge(design.monoNumericStyle),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearWavyProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth(),
            color = accentColor,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

/**
 * Wavy M3 Expressive circular gauge with tabular monospace primary metric in the center.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WavyHeroGauge(
    progress: Float,
    centerPrimary: String,
    centerSecondary: String,
    modifier: Modifier = Modifier,
    size: Dp = 104.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val design = LocalGlassrackDesign.current
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f))
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        CircularWavyProgressIndicator(
            progress = { animated },
            modifier = Modifier.size(size),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerPrimary,
                style = MaterialTheme.typography.titleLarge.merge(design.monoNumericStyle),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = centerSecondary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun VicoSparkline(
    values: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    val safeValues = if (values.size >= 2) values else listOf(0f, values.firstOrNull() ?: 0f)
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(safeValues) {
        producer.runTransaction {
            lineModel { series(safeValues.map { it.toDouble() }) }
        }
    }
    val line = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(lineColor)),
        areaFill = LineCartesianLayer.AreaFill.single(Fill(lineColor.copy(alpha = 0.18f))),
    )
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(line),
            ),
        ),
        modelProducer = producer,
        modifier = modifier,
    )
}

@Composable
fun VicoTelemetryChart(
    seriesList: List<Pair<List<Float>, Color>>,
    modifier: Modifier = Modifier,
) {
    val producer = remember { CartesianChartModelProducer() }
    val normalized = seriesList.map { (vals, color) ->
        (if (vals.size >= 2) vals else listOf(0f, vals.firstOrNull() ?: 0f)) to color
    }
    LaunchedEffect(normalized) {
        producer.runTransaction {
            lineModel {
                normalized.forEach { (vals, _) ->
                    series(vals.map { it.toDouble() })
                }
            }
        }
    }
    val lines = normalized.map { (_, color) ->
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(color)),
            areaFill = LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = 0.15f))),
        )
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(lines),
            ),
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(),
        ),
        modelProducer = producer,
        modifier = modifier,
    )
}

/**
 * Rounded-cap Vico column chart for telemetry distributions, latency buckets, or resource comparisons.
 */
@Composable
fun ExpressiveBarChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    barWidth: Dp = 14.dp,
    showAxes: Boolean = false,
) {
    val safeValues = if (values.isNotEmpty()) values else listOf(0f)
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(safeValues) {
        producer.runTransaction {
            columnModel {
                series(safeValues.map { it.toDouble() })
            }
        }
    }
    val column = rememberLineComponent(
        fill = Fill(barColor),
        thickness = barWidth,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp),
    )
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                columnProvider = ColumnCartesianLayer.ColumnProvider.series(column),
            ),
            startAxis = if (showAxes) VerticalAxis.rememberStart() else null,
            bottomAxis = if (showAxes) HorizontalAxis.rememberBottom() else null,
        ),
        modelProducer = producer,
        modifier = modifier,
    )
}

/**
 * Pure tonal status pill displaying active background jobs with an expressive [LoadingIndicator].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActiveJobsPill(
    runningCount: Int,
    latestTitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val design = LocalGlassrackDesign.current
    val isRunning = runningCount > 0
    val containerColor = if (isRunning) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        design.rackSurfaceContainer
    }
    val contentColor = if (isRunning) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isRunning) {
                LoadingIndicator(
                    modifier = Modifier.size(18.dp),
                    color = contentColor,
                )
                Text(
                    text = "$runningCount running${latestTitle?.let { " · $it" } ?: ""}",
                    style = MaterialTheme.typography.labelMedium.merge(design.monoNumericStyle),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = "Jobs Idle",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Tonal pill toggle indicating Safe Read-Only mode vs Armed execution state.
 */
@Composable
fun SafeModeBadge(
    isSafeMode: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val design = LocalGlassrackDesign.current
    val containerColor = if (isSafeMode) design.statusWarnContainer else design.statusOkContainer
    val contentColor = if (isSafeMode) design.onStatusWarnContainer else design.onStatusOkContainer
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = if (isSafeMode) Icons.Rounded.Lock else Icons.Rounded.Shield,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = contentColor,
            )
            Text(
                text = if (isSafeMode) "SAFE RO" else "ARMED",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
        }
    }
}

/**
 * Tonal pill hold-to-confirm control with smooth horizontal progress fill and zero borders.
 */
@Composable
fun HoldToConfirmPill(
    label: String,
    holdDurationMs: Long = 1100L,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.errorContainer,
    contentColor: Color = MaterialTheme.colorScheme.onErrorContainer,
) {
    var progress by remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(containerColor)
            .pointerInput(onConfirmed) {
                detectTapGestures(
                    onPress = {
                        holding = true
                        val job = scope.launch {
                            val step = 30L
                            var elapsed = 0L
                            while (elapsed < holdDurationMs) {
                                delay(step)
                                elapsed += step
                                progress = (elapsed.toFloat() / holdDurationMs).coerceIn(0f, 1f)
                            }
                            progress = 1f
                            onConfirmed()
                        }
                        tryAwaitRelease()
                        holding = false
                        job.cancel()
                        progress = 0f
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(contentColor.copy(alpha = 0.24f)),
        )
        Text(
            text = if (holding) "Keep holding..." else "Hold: $label",
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
    }
}

/**
 * Expressive confirmation dialog with geometric warning badge and hold-to-confirm or destructive pill CTA.
 */
@Composable
fun ActionSafetyDialog(
    title: String,
    description: String,
    confirmationLevel: ConfirmationLevel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val design = LocalGlassrackDesign.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        icon = {
            ExpressiveShapeBadge(
                shape = ExpressiveBadgeShape.SoftBurst,
                containerColor = design.statusWarnContainer,
                contentColor = design.onStatusWarnContainer,
                size = 48.dp,
                icon = Icons.Rounded.Warning,
            )
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            if (confirmationLevel == ConfirmationLevel.HOLD_TO_CONFIRM) {
                HoldToConfirmPill(
                    label = "Confirm",
                    onConfirmed = onConfirm,
                )
            } else {
                Button(
                    onClick = onConfirm,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Confirm")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
