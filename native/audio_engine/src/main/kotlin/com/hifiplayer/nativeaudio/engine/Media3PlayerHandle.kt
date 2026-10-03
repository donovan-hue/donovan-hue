package com.hifiplayer.nativeaudio.engine

import androidx.media3.common.Player

/**
 * Escape hatch for the pieces that are *obliged* to speak Media3: `MediaSessionService` builds a
 * session around a `Player`, and `MediaController` mirrors it.
 *
 * It is deliberately a separate interface instead of a member of [AudioEngine]: the domain and the
 * presentation layer must stay replaceable, so only the infrastructure that owns the session uses
 * this. If the engine is ever swapped for a non-Media3 one, that piece of infrastructure is the
 * only thing to adapt.
 */
interface Media3PlayerHandle {
    /** The live player. Never null once the engine is constructed. */
    val media3Player: Player
}
