package com.raulshma.grack

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.designsystem.ExpressiveBadgeShape
import com.raulshma.grack.core.designsystem.ExpressiveShapeBadge
import com.raulshma.grack.core.designsystem.GlassrackTheme
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.UserPreferences
import com.raulshma.grack.core.security.BiometricGate
import com.raulshma.grack.core.ssh.HomelabController
import com.raulshma.grack.feature.audit.AuditScreen
import com.raulshma.grack.feature.overview.OverviewScreen
import com.raulshma.grack.feature.servers.ServersScreen
import com.raulshma.grack.feature.settings.SettingsScreen
import com.raulshma.grack.feature.terminal.TerminalScreen
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

sealed interface GlassrackRoute {
    data object Overview : GlassrackRoute
    data class Servers(
        val selectedServerId: String? = null,
    ) : GlassrackRoute
    data class Terminal(
        val serverId: String? = null,
        val initialCommand: String? = null,
    ) : GlassrackRoute
    data object Audit : GlassrackRoute
    data object Settings : GlassrackRoute
}

private enum class TopTab(
    val label: String,
    val icon: ImageVector,
) {
    OVERVIEW("Rack", Icons.Rounded.Dashboard),
    SERVERS("Nodes", Icons.Rounded.Dns),
    TERMINAL("SSH", Icons.Rounded.Terminal),
    AUDIT("Audit", Icons.Rounded.History),
    SETTINGS("Vault", Icons.Rounded.Settings),
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GlassrackApp(
    deepLinkServerId: String? = null,
    deepLinkDestination: String? = null,
    dynamicSeedColor: Color? = null,
    onConsumeDeepLink: () -> Unit = {},
    onDarkThemeChanged: (Boolean) -> Unit = {},
) {
    val repository: GlassrackRepository = koinInject()
    val controller: HomelabController = koinInject()
    val biometricGate: BiometricGate = koinInject()
    val prefs by repository.preferences.collectAsState(initial = UserPreferences())
    val servers by repository.servers.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var unlocked by remember { mutableStateOf(false) }
    var fabMenuOpen by remember { mutableStateOf(false) }
    var pendingAddNode by remember { mutableStateOf(false) }
    var pendingTermServerId by remember { mutableStateOf<String?>(null) }
    var pendingTermCmd by remember { mutableStateOf<String?>(null) }

    val backStack = remember { mutableStateListOf<GlassrackRoute>(GlassrackRoute.Overview) }

    val systemDark = isSystemInDarkTheme()
    val effectiveDark = prefs.darkModeOverride ?: systemDark
    LaunchedEffect(effectiveDark) {
        onDarkThemeChanged(effectiveDark)
    }

    LaunchedEffect(prefs.biometricAppLock) {
        if (prefs.biometricAppLock && !unlocked && biometricGate.canAuthenticate()) {
            unlocked = biometricGate.authenticate("Unlock GRack", "Verify identity to access your homelab vault")
        } else if (!prefs.biometricAppLock) {
            unlocked = true
        }
    }

    LaunchedEffect(deepLinkDestination, deepLinkServerId) {
        when (deepLinkDestination) {
            "terminal" -> {
                pendingTermServerId = deepLinkServerId ?: servers.firstOrNull()?.id
                backStack.clear()
                backStack.add(GlassrackRoute.Terminal(pendingTermServerId, null))
                onConsumeDeepLink()
            }
            "server" -> {
                backStack.clear()
                backStack.add(GlassrackRoute.Servers(deepLinkServerId))
                onConsumeDeepLink()
            }
        }
    }

    fun navigateTop(tab: TopTab) {
        fabMenuOpen = false
        val route = when (tab) {
            TopTab.OVERVIEW -> GlassrackRoute.Overview
            TopTab.SERVERS -> GlassrackRoute.Servers(null)
            TopTab.TERMINAL -> GlassrackRoute.Terminal(pendingTermServerId, pendingTermCmd)
            TopTab.AUDIT -> GlassrackRoute.Audit
            TopTab.SETTINGS -> GlassrackRoute.Settings
        }
        backStack.clear()
        backStack.add(route)
    }

    fun openAddNodeSheet() {
        fabMenuOpen = false
        pendingAddNode = true
        if (backStack.lastOrNull() !is GlassrackRoute.Servers) {
            backStack.clear()
            backStack.add(GlassrackRoute.Servers())
        }
    }

    GlassrackTheme(
        preferences = prefs,
        dynamicSeedOverride = dynamicSeedColor,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            if (prefs.biometricAppLock && !unlocked && biometricGate.canAuthenticate()) {
                BiometricLockGate(
                    onUnlock = {
                        scope.launch {
                            unlocked = biometricGate.authenticate(
                                "Unlock GRack",
                                "Verify identity to access your homelab vault",
                            )
                        }
                    },
                )
            } else {
                val motionScheme = MaterialTheme.motionScheme
                val spatialSpec = motionScheme.defaultSpatialSpec<IntOffset>()
                val fastSpatialSpec = motionScheme.fastSpatialSpec<IntOffset>()
                val effectsSpec = motionScheme.defaultEffectsSpec<Float>()
                val fastEffectsSpec = motionScheme.fastEffectsSpec<Float>()

                val current = backStack.lastOrNull() ?: GlassrackRoute.Overview
                val activeTab = when (current) {
                    is GlassrackRoute.Overview -> TopTab.OVERVIEW
                    is GlassrackRoute.Servers -> TopTab.SERVERS
                    is GlassrackRoute.Terminal -> TopTab.TERMINAL
                    is GlassrackRoute.Audit -> TopTab.AUDIT
                    is GlassrackRoute.Settings -> TopTab.SETTINGS
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    val isExpanded = maxWidth >= 840.dp

                    if (isExpanded) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            NavigationRail(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                header = {
                                    FloatingActionButton(
                                        onClick = { fabMenuOpen = !fabMenuOpen },
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    ) {
                                        MorphingFabIcon(fabMenuOpen = fabMenuOpen)
                                    }
                                },
                            ) {
                                TopTab.entries.forEach { tab ->
                                    val selected = tab == activeTab
                                    NavigationRailItem(
                                        selected = selected,
                                        onClick = { navigateTop(tab) },
                                        icon = {
                                            Icon(
                                                imageVector = tab.icon,
                                                contentDescription = tab.label,
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = tab.label,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        },
                                        alwaysShowLabel = true,
                                        colors = NavigationRailItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .statusBarsPadding(),
                            ) {
                                GlassrackNavDisplay(
                                    backStack = backStack,
                                    onNavigateTop = ::navigateTop,
                                    onAddNode = ::openAddNodeSheet,
                                    onSetPendingTerminal = { srvId, cmd ->
                                        pendingTermServerId = srvId
                                        pendingTermCmd = cmd
                                    },
                                    onConsumedPendingTerminal = {
                                        pendingTermServerId = null
                                        pendingTermCmd = null
                                    },
                                    pendingTermServerId = pendingTermServerId,
                                    pendingTermCmd = pendingTermCmd,
                                    pendingAddNode = pendingAddNode,
                                    onConsumedOpenAddSheet = { pendingAddNode = false },
                                )

                                SpeedDialScrim(
                                    visible = fabMenuOpen,
                                    onDismiss = { fabMenuOpen = false },
                                )

                                androidx.compose.animation.AnimatedVisibility(
                                    visible = fabMenuOpen,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(start = 16.dp, top = 16.dp),
                                    enter = fadeIn(animationSpec = effectsSpec) +
                                        slideInVertically(animationSpec = fastSpatialSpec) { -it / 3 },
                                    exit = fadeOut(animationSpec = fastEffectsSpec) +
                                        slideOutVertically(animationSpec = fastSpatialSpec) { -it / 3 },
                                ) {
                                    SpeedDialActionsColumn(
                                        servers = servers,
                                        onWake = { target ->
                                            fabMenuOpen = false
                                            scope.launch { controller.sendWakeOnLan(target) }
                                        },
                                        onQuickSsh = { serverId ->
                                            fabMenuOpen = false
                                            pendingTermServerId = serverId
                                            pendingTermCmd = null
                                            navigateTop(TopTab.TERMINAL)
                                        },
                                        onAddNode = ::openAddNodeSheet,
                                        horizontalAlignment = Alignment.Start,
                                    )
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding(),
                        ) {
                            GlassrackNavDisplay(
                                backStack = backStack,
                                onNavigateTop = ::navigateTop,
                                onAddNode = ::openAddNodeSheet,
                                onSetPendingTerminal = { srvId, cmd ->
                                    pendingTermServerId = srvId
                                    pendingTermCmd = cmd
                                },
                                onConsumedPendingTerminal = {
                                    pendingTermServerId = null
                                    pendingTermCmd = null
                                },
                                pendingTermServerId = pendingTermServerId,
                                pendingTermCmd = pendingTermCmd,
                                pendingAddNode = pendingAddNode,
                                onConsumedOpenAddSheet = { pendingAddNode = false },
                            )

                            SpeedDialScrim(
                                visible = fabMenuOpen,
                                onDismiss = { fabMenuOpen = false },
                            )

                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.End,
                            ) {
                                AnimatedVisibility(
                                    visible = fabMenuOpen,
                                    enter = fadeIn(animationSpec = effectsSpec) +
                                        slideInVertically(animationSpec = spatialSpec) { it / 3 },
                                    exit = fadeOut(animationSpec = fastEffectsSpec) +
                                        slideOutVertically(animationSpec = spatialSpec) { it / 3 },
                                ) {
                                    SpeedDialActionsColumn(
                                        servers = servers,
                                        onWake = { target ->
                                            fabMenuOpen = false
                                            scope.launch { controller.sendWakeOnLan(target) }
                                        },
                                        onQuickSsh = { serverId ->
                                            fabMenuOpen = false
                                            pendingTermServerId = serverId
                                            pendingTermCmd = null
                                            navigateTop(TopTab.TERMINAL)
                                        },
                                        onAddNode = ::openAddNodeSheet,
                                        horizontalAlignment = Alignment.End,
                                        modifier = Modifier.padding(bottom = 12.dp),
                                    )
                                }

                                HorizontalFloatingToolbar(
                                    expanded = true,
                                    shape = RoundedCornerShape(32.dp),
                                    expandedShadowElevation = 6.dp,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                    colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                                        toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ),
                                    floatingActionButton = {
                                        FloatingToolbarDefaults.VibrantFloatingActionButton(
                                            onClick = { fabMenuOpen = !fabMenuOpen },
                                        ) {
                                            MorphingFabIcon(fabMenuOpen = fabMenuOpen)
                                        }
                                    },
                                ) {
                                    TopTab.entries.forEach { tab ->
                                        FloatingToolbarTabPill(
                                            tab = tab,
                                            selected = tab == activeTab,
                                            onClick = { navigateTop(tab) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GlassrackNavDisplay(
    backStack: MutableList<GlassrackRoute>,
    onNavigateTop: (TopTab) -> Unit,
    onAddNode: () -> Unit,
    onSetPendingTerminal: (String?, String?) -> Unit,
    onConsumedPendingTerminal: () -> Unit,
    pendingTermServerId: String?,
    pendingTermCmd: String?,
    pendingAddNode: Boolean,
    onConsumedOpenAddSheet: () -> Unit,
) {
    val motionScheme = MaterialTheme.motionScheme
    val spatialSpec = motionScheme.defaultSpatialSpec<IntOffset>()
    val effectsSpec = motionScheme.defaultEffectsSpec<Float>()
    val fastEffectsSpec = motionScheme.fastEffectsSpec<Float>()

    NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeAt(backStack.lastIndex)
            }
        },
        transitionSpec = {
            (fadeIn(animationSpec = effectsSpec) +
                slideInHorizontally(animationSpec = spatialSpec) { it / 12 })
                .togetherWith(fadeOut(animationSpec = fastEffectsSpec))
        },
        popTransitionSpec = {
            (fadeIn(animationSpec = effectsSpec) +
                slideInHorizontally(animationSpec = spatialSpec) { -it / 12 })
                .togetherWith(fadeOut(animationSpec = fastEffectsSpec))
        },
        predictivePopTransitionSpec = { _ ->
            (fadeIn(animationSpec = effectsSpec) +
                slideInHorizontally(animationSpec = spatialSpec) { -it / 12 })
                .togetherWith(fadeOut(animationSpec = fastEffectsSpec))
        },
        entryProvider = entryProvider {
            entry<GlassrackRoute.Overview> {
                OverviewScreen(
                    onOpenServerDetail = { srvId ->
                        backStack.add(GlassrackRoute.Servers(selectedServerId = srvId))
                    },
                    onOpenTerminal = { srvId ->
                        onSetPendingTerminal(srvId, null)
                        onNavigateTop(TopTab.TERMINAL)
                    },
                    onAddNode = onAddNode,
                )
            }
            entry<GlassrackRoute.Servers> { route ->
                ServersScreen(
                    selectedServerId = route.selectedServerId,
                    onSelectServer = { id ->
                        backStack[backStack.lastIndex] = GlassrackRoute.Servers(selectedServerId = id)
                    },
                    onOpenTerminal = { srvId, cmd ->
                        onSetPendingTerminal(srvId, cmd)
                        onNavigateTop(TopTab.TERMINAL)
                    },
                    initialOpenAddSheet = pendingAddNode,
                    onConsumedOpenAddSheet = onConsumedOpenAddSheet,
                )
            }
            entry<GlassrackRoute.Terminal> {
                TerminalScreen(
                    pendingOpenServerId = pendingTermServerId,
                    pendingInitialCommand = pendingTermCmd,
                    onConsumedPendingLaunch = onConsumedPendingTerminal,
                )
            }
            entry<GlassrackRoute.Audit> {
                AuditScreen()
            }
            entry<GlassrackRoute.Settings> {
                SettingsScreen()
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun BiometricLockGate(
    onUnlock: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ExpressiveShapeBadge(
                shape = ExpressiveBadgeShape.Cookie12Sided,
                size = 84.dp,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = Icons.Rounded.Fingerprint,
                contentDescription = "Vault Locked",
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "GRack Vault Locked",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Verify your identity to unlock SSH credentials, telemetry, and node controls.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onUnlock,
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Unlock with Biometrics",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FloatingToolbarTabPill(
    tab: TopTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val motionScheme = MaterialTheme.motionScheme
    val effectsSpec = motionScheme.defaultEffectsSpec<Color>()
    val fadeSpec = motionScheme.defaultEffectsSpec<Float>()
    val fastFadeSpec = motionScheme.fastEffectsSpec<Float>()

    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = effectsSpec,
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = effectsSpec,
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier
            .height(42.dp)
            .animateContentSize(animationSpec = motionScheme.fastSpatialSpec()),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (selected) 12.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                modifier = Modifier.size(20.dp),
            )
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(animationSpec = fadeSpec) +
                    expandHorizontally(animationSpec = motionScheme.fastSpatialSpec()),
                exit = fadeOut(animationSpec = fastFadeSpec) +
                    shrinkHorizontally(animationSpec = motionScheme.fastSpatialSpec()),
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MorphingFabIcon(fabMenuOpen: Boolean) {
    val motionScheme = MaterialTheme.motionScheme
    val rotation by animateFloatAsState(
        targetValue = if (fabMenuOpen) 90f else 0f,
        animationSpec = motionScheme.fastSpatialSpec(),
    )
    Crossfade(
        targetState = fabMenuOpen,
        animationSpec = motionScheme.fastEffectsSpec(),
    ) { isOpen ->
        Icon(
            imageVector = if (isOpen) Icons.Rounded.Close else Icons.Rounded.Add,
            contentDescription = if (isOpen) "Close quick actions" else "Quick actions",
            modifier = Modifier.rotate(if (isOpen) rotation - 90f else rotation),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpeedDialScrim(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val motionScheme = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = motionScheme.defaultEffectsSpec()),
        exit = fadeOut(animationSpec = motionScheme.fastEffectsSpec()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }
}

@Composable
private fun SpeedDialActionsColumn(
    servers: List<Server>,
    onWake: (Server) -> Unit,
    onQuickSsh: (String) -> Unit,
    onAddNode: () -> Unit,
    horizontalAlignment: Alignment.Horizontal,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        servers.firstOrNull { it.macAddress.isNotBlank() }?.let { wolTarget ->
            SpeedDialPill(
                label = "Wake ${wolTarget.name.substringBefore('.')}",
                icon = Icons.Rounded.Bolt,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = { onWake(wolTarget) },
            )
        }
        servers.firstOrNull()?.let { firstServer ->
            SpeedDialPill(
                label = "Quick SSH",
                icon = Icons.Rounded.Terminal,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = { onQuickSsh(firstServer.id) },
            )
        }
        SpeedDialPill(
            label = "Add Node",
            icon = Icons.Rounded.Add,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            onClick = onAddNode,
        )
    }
}

@Composable
private fun SpeedDialPill(
    label: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
