package com.hifiplayer.nativeaudio.engine

import com.hifiplayer.domain.model.audio.ReplayGainInfo
import com.hifiplayer.domain.model.error.AppError
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.playback.AudioOutputInfo
import com.hifiplayer.domain.model.playback.PlaybackStatus
import com.hifiplayer.domain.model.playback.QueueInsertPosition
import com.hifiplayer.domain.model.playback.RepeatMode
import com.hifiplayer.domain.model.settings.AppSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the DSP chain needs to know at any instant.
 *
 * [bitPerfect] is decided outside the engine (the platform mixer attributes are verified by
 * `core:audio`), and the engine simply honours it: bit-perfect means the samples are passed
 * through untouched, with no conversion, no gain and no filtering (requirement 6).
 */
data class DspRuntimeConfig(
    val settings: AppSettings,
    val replayGain: ReplayGainInfo = ReplayGainInfo.EMPTY,
    val bitPerfect: Boolean = false,
)

/**
 * What the engine knows about the audio path of the *current* item. Every field is measured:
 * the decoded format comes from the samples Media3 actually feeds the sink, and the output side
 * comes from the verified bit-perfect state (requirement 30).
 */
data class EngineState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val currentIndex: Int = -1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val gaplessEnabled: Boolean = true,
    val playbackSpeed: Float = 1f,
    /** Name of the decoder that actually opened the file, straight from the platform. */
    val decoderName: String? = null,
    val audioInfo: AudioOutputInfo = AudioOutputInfo.UNKNOWN,
    val error: AppError? = null,
)

/**
 * The replaceable engine (requirement: "motor reemplazable sin tocar UI ni lógica de negocio").
 *
 * It speaks in domain models and never exposes Media3 types, so a future engine (or a
 * AudioTrack-based one) can implement the same surface without touching the repositories.
 */
interface AudioEngine {

    val state: StateFlow<EngineState>

    /** Replaces the whole queue. [startIndex] is clamped to the list bounds. */
    fun setQueue(tracks: List<Track>, startIndex: Int = 0, playWhenReady: Boolean = true)

    fun play()

    fun pause()

    fun stop()

    fun seekTo(positionMs: Long)

    fun next()

    fun previous()

    fun skipToIndex(index: Int)

    fun setRepeatMode(mode: RepeatMode)

    fun setShuffle(enabled: Boolean)

    fun setGapless(enabled: Boolean)

    fun addToQueue(tracks: List<Track>, position: QueueInsertPosition)

    fun removeAt(index: Int)

    fun move(fromIndex: Int, toIndex: Int)

    fun clearQueue()

    /** Snapshot of the queue as the engine sees it (source of truth while playing). */
    fun queueSnapshot(): List<Track>

    fun currentTrack(): Track?

    /** Applies new DSP settings; the chain is rebuilt for the current sample rate. */
    fun updateDsp(config: DspRuntimeConfig)

    /** Reports the verified bit-perfect state so the UI banner tells the truth. */
    fun updateBitPerfect(state: com.hifiplayer.domain.model.device.BitPerfectState)

    /** 0f..1f, applied in the digital domain only when the user asked for app gain. */
    fun setVolume(fraction: Float)

    fun release()
}
