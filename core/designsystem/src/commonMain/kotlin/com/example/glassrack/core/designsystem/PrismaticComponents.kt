package com.example.glassrack.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.glassrack.core.model.ConfirmationLevel
import com.example.glassrack.core.model.ServerHealth
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

@Composable
fun RackBladeCard(
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    highlightedBorder: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val design = LocalGlassrackDesign.current
    val shape = RoundedCornerShape(24.dp)
    val borderColor = highlightedBorder ?: design.glassBorder
    val gradient = Brush.verticalGradient(
        colors = listOf(
            design.rackSurfaceElevated.copy(alpha = 0.92f),
            design.rackSurface.copy(alpha = 0.96f),
        ),
    )

    Surface(
        modifier = modifier,
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, borderColor),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Row(
            modifier = Modifier
                .background(gradient)
                .fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accentColor.copy(alpha = 0.85f)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(design.cardPadding),
                content = content,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WavyMetricBar(
    label: String,
    valueText: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
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
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
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
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActiveJobsPill(
    runningCount: Int,
    latestTitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val design = LocalGlassrackDesign.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = if (runningCount > 0) MaterialTheme.colorScheme.primaryContainer else design.rackSurfaceElevated,
        border = BorderStroke(1.dp, design.glassBorder),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (runningCount > 0) {
                LoadingIndicator(modifier = Modifier.size(18.dp))
                Text(
                    text = "$runningCount running${latestTitle?.let { " · $it" } ?: ""}",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = "Jobs Idle",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SafeModeBadge(
    isSafeMode: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val design = LocalGlassrackDesign.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = if (isSafeMode) design.statusWarn.copy(alpha = 0.18f) else design.rackSurfaceElevated,
        border = BorderStroke(1.dp, if (isSafeMode) design.statusWarn else design.glassBorder),
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = if (isSafeMode) Icons.Rounded.Lock else Icons.Rounded.Shield,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isSafeMode) design.statusWarn else design.statusOk,
            )
            Text(
                text = if (isSafeMode) "SAFE RO" else "ARMED",
                style = MaterialTheme.typography.labelSmall,
                color = if (isSafeMode) design.statusWarn else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
            .border(1.dp, contentColor.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
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
                .background(contentColor.copy(alpha = 0.25f)),
        )
        Text(
            text = if (holding) "Keep holding..." else "Hold: $label",
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
    }
}

@Composable
fun ActionSafetyDialog(
    title: String,
    description: String,
    confirmationLevel: ConfirmationLevel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null, tint = LocalGlassrackDesign.current.statusWarn) },
        title = { Text(title) },
        text = { Text(description) },
        confirmButton = {
            if (confirmationLevel == ConfirmationLevel.HOLD_TO_CONFIRM) {
                HoldToConfirmPill(
                    label = "Confirm",
                    onConfirmed = onConfirm,
                )
            } else {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Confirm")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
