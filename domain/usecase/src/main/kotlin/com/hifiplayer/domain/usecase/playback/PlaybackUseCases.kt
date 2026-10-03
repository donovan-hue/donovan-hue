package com.hifiplayer.domain.usecase.playback

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.playback.PlaybackState
import com.hifiplayer.domain.model.playback.QueueOrigin
import com.hifiplayer.domain.model.playback.RepeatMode
import com.hifiplayer.domain.repository.MusicRepository
import com.hifiplayer.domain.repository.PlaybackRepository
import kotlinx.coroutines.flow.StateFlow

/** Transport use cases (phases 3-5). */
class PlayTracksUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(
        tracks: List<Track>,
        startIndex: Int = 0,
        origin: QueueOrigin = QueueOrigin.SINGLE,
    ): Outcome<Unit> {
        if (tracks.isEmpty()) return Outcome.Failure(
            com.hifiplayer.domain.model.error.IllegalState("No hay pistas para reproducir"),
        )
        return playback.playTracks(tracks, startIndex.coerceIn(0, tracks.lastIndex), origin)
    }
}

class PlayTrackUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(track: Track, origin: QueueOrigin = QueueOrigin.SINGLE): Outcome<Unit> =
        playback.playTracks(listOf(track), 0, origin)
}

class TogglePlayPauseUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(): Outcome<Unit> = playback.playPause()
}

class PlayUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(): Outcome<Unit> = playback.play()
}

class PauseUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(): Outcome<Unit> = playback.pause()
}

class StopPlaybackUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(): Outcome<Unit> = playback.stop()
}

class NextTrackUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(): Outcome<Unit> = playback.next()
}

class PreviousTrackUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(): Outcome<Unit> = playback.previous()
}

class SeekUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(positionMs: Long): Outcome<Unit> =
        playback.seekTo(positionMs.coerceAtLeast(0L))
}

class SetRepeatModeUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(mode: RepeatMode): Outcome<Unit> = playback.setRepeatMode(mode)

    /** Cycles OFF → ALL → ONE, matching the single repeat button in the player. */
    suspend fun cycle(current: RepeatMode): Outcome<Unit> = playback.setRepeatMode(
        when (current) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        },
    )
}

class SetShuffleUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(enabled: Boolean): Outcome<Unit> = playback.setShuffle(enabled)
}

class SetGaplessUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(enabled: Boolean): Outcome<Unit> = playback.setGapless(enabled)
}

class ObservePlaybackStateUseCase(private val playback: PlaybackRepository) {
    operator fun invoke(): StateFlow<PlaybackState> = playback.state
}

class SaveQueueUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(): Outcome<Unit> = playback.saveQueue()
}

class RestoreQueueUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(): Outcome<Boolean> = playback.restoreQueue()
}

/**
 * Records playback history once a track has been meaningfully heard, so "Reproducido
 * recientemente" reflects real listening rather than every skip.
 */
class RecordPlaybackUseCase(
    private val music: MusicRepository,
    private val minimumFraction: Float = 0.3f,
    private val minimumMs: Long = 30_000L,
) {
    suspend operator fun invoke(trackId: String, positionMs: Long, durationMs: Long): Outcome<Unit> {
        if (durationMs <= 0L) return Outcome.Success(Unit)
        val fraction = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        val enoughTime = positionMs >= minimumMs || fraction >= minimumFraction
        if (!enoughTime) return Outcome.Success(Unit)
        return music.recordPlayback(trackId, fraction)
    }
}
