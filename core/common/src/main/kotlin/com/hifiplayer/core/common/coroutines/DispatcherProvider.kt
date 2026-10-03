package com.hifiplayer.core.common.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Every repository/engine class receives a [DispatcherProvider] instead of touching
 * [Dispatchers] directly, so tests can swap in a deterministic dispatcher.
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
    /** Single-threaded lane for playback state mutations that must stay ordered. */
    val playback: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main.immediate
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val playback: CoroutineDispatcher get() = Dispatchers.Default.limitedParallelism(1)
}
