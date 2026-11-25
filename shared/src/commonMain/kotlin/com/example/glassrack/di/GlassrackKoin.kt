package com.example.glassrack.di

import com.example.glassrack.core.database.GlassrackDatabase
import com.example.glassrack.core.database.GlassrackRepository
import com.example.glassrack.core.database.PreferencesStore
import com.example.glassrack.core.database.buildGlassrackDatabase
import com.example.glassrack.core.database.createPreferencesDataStore
import com.example.glassrack.core.ssh.HomelabController
import com.example.glassrack.core.ssh.PlatformSshClient
import com.example.glassrack.feature.audit.AuditViewModel
import com.example.glassrack.feature.containers.ContainersViewModel
import com.example.glassrack.feature.overview.OverviewViewModel
import com.example.glassrack.feature.processes.ProcessesViewModel
import com.example.glassrack.feature.servers.ServersViewModel
import com.example.glassrack.feature.settings.SettingsViewModel
import com.example.glassrack.feature.terminal.TerminalViewModel
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
    single { PlatformSshClient(get(), get()) }
    single { HomelabController(get(), get()) }

    viewModel { OverviewViewModel(get(), get()) }
    viewModel { ServersViewModel(get(), get(), get(), get()) }
    viewModel { ContainersViewModel(get(), get(), get()) }
    viewModel { ProcessesViewModel(get(), get(), get()) }
    viewModel { TerminalViewModel(get(), get()) }
    viewModel { AuditViewModel(get()) }
    viewModel { SettingsViewModel(get(), get()) }
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
