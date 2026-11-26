package com.raulshma.grack.feature.servers

import kotlin.coroutines.CoroutineContext

/**
 * KMP-safe dispatcher seam for the file-transfer plumbing (name/size probes and stream
 * opens in [ServersViewModel]). `Dispatchers.IO` is JVM-only in this coroutines version,
 * so the default is resolved per target: the real IO pool on Android, the default pool on
 * iOS. Tests inject their own context through the constructor instead.
 */
expect val transferIoDispatcher: CoroutineContext
