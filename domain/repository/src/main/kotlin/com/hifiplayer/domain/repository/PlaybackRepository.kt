package com.hifiplayer.domain.repository

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.playback.PlaybackState
import com.hifiplayer.domain.model.playback.QueueInsertPosition
import com.hifiplayer.domain.model.playback.QueueOrigin
import com.hifiplayer.domain.model.playback.RepeatMode
import com.hifiplayer.domain.model.library.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * Playback control surface (requirement 7). The engine (Media3 today, anything else tomorrow)
 * implements [com.hifiplayer.nativeaudio.engine.AudioEngine]; this repository is what the
 * domain and the UI depend on, so swapping engines never touches business logic.
 */
interface PlaybackRepository {

    val state: StateFlow<PlaybackState>

    // ---------------- transport ----------------

    suspend fun playTracks(tracks: List<Track>, startIndex: Int = 0, origin: QueueOrigin = QueueOrigin.SINGLE): Outcome<Unit>

    suspend fun playTrack(track: Track, origin: QueueOrigin = QueueOrigin.SINGLE): Outcome<Unit>

    suspend fun playPause(): Outcome<Unit>

    suspend fun play(): Outcome<Unit>

    suspend fun pause(): Outcome<Unit>

    suspend fun stop(): Outcome<Unit>

    suspend fun next(): Outcome<Unit>

    suspend fun previous(): Outcome<Unit>

    /** Seek in ms; the engine clamps to the track duration. */
    suspend fun seekTo(positionMs: Long): Outcome<Unit>

    suspend fun setRepeatMode(mode: RepeatMode): Outcome<Unit>

    suspend fun setShuffle(enabled: Boolean): Outcome<Unit>

    suspend fun setGapless(enabled: Boolean): Outcome<Unit>

    // ---------------- queue ----------------

    suspend fun addToQueue(track: Track, position: QueueInsertPosition = QueueInsertPosition.END): Outcome<Unit>

    suspend fun addTracksToQueue(tracks: List<Track>, position: QueueInsertPosition = QueueInsertPosition.END): Outcome<Unit>

    suspend fun removeFromQueue(index: Int): Outcome<Unit>

    suspend fun moveInQueue(fromIndex: Int, toIndex: Int): Outcome<Unit>

    suspend fun clearQueue(): Outcome<Unit>

    suspend fun playQueueIndex(index: Int): Outcome<Unit>

    suspend fun saveQueue(): Outcome<Unit>

    /** Returns true when a previously saved queue was restored (requirement 7 + 27). */
    suspend fun restoreQueue(): Outcome<Boolean>
}
