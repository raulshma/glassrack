package com.example.glassrack.di

import com.example.glassrack.core.database.DatabasePlatformContext
import com.example.glassrack.core.security.BiometricGate
import com.example.glassrack.core.security.SecureVault
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformSecurityModule(): Module = module {
    single { DatabasePlatformContext(androidContext()) }
    single { SecureVault(androidContext()) }
    single { BiometricGate(androidContext()) }
}
