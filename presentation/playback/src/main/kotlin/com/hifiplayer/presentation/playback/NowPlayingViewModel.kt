package com.hifiplayer.presentation.playback

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.logging.Logger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.onFailure
import com.hifiplayer.domain.model.device.BitPerfectState
import com.hifiplayer.domain.model.library.ArtworkSource
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.playback.AudioOutputInfo
import com.hifiplayer.domain.model.playback.PlaybackState
import com.hifiplayer.domain.model.playback.PlaybackStatus
import com.hifiplayer.domain.model.playback.RepeatMode
import com.hifiplayer.domain.model.settings.AppSettings
import com.hifiplayer.domain.usecase.library.LoadArtworkBytesUseCase
import com.hifiplayer.domain.usecase.library.ResolveTrackArtworkUseCase
import com.hifiplayer.domain.usecase.playback.NextTrackUseCase
import com.hifiplayer.domain.usecase.playback.ObservePlaybackStateUseCase
import com.hifiplayer.domain.usecase.playback.PreviousTrackUseCase
import com.hifiplayer.domain.usecase.playback.SeekUseCase
import com.hifiplayer.domain.usecase.playback.SetRepeatModeUseCase
import com.hifiplayer.domain.usecase.playback.SetShuffleUseCase
import com.hifiplayer.domain.usecase.playback.TogglePlayPauseUseCase
import com.hifiplayer.domain.usecase.settings.ObserveSettingsUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Now Playing (requirement 24; Rule 47 step 4).
 *
 * The ViewModel is a translator, not a decision maker: playback state comes from the repository,
 * the audio path comes from what the engine measured, artwork comes from the artwork pipeline.
 * Every control maps to exactly one use case, so there is no second opinion about playback
 * anywhere in the UI.
 */
class NowPlayingViewModel(
    private val observePlaybackState: ObservePlaybackStateUseCase,
    observeSettings: ObserveSettingsUseCase,
    private val togglePlayPause: TogglePlayPauseUseCase,
    private val nextTrack: NextTrackUseCase,
    private val previousTrack: PreviousTrackUseCase,
    private val seek: SeekUseCase,
    private val setShuffle: SetShuffleUseCase,
    private val setRepeatMode: SetRepeatModeUseCase,
    private val resolveArtwork: ResolveTrackArtworkUseCase,
    private val loadArtworkBytes: LoadArtworkBytesUseCase,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger = AppLogger.logger(),
) : ViewModel() {

    /** The current track, kept as its own flow so artwork work never fights the UI state. */
    private val currentTrack = MutableStateFlow<Track?>(null)

    private val artworkState = MutableStateFlow<ArtworkState>(ArtworkState.None)

    /** Non-null while the finger is on the seek bar: the position must not fight the user. */
    private val scrubFraction = MutableStateFlow<Float?>(null)

    private val message = MutableStateFlow<Message?>(null)

    private var artworkJob: Job? = null

    val state: StateFlow<NowPlayingUiState> = combine(
        observePlaybackState(),
        observeSettings(),
        artworkState,
        scrubFraction,
        message,
    ) { playback, settings, artwork, scrub, currentMessage ->
        buildState(playback, settings, artwork, scrub, currentMessage)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NowPlayingUiState(),
    )

    init {
        viewModelScope.launch {
            observePlaybackState()
                .map { it.currentTrack?.id }
                .distinctUntilChanged()
                .collect { trackId ->
                    val track = observePlaybackState().value.currentTrack
                    currentTrack.value = track
                    if (trackId == null) {
                        artworkJob?.cancel()
                        artworkState.value = ArtworkState.None
                    } else {
                        reloadArtwork(track)
                    }
                }
        }

        // Playback errors are surfaced to the user instead of being swallowed (requirement 31).
        viewModelScope.launch {
            observePlaybackState()
                .map { it.error?.userMessage }
                .distinctUntilChanged()
                .collect { errorMessage ->
                    if (errorMessage != null) message.value = Message(errorMessage, isError = true)
                }
        }
    }

    // ------------------------------------------------------------------ controls

    fun onTogglePlayPause() = launchAction { togglePlayPause() }

    fun onNext() = launchAction { nextTrack() }

    fun onPrevious() = launchAction { previousTrack() }

    fun onShuffleToggle() = launchAction { setShuffle(!state.value.shuffleEnabled) }

    fun onRepeatCycle() = launchAction {
        val next = when (state.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        setRepeatMode(next)
    }

    fun onScrubStart() {
        scrubFraction.value = state.value.progress
    }

    fun onScrubChange(fraction: Float) {
        scrubFraction.value = fraction.coerceIn(0f, 1f)
    }

    /** Only here does the seek actually reach the engine: dragging stays local and cheap. */
    fun onScrubFinish() {
        val fraction = scrubFraction.value ?: return
        scrubFraction.value = null
        val duration = state.value.durationMs
        if (duration <= 0L) return
        launchAction { seek((duration * fraction.toDouble()).toLong()) }
    }

    fun onDismissMessage() {
        message.value = null
    }

    // ------------------------------------------------------------------ internals

    private fun launchAction(block: suspend () -> Outcome<*>) {
        viewModelScope.launch {
            block().onFailure { error ->
                logger.w(TAG, "Acción de reproducción fallida: ${error.code}")
                message.value = Message(error.userMessage, isError = true)
            }
        }
    }

    private fun reloadArtwork(track: Track?) {
        artworkJob?.cancel()
        if (track == null) {
            artworkState.value = ArtworkState.None
            return
        }
        artworkJob = viewModelScope.launch {
            artworkState.value = ArtworkState.Loading
            val source = resolveArtwork(track).getOrNull()
            val bitmap = source?.let { decodeArtwork(it) }
            artworkState.value = if (source != null && bitmap != null) {
                ArtworkState.Ready(bitmap, source)
            } else {
                artworkState.value = ArtworkState.None
                ArtworkState.None
            }
        }
    }

    private suspend fun decodeArtwork(source: ArtworkSource): ImageBitmap? {
        val bytes = loadArtworkBytes(source, ARTWORK_TARGET_PX).getOrNull() ?: return null
        return withContext(dispatchers.default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }

    private fun buildState(
        playback: PlaybackState,
        settings: AppSettings,
        artwork: ArtworkState,
        scrub: Float?,
        currentMessage: Message?,
    ): NowPlayingUiState {
        val track = playback.currentTrack
        val duration = if (playback.durationMs > 0L) playback.durationMs else track?.durationMs ?: 0L
        val progress = scrub ?: if (duration > 0L) {
            (playback.positionMs.toDouble() / duration.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        return NowPlayingUiState(
            hasTrack = track != null,
            title = track?.displayTitle ?: "",
            artist = track?.artist ?: "Artista desconocido",
            album = track?.album.orEmpty(),
            year = track?.year,
            trackNumberLabel = track?.trackNumber?.let { number -> "Pista $number" },
            fileSummary = track?.let(::describeFile),
            isPlaying = playback.isPlaying,
            isBuffering = playback.status == PlaybackStatus.BUFFERING,
            statusLabel = playback.status.displayName,
            positionMs = playback.positionMs,
            durationMs = duration,
            bufferedMs = playback.bufferedMs,
            progress = progress,
            isScrubbing = scrub != null,
            shuffleEnabled = playback.shuffleEnabled,
            repeatMode = playback.repeatMode,
            hasNext = playback.hasNext,
            hasPrevious = playback.hasPrevious,
            queueSize = playback.queue.size,
            artwork = (artwork as? ArtworkState.Ready)?.bitmap,
            artworkSourceLabel = (artwork as? ArtworkState.Ready)?.source?.priority?.displayName,
            artworkLoading = artwork is ArtworkState.Loading,
            audio = buildAudioPath(playback.audioInfo, settings),
            message = currentMessage?.text,
            messageIsError = currentMessage?.isError == true,
        )
    }

    /** The one-line summary of the file itself: codec, resolution and bitrate as measured. */
    private fun describeFile(track: Track): String {
        val format = track.format ?: return track.displayName
        return buildString {
            append(format.codec.displayName)
            append(" · ")
            append(format.label)
            if (format.isVariableBitrate) append(" · VBR")
            format.bitrateKbps?.let { append(" · ").append(it).append(" kbps") }
        }
    }


    private sealed interface ArtworkState {
        data object None : ArtworkState
        data object Loading : ArtworkState
        data class Ready(val bitmap: ImageBitmap, val source: ArtworkSource) : ArtworkState
    }

    private data class Message(val text: String, val isError: Boolean)

    companion object {
        private const val TAG = "NowPlaying"

        /**
         * Cover size requested to the artwork pipeline. Big enough for the largest phone panel at
         * 3x, small enough that a 4000 px embedded JPEG is never decoded at full size
         * (requirement 20).
         */
        const val ARTWORK_TARGET_PX = 640
    }
}
