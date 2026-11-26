package com.raulshma.grack.widget

import com.raulshma.grack.core.ssh.ContainerStateCache
import com.raulshma.grack.core.ssh.ScheduledRunCoordinator
import com.raulshma.grack.core.terminal.SshSessionNotifier
import com.raulshma.grack.feature.servers.ContentResolverTransferStreams
import com.raulshma.grack.feature.servers.TransferStreams
import com.raulshma.grack.notify.AndroidSshSessionNotifier
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/** Android-only bindings contributed by the app on top of the shared Koin modules. */
val grackAndroidModule: Module = module {
    single<ContainerStateCache> { WidgetContainerCache(androidContext()) }
    // Phase 4 scheduler engine; consumed by ScheduledRunWorker and the WorkScheduler.
    single { ScheduledRunCoordinator(get(), get(), get()) }
    // Phase 6 binary transfers: streamed through the Android ContentResolver.
    single<TransferStreams> { ContentResolverTransferStreams(androidContext()) }
    // Phase 7: foreground-service lifecycle + heads-ups for live terminal sessions.
    single<SshSessionNotifier> { AndroidSshSessionNotifier(androidContext()) }
}
