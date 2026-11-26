package com.raulshma.grack.feature.servers

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

actual val transferIoDispatcher: CoroutineContext = Dispatchers.IO
