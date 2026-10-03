package com.hifiplayer.presentation.playback

import androidx.compose.ui.graphics.ImageBitmap
import com.hifiplayer.domain.model.playback.RepeatMode

/**
 * The audio path as the app *measured* it, split exactly the way requirement 30 demands:
 * SOURCE (the file) and OUTPUT (what leaves the device), plus what the decode step produced in
 * between. Every field is nullable and renders as "no detectado" when the value was never
 * measured — the UI is not allowed to fill the gaps with guesses.
 */
data class AudioPathUi(
    val sourceFormatLabel: String? = null,
    val sourceCodecName: String? = null,
    val sourceChannels: String? = null,
    val sourceBitrate: String? = null,
    val sourceIsLossless: Boolean? = null,
    val decodedFormatLabel: String? = null,
    val outputFormatLabel: String? = null,
    val outputDeviceName: String? = null,
    val outputDeviceType: String? = null,
    val outputStatusLabel: String = "—",
    val decoderName: String? = null,
    val resampled: Boolean = false,
    val downmixed: Boolean = false,
    val bitPerfect: BitPerfectBanner = BitPerfectBanner(),
    val dspChainDescription: String = "",
    val replayGainLabel: String? = null,
    val eqActive: Boolean = false,
    val crossfeedLabel: String? = null,
    val appliedGainLabel: String? = null,
    val clippingPrevented: Boolean = false,
    val note: String? = null,
)

/** Verbatim state of the bit-perfect banner, so nothing is re-worded between engine and screen. */
data class BitPerfectBanner(
    val requested: Boolean = false,
    val isActive: Boolean = false,
    val title: String = "",
    val detail: String = "",
)

data class NowPlayingUiState(
    val hasTrack: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val year: Int? = null,
    val trackNumberLabel: String? = null,
    val fileSummary: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val statusLabel: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    /** 0f..1f, drive by the slider. While the user drags, this is the dragged value. */
    val progress: Float = 0f,
    val isScrubbing: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val queueSize: Int = 0,
    val artwork: ImageBitmap? = null,
    val artworkSourceLabel: String? = null,
    val artworkLoading: Boolean = false,
    val audio: AudioPathUi = AudioPathUi(),
    /** One-shot user-facing message (errors and confirmations). Never a silent failure. */
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    val repeatLabel: String
        get() = when (repeatMode) {
            RepeatMode.OFF -> "Repetir: apagado"
            RepeatMode.ALL -> "Repetir: toda la cola"
            RepeatMode.ONE -> "Repetir: esta pista"
        }
}
