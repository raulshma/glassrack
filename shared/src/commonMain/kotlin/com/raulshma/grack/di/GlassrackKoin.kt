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
import com.raulshma.grack.feature.processes.ProcessesViewModel
import com.raulshma.grack.feature.servers.ServersViewModel
import com.raulshma.grack.feature.settings.SettingsViewModel
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
    single { HomelabController(get(), get()) }

    viewModel { OverviewViewModel(get(), get()) }
    viewModel { ServersViewModel(get(), get(), get(), get()) }
    viewModel { ContainersViewModel(get(), get(), get()) }
    viewModel { ProcessesViewModel(get(), get(), get()) }
    viewModel { TerminalViewModel(get(), get()) }
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
