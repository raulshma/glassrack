package com.raulshma.grack.widget

import com.raulshma.grack.core.ssh.ContainerStateCache
import com.raulshma.grack.core.ssh.ScheduledRunCoordinator
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/** Android-only bindings contributed by the app on top of the shared Koin modules. */
val grackAndroidModule: Module = module {
    single<ContainerStateCache> { WidgetContainerCache(androidContext()) }
    // Phase 4 scheduler engine; consumed by ScheduledRunWorker and the WorkScheduler.
    single { ScheduledRunCoordinator(get(), get(), get()) }
}
