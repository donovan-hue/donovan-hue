package com.hifiplayer.nativeaudio.engine

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.hifiplayer.core.common.error.toAppError
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.dsp.pipeline.AudioProcessingPipeline
import com.hifiplayer.domain.model.audio.AudioFormatSpec
import com.hifiplayer.domain.model.device.BitPerfectState
import com.hifiplayer.domain.model.error.PlaybackError
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.playback.AudioOutputInfo
import com.hifiplayer.domain.model.playback.PlaybackStatus
import com.hifiplayer.domain.model.playback.QueueInsertPosition
import com.hifiplayer.domain.model.playback.RepeatMode
import com.hifiplayer.domain.model.settings.AppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Media3/ExoPlayer implementation of [AudioEngine] (requirement 3 and 7).
 *
 * Responsibilities, and nothing else:
 *  - own the player and the queue;
 *  - expose a domain-only state snapshot (no Media3 types leak out);
 *  - let the DSP chain and the verified bit-perfect state shape the audio path;
 *  - report what it *measured* (decoded format, decoder name) instead of what it hopes.
 *
 * Must be created on the main thread: ExoPlayer requires a Looper and the engine pins itself to
 * the main looper so playback control is never racing with the UI.
 */
class Media3AudioEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
) : AudioEngine, Media3PlayerHandle {

    private val pipeline = AudioProcessingPipeline()

    @Volatile
    private var dspConfig: DspRuntimeConfig = DspRuntimeConfig(AppSettings.DEFAULT)

    @Volatile
    private var bitPerfect: BitPerfectState = BitPerfectState()

    /** Format actually delivered to the sink, measured on the main thread. */
    @Volatile
    private var decodedFormat: AudioFormatSpec? = null

    @Volatile
    private var decoderName: String? = null

    /** Last playback error, kept so the UI can show it until it is resolved. */
    @Volatile
    private var lastError: com.hifiplayer.domain.model.error.AppError? = null

    private val queue = mutableListOf<Track>()

    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Required by [Media3PlayerHandle] so `MediaSessionService` can build the session. */
    override val media3Player: androidx.media3.common.Player get() = player

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLooper(Looper.getMainLooper())
        .setRenderersFactory(
            HiFiRenderersFactory(
                context = context,
                pipeline = pipeline,
                configProvider = { dspConfig },
                onDecodedFormat = ::onDecodedFormatMeasured,
            ),
        )
        // Generous buffer: local files, so a big read-ahead costs nothing and makes gapless
        // transitions immune to storage hiccups.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 15_000,
                    /* maxBufferMs = */ 60_000,
                    /* bufferForPlaybackMs = */ 1_000,
                    /* bufferForPlaybackAfterRebufferMs = */ 2_000,
                )
                .setBackBuffer(/* backBufferDurationMs = */ 15_000, /* retainBackBufferFromKeyframe = */ false)
                .build(),
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        // Unplugging headphones must pause, never blast the speaker (requirement 9).
        .setHandleAudioBecomingNoisy(true)
        .setSkipSilenceEnabled(false)
        .setSeekBackIncrementMs(10_000L)
        .build()

    private val ticker: Job = scope.launch(mainDispatcher) {
        while (isActive) {
            if (player.isPlaying || player.playbackState == Player.STATE_BUFFERING) {
                publish()
            }
            delay(POSITION_TICK_MS)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_IDLE) {
                    // IDLE after a failed preparation must keep the error visible.
                    publish()
                } else {
                    publish()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publish()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // A new item means a new source format: the decoder and the DSP chain are re-read.
                decoderName = null
                decodedFormat = null
                lastError = null
                publish()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                publish()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                publish()
            }

            override fun onPlayerError(error: PlaybackException) {
                lastError = error.toAppError(TAG)
                AppLogger.e(TAG, "Error de reproducción: ${error.errorCodeName}", error)
                publish()
            }
        })

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializationDurationMs: Long,
            ) {
                this@Media3AudioEngine.decoderName = decoderName
                publish()
            }
        })

        player.prepare()
    }

    // ---------------------------------------------------------------- queue

    override fun setQueue(tracks: List<Track>, startIndex: Int, playWhenReady: Boolean) {
        onMain {
            queue.clear()
            queue += tracks
            lastError = null
            if (tracks.isEmpty()) {
                player.stop()
                player.clearMediaItems()
                publish()
                return@onMain
            }
            val index = startIndex.coerceIn(0, tracks.lastIndex)
            player.setMediaItems(tracks.map { it.toMediaItem() }, index, 0L)
            player.prepare()
            player.playWhenReady = playWhenReady
            publish()
        }
    }

    override fun play() = onMain {
        if (queue.isEmpty()) return@onMain
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
        player.play()
        publish()
    }

    override fun pause() = onMain {
        player.pause()
        publish()
    }

    override fun stop() = onMain {
        player.stop()
        player.clearMediaItems()
        queue.clear()
        publish()
    }

    override fun seekTo(positionMs: Long) = onMain {
        val clamped = positionMs.coerceAtLeast(0L)
        player.seekTo(clamped)
        publish()
    }

    override fun next() = onMain {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            publish()
        }
    }

    override fun previous() = onMain {
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
            publish()
        } else {
            player.seekTo(0L)
            publish()
        }
    }

    override fun skipToIndex(index: Int) = onMain {
        if (index in queue.indices) {
            player.seekTo(index, 0L)
            publish()
        }
    }

    override fun setRepeatMode(mode: RepeatMode) = onMain {
        player.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
        publish()
    }

    override fun setShuffle(enabled: Boolean) = onMain {
        // The queue is kept in its original order; ExoPlayer's shuffle order is what changes, so
        // "up next" stays explainable and the saved queue is still meaningful.
        player.shuffleModeEnabled = enabled
        publish()
    }

    override fun setGapless(enabled: Boolean) = onMain {
        player.setPauseAtEndOfMediaItems(!enabled)
        publish()
    }

    override fun addToQueue(tracks: List<Track>, position: QueueInsertPosition) = onMain {
        if (tracks.isEmpty()) return@onMain
        val insertAt = when (position) {
            QueueInsertPosition.NEXT -> (player.currentMediaItemIndex + 1).coerceIn(0, queue.size)
            QueueInsertPosition.END -> queue.size
        }
        queue.addAll(insertAt, tracks)
        player.addMediaItems(insertAt, tracks.map { it.toMediaItem() })
        publish()
    }

    override fun removeAt(index: Int) = onMain {
        if (index !in queue.indices) return@onMain
        queue.removeAt(index)
        player.removeMediaItem(index)
        publish()
    }

    override fun move(fromIndex: Int, toIndex: Int) = onMain {
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return@onMain
        val moved = queue.removeAt(fromIndex)
        queue.add(toIndex, moved)
        player.moveMediaItem(fromIndex, toIndex)
        publish()
    }

    override fun clearQueue() = onMain {
        queue.clear()
        player.clearMediaItems()
        publish()
    }

    override fun queueSnapshot(): List<Track> = queue.toList()

    override fun currentTrack(): Track? = queue.getOrNull(player.currentMediaItemIndex)

    // ---------------------------------------------------------------- configuration

    override fun updateDsp(config: DspRuntimeConfig) {
        dspConfig = config
        onMain {
            // Re-configuring the chain recomputes filter coefficients for the current stream, so
            // toggling EQ mid-song takes effect immediately and without a click.
            val format = decodedFormat
            if (format != null) {
                pipeline.configure(
                    settings = config.settings,
                    replayGainInfo = config.replayGain,
                    sampleRateHz = format.sampleRateHz,
                    channels = format.channels,
                    bitPerfectRequested = config.bitPerfect,
                )
            }
            publish()
        }
    }

    override fun updateBitPerfect(state: BitPerfectState) {
        bitPerfect = state
        publish()
    }

    override fun setVolume(fraction: Float) = onMain {
        player.volume = fraction.coerceIn(0f, 1f)
        publish()
    }

    override fun release() {
        onMain {
            ticker.cancel()
            player.release()
            queue.clear()
        }
    }

    // ---------------------------------------------------------------- internals

    /** Called from the audio thread with the format the sink is really being fed. */
    private fun onDecodedFormatMeasured(sampleRateHz: Int, channels: Int, encoding: Int) {
        val codec = currentTrack()?.format?.codec ?: com.hifiplayer.domain.model.audio.Codec.UNKNOWN
        val bitDepth = when (encoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            C.ENCODING_PCM_FLOAT -> 32
            else -> currentTrack()?.format?.bitDepth ?: 0
        }
        decodedFormat = AudioFormatSpec(
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            channels = channels,
            codec = codec,
            pcmEncoding = com.hifiplayer.domain.model.audio.PcmEncoding.of(bitDepth, encoding == C.ENCODING_PCM_FLOAT),
        )
        onMain { publish() }
    }

    private fun publish() {
        val track = currentTrack()
        val position = if (queue.isEmpty()) 0L else player.currentPosition.coerceAtLeast(0L)
        val duration = track?.durationMs?.takeIf { it > 0L }
            ?: player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            ?: 0L

        _state.value = EngineState(
            status = player.playbackState.toStatus(),
            isPlaying = player.isPlaying,
            currentIndex = if (queue.isEmpty()) -1 else player.currentMediaItemIndex,
            positionMs = position,
            durationMs = duration,
            bufferedMs = if (queue.isEmpty()) 0L else player.bufferedPosition.coerceAtLeast(0L),
            repeatMode = player.repeatMode.toRepeatMode(),
            shuffleEnabled = player.shuffleModeEnabled,
            gaplessEnabled = !player.pauseAtEndOfMediaItems,
            playbackSpeed = player.playbackParameters.speed,
            decoderName = decoderName,
            audioInfo = buildAudioInfo(track),
            error = lastError,
        )
    }

    /**
     * Builds the honest description of the audio path:
     * SOURCE from the file header (probed), DECODED from what Media3 actually feeds the sink
     * (measured here), OUTPUT from the bit-perfect state verified against the platform.
     */
    private fun buildAudioInfo(track: Track?): AudioOutputInfo {
        val snapshot = pipeline.snapshot
        val decoded = decodedFormat
        val source = track?.format
        val resampled = source != null && decoded != null && source.sampleRateHz != decoded.sampleRateHz
        val downmixed = source != null && decoded != null && source.channels > decoded.channels

        return AudioOutputInfo(
            sourceFormat = source,
            decodedFormat = decoded ?: source,
            outputFormat = bitPerfect.deliveredFormat ?: decoded ?: source,
            bitPerfect = bitPerfect,
            resampled = resampled,
            downmixed = downmixed,
            replayGainDb = snapshot.replayGainAppliedDb,
            replayGainMode = snapshot.replayGainMode.name,
            eqActive = snapshot.eqEnabled && snapshot.eqActiveBands > 0,
            crossfeedLabel = snapshot.crossfeedLabel.takeIf { snapshot.crossfeedEnabled },
            preampDb = snapshot.preampDb,
            appliedGainDb = snapshot.effectiveGainDb,
            clippingPrevented = snapshot.replayGainClippingReductionDb != 0.0,
            outputDeviceName = bitPerfect.routeName,
            outputDeviceType = null,
            decoderName = decoderName,
            isDecodingLossy = source?.isLossless == false,
            message = snapshot.replayGainExplanation.takeIf { it.isNotBlank() },
        )
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            // Post to the player's looper: ExoPlayer is not thread safe.
            android.os.Handler(Looper.getMainLooper()).post { block() }
        }
    }

    private companion object {
        const val TAG = "Media3AudioEngine"
        const val POSITION_TICK_MS = 500L
    }
}

// ---------------------------------------------------------------- small mappers

private fun Int.toStatus(): PlaybackStatus = when (this) {
    Player.STATE_IDLE -> PlaybackStatus.IDLE
    Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
    Player.STATE_READY -> PlaybackStatus.READY
    Player.STATE_ENDED -> PlaybackStatus.ENDED
    else -> PlaybackStatus.IDLE
}

private fun Int.toRepeatMode(): RepeatMode = when (this) {
    Player.REPEAT_MODE_OFF -> RepeatMode.OFF
    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
    else -> RepeatMode.OFF
}
