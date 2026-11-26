package com.raulshma.grack.di

import com.raulshma.grack.core.database.GlassrackDatabase
import com.raulshma.grack.core.database.GlassrackRepository
import com.raulshma.grack.core.database.PreferencesStore
import com.raulshma.grack.core.database.buildGlassrackDatabase
import com.raulshma.grack.core.database.createPreferencesDataStore
import com.raulshma.grack.core.ssh.HomelabController
import com.raulshma.grack.core.ssh.PlatformSshClient
import com.raulshma.grack.core.ssh.SshSessionClient
import com.raulshma.grack.feature.audit.AuditViewModel
import com.raulshma.grack.feature.containers.ContainersViewModel
import com.raulshma.grack.feature.overview.OverviewViewModel
import com.raulshma.grack.feature.overview.UpdatesViewModel
import com.raulshma.grack.feature.processes.ProcessesViewModel
import com.raulshma.grack.feature.servers.ServersViewModel
import com.raulshma.grack.feature.settings.SettingsViewModel
import com.raulshma.grack.core.terminal.DefaultTerminalTabHandle
import com.raulshma.grack.core.terminal.TerminalSessionManager
import com.raulshma.grack.core.terminal.TerminalShellOpener
import com.raulshma.grack.core.terminal.TerminalTabHandleFactory
import com.raulshma.grack.feature.terminal.TerminalViewModel
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

expect fun platformSecurityModule(): Module

val glassrackCommonModule = module {
    single<GlassrackDatabase> { buildGlassrackDatabase(get()) }
    single { PreferencesStore(createPreferencesDataStore(get())) }
    single { GlassrackRepository(get(), get(), get()) }
    single<SshSessionClient> { PlatformSshClient(get(), get()) }
    // The Android app contributes a file-backed ContainerStateCache; on iOS (and in tests)
    // no binding exists and the controller simply skips widget-cache writes.
    single { HomelabController(get(), get(), containerStateCache = getOrNull()) }

    // Phase 7: app-scoped terminal session owner. The opener routes through the controller
    // so interactive shells stay audited; the Android app contributes an SshSessionNotifier
    // (foreground service) via getOrNull, and the manager skips notifications without one.
    single<TerminalShellOpener> {
        val controller = get<HomelabController>()
        TerminalShellOpener { server, cols, rows -> controller.openInteractiveShell(server, cols, rows) }
    }
    single {
        TerminalSessionManager(
            shellOpener = get(),
            handleFactory = TerminalTabHandleFactory { shell, appearance -> DefaultTerminalTabHandle(shell, appearance) },
            preferences = get<GlassrackRepository>().preferences,
            notifier = getOrNull(),
        )
    }

    viewModel { OverviewViewModel(get(), get()) }
    viewModel { UpdatesViewModel(get(), get(), get()) }
    // TransferStreams (ContentResolver-backed) and ContainerStateCache (file-backed) are
    // Android-only: getOrNull keeps the VM and this module KMP-clean; transfers report as
    // unsupported and the widget-pending action cannot resolve its target where missing.
    viewModel { ServersViewModel(get(), get(), get(), get(), getOrNull(), containerStateCache = getOrNull()) }
    viewModel { ContainersViewModel(get(), get(), get()) }
    viewModel { ProcessesViewModel(get(), get(), get(), get()) }
    viewModel { TerminalViewModel(get(), get(), get()) }
    viewModel { AuditViewModel(get()) }
    viewModel { SettingsViewModel(get(), get(), get()) }
}

private var koinStarted = false

fun initGlassrackKoin(platformDeclaration: KoinApplication.() -> Unit = {}) {
    if (koinStarted) return
    koinStarted = true
    startKoin {
        platformDeclaration()
        modules(platformSecurityModule(), glassrackCommonModule)
    }
}
