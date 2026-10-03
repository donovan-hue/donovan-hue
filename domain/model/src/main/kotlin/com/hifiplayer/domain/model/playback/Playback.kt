package com.hifiplayer.domain.model.playback

import com.hifiplayer.domain.model.audio.AudioFormatSpec
import com.hifiplayer.domain.model.device.BitPerfectState
import com.hifiplayer.domain.model.error.AppError
import com.hifiplayer.domain.model.library.Track

enum class PlaybackStatus(val displayName: String) {
    IDLE("En reposo"),
    BUFFERING("Preparando"),
    READY("Listo"),
    PLAYING("Reproduciendo"),
    PAUSED("En pausa"),
    ENDED("Finalizado"),
    ERROR("Error"),
}

/** Requirement 7: shuffle / repeat (OFF / ONE / ALL). */
enum class RepeatMode(val displayName: String) {
    OFF("Sin repetición"),
    ONE("Repetir pista"),
    ALL("Repetir cola");
}

/** How the queue was built, so "next" is predictable and explainable to the user. */
enum class QueueOrigin(val displayName: String) {
    ALBUM("Álbum"),
    FOLDER("Carpeta"),
    ARTIST("Artista"),
    GENRE("Género"),
    PLAYLIST("Playlist"),
    ALL_TRACKS("Toda la biblioteca"),
    SEARCH("Resultado de búsqueda"),
    SINGLE("Una pista"),
    EXTERNAL("Contenido externo"),
    RESTORED("Sesión restaurada");
}

/** Where a "add to queue" action inserts the track (requirement 27). */
enum class QueueInsertPosition { NEXT, END }

data class QueueItem(
    val trackId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val codecId: String,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val artworkUri: String?,
) {
    val formatLabel: String
        get() = if (sampleRateHz != null && bitDepth != null) {
            "$bitDepth-bit / ${AudioFormatSpec.formatSampleRate(sampleRateHz)}"
        } else {
            ""
        }
}

/**
 * What is *actually* happening in the audio path, item by item (requirement 30). The
 * AudioInfoCard renders this struct as-is: SOURCE block + OUTPUT block + status.
 */
data class AudioOutputInfo(
    val sourceFormat: AudioFormatSpec? = null,
    val decodedFormat: AudioFormatSpec? = null,
    val outputFormat: AudioFormatSpec? = null,
    val bitPerfect: BitPerfectState = BitPerfectState(),
    val resampled: Boolean = false,
    val downmixed: Boolean = false,
    val replayGainDb: Double = 0.0,
    val replayGainMode: String? = null,
    val eqActive: Boolean = false,
    val crossfeedLabel: String? = null,
    val preampDb: Double = 0.0,
    val appliedGainDb: Double = 0.0,
    val clippingPrevented: Boolean = false,
    val outputDeviceName: String? = null,
    val outputDeviceType: String? = null,
    val decoderName: String? = null,
    val isDecodingLossy: Boolean = false,
    val message: String? = null,
) {
    /** "Direct" when nothing modified the signal, "Converted" when the platform did. */
    val outputStatusLabel: String
        get() = when {
            bitPerfect.isActive -> "Direct"
            resampled || downmixed -> "Converted"
            eqActive || replayGainDb != 0.0 -> "DSP Active"
            else -> "Direct"
        }

    companion object {
        val UNKNOWN: AudioOutputInfo = AudioOutputInfo()
    }
}

/**
 * Immutable snapshot of everything the player UI needs. Produced by the engine and exposed as
 * a StateFlow so rotation/backgrounding never loses state (requirement 37).
 */
data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val currentTrack: Track? = null,
    val queue: List<QueueItem> = emptyList(),
    val queueOrigin: QueueOrigin = QueueOrigin.SINGLE,
    val currentIndex: Int = -1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val gaplessEnabled: Boolean = true,
    val audioInfo: AudioOutputInfo = AudioOutputInfo.UNKNOWN,
    val error: AppError? = null,
) {
    val isIdle: Boolean get() = status == PlaybackStatus.IDLE

    val hasNext: Boolean
        get() = when {
            queue.isEmpty() -> false
            repeatMode == RepeatMode.ALL -> true
            else -> currentIndex in 0 until queue.lastIndex
        }

    val hasPrevious: Boolean
        get() = when {
            queue.isEmpty() -> false
            repeatMode == RepeatMode.ALL -> true
            else -> currentIndex > 0
        }

    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    val upNext: List<QueueItem>
        get() = if (currentIndex < 0) emptyList() else queue.drop(currentIndex + 1)

    companion object {
        val IDLE: PlaybackState = PlaybackState()
    }
}
