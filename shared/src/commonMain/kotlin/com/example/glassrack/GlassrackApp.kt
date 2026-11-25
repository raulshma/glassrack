package com.example.glassrack

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.example.glassrack.core.database.GlassrackRepository
import com.example.glassrack.core.designsystem.GlassrackTheme
import com.example.glassrack.core.designsystem.LocalGlassrackDesign
import com.example.glassrack.core.model.UserPreferences
import com.example.glassrack.core.security.BiometricGate
import com.example.glassrack.core.ssh.HomelabController
import com.example.glassrack.feature.audit.AuditScreen
import com.example.glassrack.feature.overview.OverviewScreen
import com.example.glassrack.feature.servers.ServersScreen
import com.example.glassrack.feature.settings.SettingsScreen
import com.example.glassrack.feature.terminal.TerminalScreen
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

sealed interface GlassrackRoute {
    data object Overview : GlassrackRoute
    data class Servers(val selectedServerId: String? = null) : GlassrackRoute
    data class Terminal(val serverId: String? = null, val initialCommand: String? = null) : GlassrackRoute
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
) {
    val repository: GlassrackRepository = koinInject()
    val controller: HomelabController = koinInject()
    val biometricGate: BiometricGate = koinInject()
    val prefs by repository.preferences.collectAsState(initial = UserPreferences())
    val servers by repository.servers.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var unlocked by remember { mutableStateOf(false) }
    var fabMenuOpen by remember { mutableStateOf(false) }
    var pendingTermServerId by remember { mutableStateOf<String?>(null) }
    var pendingTermCmd by remember { mutableStateOf<String?>(null) }

    val backStack = remember { mutableStateListOf<GlassrackRoute>(GlassrackRoute.Overview) }

    LaunchedEffect(prefs.biometricAppLock) {
        if (prefs.biometricAppLock && !unlocked && biometricGate.canAuthenticate()) {
            unlocked = biometricGate.authenticate("Unlock Glassrack", "Verify identity to access your homelab vault")
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

    GlassrackTheme(
        preferences = prefs,
        dynamicSeedOverride = dynamicSeedColor,
    ) {
        val design = LocalGlassrackDesign.current
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            if (prefs.biometricAppLock && !unlocked && biometricGate.canAuthenticate()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Fingerprint, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Glassrack Vault Locked", style = MaterialTheme.typography.headlineSmall)
                        Button(onClick = {
                            scope.launch {
                                unlocked = biometricGate.authenticate("Unlock Glassrack", "Verify identity")
                            }
                        }) { Text("Unlock with Biometrics") }
                    }
                }
            } else {
                val current = backStack.lastOrNull() ?: GlassrackRoute.Overview
                val activeTab = when (current) {
                    is GlassrackRoute.Overview -> TopTab.OVERVIEW
                    is GlassrackRoute.Servers -> TopTab.SERVERS
                    is GlassrackRoute.Terminal -> TopTab.TERMINAL
                    is GlassrackRoute.Audit -> TopTab.AUDIT
                    is GlassrackRoute.Settings -> TopTab.SETTINGS
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding(),
                ) {
                    NavDisplay(
                        backStack = backStack,
                        onBack = {
                            if (backStack.size > 1) {
                                backStack.removeAt(backStack.lastIndex)
                            }
                        },
                        entryProvider = entryProvider {
                            entry<GlassrackRoute.Overview> {
                                OverviewScreen(
                                    onOpenServerDetail = { srvId ->
                                        backStack.add(GlassrackRoute.Servers(srvId))
                                    },
                                    onOpenTerminal = { srvId ->
                                        pendingTermServerId = srvId
                                        pendingTermCmd = null
                                        navigateTop(TopTab.TERMINAL)
                                    },
                                )
                            }
                            entry<GlassrackRoute.Servers> { route ->
                                ServersScreen(
                                    selectedServerId = route.selectedServerId,
                                    onSelectServer = { id ->
                                        backStack[backStack.lastIndex] = GlassrackRoute.Servers(id)
                                    },
                                    onOpenTerminal = { srvId, cmd ->
                                        pendingTermServerId = srvId
                                        pendingTermCmd = cmd
                                        navigateTop(TopTab.TERMINAL)
                                    },
                                )
                            }
                            entry<GlassrackRoute.Terminal> {
                                TerminalScreen(
                                    pendingOpenServerId = pendingTermServerId,
                                    pendingInitialCommand = pendingTermCmd,
                                    onConsumedPendingLaunch = {
                                        pendingTermServerId = null
                                        pendingTermCmd = null
                                    },
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

                    // Expressive Speed-Dial FAB Menu + Floating Rack Dock
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        AnimatedVisibility(
                            visible = fabMenuOpen,
                            enter = fadeIn() + slideInVertically { it / 2 },
                            exit = fadeOut() + slideOutVertically { it / 2 },
                        ) {
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 10.dp),
                            ) {
                                servers.firstOrNull { it.macAddress.isNotBlank() }?.let { wolTarget ->
                                    SmallFloatingActionButton(
                                        onClick = {
                                            fabMenuOpen = false
                                            scope.launch { controller.sendWakeOnLan(wolTarget) }
                                        },
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    ) {
                                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(18.dp))
                                            Text("Wake ${wolTarget.name.substringBefore('.')}", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                                SmallFloatingActionButton(
                                    onClick = {
                                        fabMenuOpen = false
                                        pendingTermServerId = servers.firstOrNull()?.id
                                        navigateTop(TopTab.TERMINAL)
                                    },
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Rounded.Terminal, null, modifier = Modifier.size(18.dp))
                                        Text("Quick SSH", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                SmallFloatingActionButton(
                                    onClick = {
                                        fabMenuOpen = false
                                        navigateTop(TopTab.SERVERS)
                                    },
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                ) {
                                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                                        Text("Manage Nodes", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }

                        HorizontalFloatingToolbar(
                            expanded = true,
                            shape = RoundedCornerShape(32.dp),
                            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(
                                toolbarContainerColor = design.rackSurfaceElevated.copy(alpha = 0.96f),
                            ),
                            floatingActionButton = {
                                FloatingToolbarDefaults.VibrantFloatingActionButton(
                                    onClick = { fabMenuOpen = !fabMenuOpen },
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Terminal,
                                        contentDescription = "Quick Actions",
                                    )
                                }
                            },
                        ) {
                            TopTab.entries.forEach { tab ->
                                val selected = tab == activeTab
                                IconButton(
                                    onClick = { navigateTop(tab) },
                                    colors = if (selected) {
                                        IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    } else {
                                        IconButtonDefaults.iconButtonColors(
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                ) {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
