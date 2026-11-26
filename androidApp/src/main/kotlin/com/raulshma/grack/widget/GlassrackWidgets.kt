package com.raulshma.grack.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.raulshma.grack.MainActivity
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.ContainerAction
import com.raulshma.grack.core.model.ContainerState
import com.raulshma.grack.core.model.Formatters
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.TriggerSource
import com.raulshma.grack.core.model.WidgetClusterSnapshot
import com.raulshma.grack.core.model.deriveWidgetClusterSnapshot
import com.raulshma.grack.core.model.requiresConfirmation
import com.raulshma.grack.core.ssh.ContainerStateCache
import com.raulshma.grack.core.ssh.HomelabController
import com.raulshma.grack.worker.TelemetryRefreshWorker
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.glance.cartesian.CartesianChartImage
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

private val PARAM_SERVER_ID = ActionParameters.Key<String>("server_id")
private val PARAM_CONTAINER_NAME = ActionParameters.Key<String>("container_name")
private val PARAM_ACTION_TYPE = ActionParameters.Key<String>("action_type")

private object WidgetKoin {
    fun repository(): GlassrackRepository = GlobalContext.get().get()
    fun controller(): HomelabController = GlobalContext.get().get()
    fun containerCache(): ContainerStateCache? = GlobalContext.get().getOrNull()
}

/**
 * Widget rendering reads only on-device data: repository metrics, the controller's in-memory
 * runtime states and the persisted container cache. A widget update must never cause SSH
 * traffic; fresh telemetry arrives exclusively through the periodic TelemetryRefreshWorker.
 */
private suspend fun loadSnapshot(): WidgetClusterSnapshot {
    val repo = WidgetKoin.repository()
    val servers = repo.getServersOnce()
    val historyMap = repo.recentMetricsByServer.first()
    val runtimeMap = WidgetKoin.controller().runtimeStates.value
    val cachedContainers = WidgetKoin.containerCache()?.allEntries().orEmpty()
    return deriveWidgetClusterSnapshot(
        servers = servers,
        metricsByServer = historyMap,
        runtimeStates = runtimeMap,
        cachedContainers = cachedContainers,
    )
}

class RefreshAllWidgetsCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // No SSH from a widget tap: re-render from stored data; the periodic worker
        // provides fresh telemetry.
        TelemetryRefreshWorker.refreshAllWidgets(context)
    }
}

class ToggleContainerWidgetCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val serverId = parameters[PARAM_SERVER_ID] ?: return
        val ctrName = parameters[PARAM_CONTAINER_NAME] ?: return
        val actionName = parameters[PARAM_ACTION_TYPE] ?: "RESTART"
        val repo = WidgetKoin.repository()
        val ctrl = WidgetKoin.controller()
        val server = repo.getServerById(serverId) ?: return
        val prefs = repo.preferences.first()
        if (prefs.safeReadOnlyMode) return
        val action = ContainerAction.valueOf(actionName)
        if (requiresConfirmation(actionIsDestructive = action.isDestructive, prefs = prefs)) {
            // A widget tap cannot present the confirmation UI; it must not silently
            // bypass the gate either, so the destructive tap is recorded as BLOCKED.
            ctrl.recordBlockedAction(
                server = server,
                category = AuditCategory.CONTAINER,
                actionTitle = "Blocked ${action.label} on $ctrName (widget)",
                command = "${action.name.lowercase()} $ctrName",
                reason = "Destructive widget tap requires in-app confirmation",
                triggerSource = TriggerSource.WIDGET,
            )
            return
        }
        // Resolve the target from the last-known cache: rows rendered on the widget came
        // from it, so no SSH lookup is needed before the audited action itself.
        val container = WidgetKoin.containerCache()?.containersFor(serverId)
            ?.firstOrNull { it.name == ctrName } ?: return
        ctrl.performContainerAction(server, container, action, TriggerSource.WIDGET)
        TelemetryRefreshWorker.refreshAllWidgets(context)
    }
}

class SendWolWidgetCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val serverId = parameters[PARAM_SERVER_ID] ?: return
        val repo = WidgetKoin.repository()
        val ctrl = WidgetKoin.controller()
        val server = repo.getServerById(serverId) ?: return
        ctrl.sendWakeOnLan(server, TriggerSource.WIDGET)
    }
}

private fun launchAppIntent(context: Context, dest: String? = null, serverId: String? = null): Intent {
    return Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        dest?.let { putExtra(MainActivity.EXTRA_DEST, it) }
        serverId?.let { putExtra(MainActivity.EXTRA_SERVER_ID, it) }
    }
}

class ClusterPulseWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadSnapshot()
        provideContent {
            GlanceTheme {
                ClusterPulseContent(context, data)
            }
        }
    }
}

@Composable
private fun ClusterPulseContent(context: Context, data: WidgetClusterSnapshot) {
    val metrics = data.latestByServer.values
    val onlineCount = metrics.size.coerceAtMost(data.servers.size)
    val avgCpu = if (metrics.isEmpty()) 0f else metrics.map { it.cpuPct }.average().toFloat()
    val runningCtrs = metrics.sumOf { it.runningContainers }
    val totalCtrs = metrics.sumOf { it.totalContainers }

    Scaffold(
        backgroundColor = GlanceTheme.colors.widgetBackground,
        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(launchAppIntent(context))),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "GLASSRACK PULSE",
                    style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(
                    text = "$onlineCount/${data.servers.size} Up",
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp),
                )
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    text = "↻",
                    style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp),
                    modifier = GlanceModifier.clickable(actionRunCallback<RefreshAllWidgetsCallback>()).padding(4.dp),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = "CPU ${Formatters.formatPercent(avgCpu)} · Containers $runningCtrs/$totalCtrs",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
            Text(
                text = Formatters.formatWidgetAge(data.lastUpdateEpochMs, System.currentTimeMillis()),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(8.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                data.servers.take(3).forEach { srv ->
                    val snap = data.latestByServer[srv.id]
                    val dot = if (snap != null) "●" else "○"
                    Column(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .padding(end = 6.dp)
                            .cornerRadius(10.dp)
                            .background(GlanceTheme.colors.secondaryContainer)
                            .padding(6.dp)
                            .clickable(actionStartActivity(launchAppIntent(context, "server", srv.id))),
                    ) {
                        Text(
                            text = "$dot ${srv.name.substringBefore('.')}",
                            style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1,
                        )
                        Text(
                            text = snap?.let { Formatters.formatPercent(it.cpuPct) } ?: "Offline",
                            style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 11.sp),
                        )
                    }
                }
            }
        }
    }
}

class ServerTelemetryWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadSnapshot()
        val server = data.servers.firstOrNull()
        val snap = server?.let { data.latestByServer[it.id] }
        val hist = server?.let { data.historyByServer[it.id].orEmpty() }.orEmpty()
        provideContent {
            GlanceTheme {
                Scaffold(
                    backgroundColor = GlanceTheme.colors.widgetBackground,
                    modifier = GlanceModifier.fillMaxSize().clickable(
                        actionStartActivity(launchAppIntent(context, "server", server?.id)),
                    ),
                ) {
                    Column(modifier = GlanceModifier.fillMaxSize().padding(10.dp)) {
                        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "● ${server?.name?.substringBefore('.') ?: "No Node"}",
                                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp),
                                modifier = GlanceModifier.defaultWeight(),
                                maxLines = 1,
                            )
                            Text(
                                text = ">_",
                                style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp),
                                modifier = GlanceModifier.clickable(
                                    actionStartActivity(launchAppIntent(context, "terminal", server?.id)),
                                ).padding(4.dp),
                            )
                        }
                        Spacer(GlanceModifier.height(4.dp))
                        Text(
                            text = "CPU ${Formatters.formatPercent(snap?.cpuPct ?: 0f)} · RAM ${Formatters.formatPercent(snap?.memPct ?: 0f)}",
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                        )
                        Text(
                            text = "Temp ${snap?.tempC?.let { "${it.toInt()}°C" } ?: "—"}",
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                        )
                        Text(
                            text = Formatters.formatWidgetAge(
                                data.latestByServer[server?.id]?.timestampEpochMs ?: data.lastUpdateEpochMs,
                                System.currentTimeMillis(),
                            ),
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                            maxLines = 1,
                        )
                        if (hist.size >= 2) {
                            Spacer(GlanceModifier.height(4.dp))
                            val vals = hist.takeLast(16).map { it.cpuPct.toDouble() }
                            val chart = rememberCartesianChart(
                                rememberLineCartesianLayer(
                                    lineProvider = LineCartesianLayer.LineProvider.series(
                                        LineCartesianLayer.rememberLine(
                                            fill = LineCartesianLayer.LineFill.single(Fill(Color(0xFF00D1FF))),
                                            areaFill = LineCartesianLayer.AreaFill.single(Fill(Color(0x3300D1FF))),
                                        ),
                                    ),
                                ),
                            )
                            val model = CartesianChartModel(LineCartesianLayerModel.build { series(vals) })
                            CartesianChartImage(
                                chart = chart,
                                model = model,
                                contentDescription = "CPU History",
                                modifier = GlanceModifier.fillMaxWidth().height(38.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

class ContainerControlWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadSnapshot()
        provideContent {
            GlanceTheme {
                Scaffold(
                    backgroundColor = GlanceTheme.colors.widgetBackground,
                    modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(launchAppIntent(context))),
                ) {
                    Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
                        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "CONTAINERS",
                                style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp),
                                modifier = GlanceModifier.defaultWeight(),
                            )
                            Text(
                                text = "↻",
                                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                                modifier = GlanceModifier.clickable(actionRunCallback<RefreshAllWidgetsCallback>()).padding(4.dp),
                            )
                        }
                        Spacer(GlanceModifier.height(6.dp))
                        if (data.containers.isEmpty()) {
                            Text(
                                text = "No cached container state yet — open the app to populate",
                                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                                maxLines = 1,
                            )
                        } else {
                            Text(
                                text = Formatters.formatWidgetAge(data.containersUpdatedAtEpochMs, System.currentTimeMillis()),
                                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                                maxLines = 1,
                            )
                        }
                        data.containers.forEach { (srv, ctr) ->
                            val running = ctr.state == ContainerState.RUNNING
                            val dot = if (running) "●" else "○"
                            val nextAction = if (running) ContainerAction.STOP.name else ContainerAction.START.name
                            Row(
                                modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "$dot ${ctr.name} (${srv.name.substringBefore('.')})",
                                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp),
                                    modifier = GlanceModifier.defaultWeight(),
                                    maxLines = 1,
                                )
                                Text(
                                    text = if (running) "■" else "▶",
                                    style = TextStyle(
                                        color = if (running) ColorProvider(Color(0xFFF43F5E)) else ColorProvider(Color(0xFF22D3A6)),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    modifier = GlanceModifier.clickable(
                                        actionRunCallback<ToggleContainerWidgetCallback>(
                                            actionParametersOf(
                                                PARAM_SERVER_ID to srv.id,
                                                PARAM_CONTAINER_NAME to ctr.name,
                                                PARAM_ACTION_TYPE to nextAction,
                                            ),
                                        ),
                                    ).padding(horizontal = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

class ActionStripWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadSnapshot()
        val wolServer = data.servers.firstOrNull { it.macAddress.isNotBlank() }
        val sshServer = data.servers.firstOrNull()
        provideContent {
            GlanceTheme {
                Scaffold(
                    backgroundColor = GlanceTheme.colors.widgetBackground,
                    modifier = GlanceModifier.fillMaxSize(),
                ) {
                    Row(
                        modifier = GlanceModifier.fillMaxSize().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (wolServer != null) {
                            Row(
                                modifier = GlanceModifier
                                    .defaultWeight()
                                    .cornerRadius(12.dp)
                                    .background(GlanceTheme.colors.primaryContainer)
                                    .padding(8.dp)
                                    .clickable(
                                        actionRunCallback<SendWolWidgetCallback>(
                                            actionParametersOf(PARAM_SERVER_ID to wolServer.id),
                                        ),
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = "⚡ Wake ${wolServer.name.substringBefore('.')}",
                                    style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                )
                            }
                            Spacer(GlanceModifier.width(8.dp))
                        }
                        Row(
                            modifier = GlanceModifier
                                .defaultWeight()
                                .cornerRadius(12.dp)
                                .background(GlanceTheme.colors.secondaryContainer)
                                .padding(8.dp)
                                .clickable(actionStartActivity(launchAppIntent(context, "terminal", sshServer?.id))),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = ">_ SSH ${sshServer?.name?.substringBefore('.') ?: "Lab"}",
                                style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }
        }
    }
}

class ClusterPulseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ClusterPulseWidget()
}

class ServerTelemetryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ServerTelemetryWidget()
}

class ContainerControlWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContainerControlWidget()
}

class ActionStripWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ActionStripWidget()
}
