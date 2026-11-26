package com.raulshma.grack.di

import com.raulshma.grack.core.database.DatabasePlatformContext
import com.raulshma.grack.core.security.BiometricGate
import com.raulshma.grack.core.security.NotificationGate
import com.raulshma.grack.core.security.SecureVault
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformSecurityModule(): Module = module {
    single { DatabasePlatformContext(androidContext()) }
    single { SecureVault(androidContext()) }
    single { BiometricGate(androidContext()) }
    single { NotificationGate(androidContext()) }
}
