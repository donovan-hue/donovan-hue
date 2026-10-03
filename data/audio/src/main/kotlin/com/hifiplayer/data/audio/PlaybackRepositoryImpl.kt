package com.hifiplayer.data.audio

import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.outcomeOf
import com.hifiplayer.core.common.error.TypedAppException
import com.hifiplayer.core.common.result.outcomeOfSync
import com.hifiplayer.core.common.time.TimeProvider
import com.hifiplayer.domain.model.audio.AudioFormatSpec
import com.hifiplayer.domain.model.device.BitPerfectBlocker
import com.hifiplayer.domain.model.device.BitPerfectState
import com.hifiplayer.domain.repository.AudioDeviceRepository
import com.hifiplayer.domain.repository.MusicRepository
import com.hifiplayer.domain.repository.PlaybackRepository
import com.hifiplayer.domain.repository.QueueRepository
import com.hifiplayer.domain.repository.SettingsRepository
import com.hifiplayer.nativeaudio.engine.AudioEngine
import com.hifiplayer.nativeaudio.engine.DspRuntimeConfig
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.playback.PlaybackState
import com.hifiplayer.domain.model.playback.QueueInsertPosition
import com.hifiplayer.domain.model.playback.QueueItem
import com.hifiplayer.domain.model.playback.QueueOrigin
import com.hifiplayer.domain.model.playback.RepeatMode
import com.hifiplayer.domain.model.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Requirement 7/27: the control surface the UI and the use cases talk to.
 *
 * This class is the only place where three things meet, and it keeps them in order:
 *  - the engine (playback itself),
 *  - the settings (DSP chain, bit-perfect preference) and
 *  - the hardware (verified bit-perfect state from `AudioDeviceRepository`).
 *
 * Every state change re-evaluates the audio policy, so the banner in the player never lags behind
 * what the engine is really doing.
 */
class PlaybackRepositoryImpl(
    private val engine: AudioEngine,
    private val queueRepository: QueueRepository,
    private val musicRepository: MusicRepository,
    private val settingsRepository: SettingsRepository,
    private val audioDeviceRepository: AudioDeviceRepository,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val timeProvider: TimeProvider = TimeProvider.System,
) : PlaybackRepository {

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val origin = MutableStateFlow(QueueOrigin.SINGLE)
    private val mutationMutex = Mutex()

    init {
        // 1) Mirror the engine state into the domain state.
        scope.launch(dispatchers.default) {
            engine.state.collect { engineState ->
                _state.value = PlaybackState(
                    status = engineState.status,
                    isPlaying = engineState.isPlaying,
                    currentTrack = engine.queueSnapshot().getOrNull(engineState.currentIndex),
                    queue = engine.queueSnapshot().map { it.toQueueItem() },
                    queueOrigin = origin.value,
                    currentIndex = engineState.currentIndex,
                    positionMs = engineState.positionMs,
                    durationMs = engineState.durationMs,
                    bufferedMs = engineState.bufferedMs,
                    repeatMode = engineState.repeatMode,
                    shuffleEnabled = engineState.shuffleEnabled,
                    gaplessEnabled = engineState.gaplessEnabled,
                    audioInfo = engineState.audioInfo,
                    error = engineState.error,
                )
            }
        }

        // 2) When the current track changes, the audio policy is recomputed: a different sample
        //    rate needs a different bit-perfect request and different ReplayGain values. The queue
        //    is persisted at the same moment, which is what makes a cold start resume the session.
        scope.launch(dispatchers.default) {
            engine.state
                .map { it.currentIndex }
                .distinctUntilChanged()
                .collect {
                    applyAudioPolicy()
                    saveQueue()
                }
        }

        // 3) Settings changes are pushed to the engine as they happen.
        scope.launch(dispatchers.default) {
            settingsRepository.settings.collect { applyAudioPolicy() }
        }
    }

    // ---------------------------------------------------------------- transport

    override suspend fun playTracks(
        tracks: List<Track>,
        startIndex: Int,
        origin: QueueOrigin,
    ): Outcome<Unit> = withContext(dispatchers.playback) {
        outcomeOf(TAG) {
            if (tracks.isEmpty()) {
                throw TypedAppException(
                    com.hifiplayer.domain.model.error.PlaybackError.Unknown(
                        errorCodeName = "EMPTY_QUEUE",
                        cause = "no hay pistas para reproducir",
                    ),
                )
            }
            val playable = tracks.filter { !it.uri.isBlank() }
            if (playable.isEmpty()) {
                throw TypedAppException(
                    com.hifiplayer.domain.model.error.PlaybackError.FileNotFound(
                        uri = "",
                        reason = "ninguna pista de la selección tiene archivo disponible",
                    ),
                )
            }
            this@PlaybackRepositoryImpl.origin.value = origin
            engine.setQueue(playable, startIndex.coerceIn(0, playable.lastIndex), playWhenReady = true)
            applyAudioPolicy()
        }
    }

    override suspend fun playTrack(track: Track, origin: QueueOrigin): Outcome<Unit> =
        playTracks(listOf(track), 0, origin)

    override suspend fun playPause(): Outcome<Unit> = withContext(dispatchers.playback) {
        outcomeOfSync(TAG) {
            if (engine.state.value.isPlaying) engine.pause() else engine.play()
        }
    }

    override suspend fun play(): Outcome<Unit> = withContext(dispatchers.playback) {
        outcomeOfSync(TAG) { engine.play() }
    }

    override suspend fun pause(): Outcome<Unit> = withContext(dispatchers.playback) {
        outcomeOfSync(TAG) { engine.pause() }
    }

    override suspend fun stop(): Outcome<Unit> = withContext(dispatchers.playback) {
        outcomeOfSync(TAG) { engine.stop() }
    }

    override suspend fun next(): Outcome<Unit> = withContext(dispatchers.playback) {
        outcomeOfSync(TAG) { engine.next() }
    }

    override suspend fun previous(): Outcome<Unit> = withContext(dispatchers.playback) {
        outcomeOfSync(TAG) { engine.previous() }
    }

    override suspend fun seekTo(positionMs: Long): Outcome<Unit> = withContext(dispatchers.playback) {
        outcomeOfSync(TAG) { engine.seekTo(positionMs) }
    }

    override suspend fun setRepeatMode(mode: RepeatMode): Outcome<Unit> = withContext(dispatchers.playback) {
        outcomeOf(TAG) {
            engine.setRepeatMode(mode)
            settingsRepository.updatePlayback { it.copy(repeatMode = mode.toSetting()) }
            Unit
        }
    }

    override suspend fun setShuffle(enabled: Boolean): Outcome<Unit> = withContext(dispatchers.playback) {
        outcomeOf(TAG) {
            engine.setShuffle(enabled)
            settingsRepository.updatePlayback { it.copy(shuffleEnabled = enabled) }
            Unit
        }
    }

    override suspend fun setGapless(enabled: Boolean): Outcome<Unit> = withContext(dispatchers.playback) {
        outcomeOf(TAG) {
            engine.setGapless(enabled)
            settingsRepository.updatePlayback { it.copy(gaplessEnabled = enabled) }
            Unit
        }
    }

    // ---------------------------------------------------------------- queue

    override suspend fun addToQueue(track: Track, position: QueueInsertPosition): Outcome<Unit> =
        addTracksToQueue(listOf(track), position)

    override suspend fun addTracksToQueue(tracks: List<Track>, position: QueueInsertPosition): Outcome<Unit> =
        withContext(dispatchers.playback) {
            outcomeOf(TAG) {
                if (tracks.isEmpty()) return@outcomeOf
                engine.addToQueue(tracks, position)
                saveQueue()
                Unit
            }
        }

    override suspend fun removeFromQueue(index: Int): Outcome<Unit> = withContext(dispatchers.playback) {
        outcomeOf(TAG) {
            engine.removeAt(index)
            saveQueue()
            Unit
        }
    }

    override suspend fun moveInQueue(fromIndex: Int, toIndex: Int): Outcome<Unit> = withContext(dispatchers.playback) {
        outcomeOf(TAG) {
            engine.move(fromIndex, toIndex)
            saveQueue()
            Unit
        }
    }

    override suspend fun clearQueue(): Outcome<Unit> = withContext(dispatchers.playback) {
        outcomeOf(TAG) {
            engine.clearQueue()
            queueRepository.clear()
            Unit
        }
    }

    override suspend fun playQueueIndex(index: Int): Outcome<Unit> = withContext(dispatchers.playback) {
        outcomeOf(TAG) { engine.skipToIndex(index) }
    }

    override suspend fun saveQueue(): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            val state = engine.state.value
            val items = engine.queueSnapshot()
            if (items.isEmpty()) {
                queueRepository.clear()
            } else {
                queueRepository.save(
                    QueueRepository.SavedQueue(
                        items = items.map { it.toQueueItem() },
                        currentIndex = state.currentIndex,
                        origin = origin.value,
                        shuffleEnabled = state.shuffleEnabled,
                        savedAtEpochMs = timeProvider.nowMs(),
                    ),
                )
            }
            Unit
        }
    }

    override suspend fun restoreQueue(): Outcome<Boolean> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            val saved = queueRepository.load() ?: return@outcomeOf false
            // The ids are resolved against Room: a track that was deleted in the meantime simply
            // disappears from the restored queue instead of becoming a broken entry.
            val tracks = musicRepository.tracksByIds(saved.items.map { it.trackId })
            if (tracks.isEmpty()) {
                queueRepository.clear()
                return@outcomeOf false
            }
            origin.value = QueueOrigin.RESTORED
            val index = saved.currentIndex.coerceIn(0, tracks.lastIndex)
            engine.setQueue(tracks, index, playWhenReady = false)
            engine.setShuffle(saved.shuffleEnabled)
            // The position inside the track is not restored: resuming in the middle of a song
            // without the user asking for it would be surprising (playback settings own that).
            true
        }
    }

    // ---------------------------------------------------------------- audio policy

    /**
     * Decides the audio path for the *current* track and tells the engine about it.
     *
     * Bit-perfect is never assumed: it is requested from the platform through
     * [AudioDeviceRepository], and the engine is only told it is active when the platform
     * confirmed it for this exact format.
     */
    private suspend fun applyAudioPolicy() {
        mutationMutex.withLock {
            val settings = settingsRepository.settings.value
            val currentState = engine.state.value
            val track = engine.queueSnapshot().getOrNull(currentState.currentIndex)
            val format = track?.format

            val dspActive = isDspEngaged(settings)
            val wantsBitPerfect = settings.playback.bitPerfectEnabled

            val bitPerfectState: BitPerfectState = if (format == null) {
                BitPerfectState(
                    requested = wantsBitPerfect,
                    isActive = false,
                    support = com.hifiplayer.domain.model.device.BitPerfectSupport.UNKNOWN,
                    blockers = if (wantsBitPerfect) {
                        listOf(BitPerfectBlocker.FORMAT_NOT_OFFERED_BY_DAC)
                    } else {
                        emptyList()
                    },
                    dspActive = dspActive,
                    dspChainDescription = chainDescription(settings),
                )
            } else if (!wantsBitPerfect) {
                BitPerfectState(
                    requested = false,
                    isActive = false,
                    support = com.hifiplayer.domain.model.device.BitPerfectSupport.UNKNOWN,
                    dspActive = dspActive,
                    dspChainDescription = chainDescription(settings),
                )
            } else {
                audioDeviceRepository
                    .activateBitPerfect(
                        format = format,
                        dspActive = dspActive,
                        dspChainDescription = chainDescription(settings),
                        // The engine holds its own volume at 1.0 while bit-perfect is active, so the
                        // only attenuation left would come from the system mixer — and bypassing that
                        // mixer is exactly what the platform attributes below are for.
                        volumeAttenuatesSignal = false,
                    )
                    .getOrElse { error ->
                        BitPerfectState(
                            requested = true,
                            isActive = false,
                            support = com.hifiplayer.domain.model.device.BitPerfectSupport.UNKNOWN,
                            blockers = listOf(BitPerfectBlocker.EXCLUSIVE_MODE_UNAVAILABLE),
                            dspActive = dspActive,
                            dspChainDescription = chainDescription(settings),
                            evidence = listOf(error.userMessage),
                        )
                    }
            }

            engine.updateBitPerfect(bitPerfectState)
            engine.updateDsp(
                DspRuntimeConfig(
                    settings = settings,
                    replayGain = track?.replayGain ?: com.hifiplayer.domain.model.audio.ReplayGainInfo.EMPTY,
                    bitPerfect = bitPerfectState.isActive,
                ),
            )
        }
    }

    /** DSP is "engaged" only when something would really change the samples. */
    private fun isDspEngaged(settings: AppSettings): Boolean {
        if (settings.eq.enabled && settings.eq.activeBandCount > 0 && settings.eq.hasAnyGain) return true
        if (settings.dsp.crossfeed.isEnabled) return true
        if (settings.replayGain.mode != com.hifiplayer.domain.model.audio.ReplayGainMode.OFF) return true
        if (settings.dsp.appGainDb != 0.0 || settings.dsp.preampDb != 0.0) return true
        if (settings.dsp.balance != 0.0) return true
        return false
    }

    private fun chainDescription(settings: AppSettings): String {
        val stages = mutableListOf<String>()
        stages += "Source"
        stages += "Decode"
        if (settings.replayGain.mode != com.hifiplayer.domain.model.audio.ReplayGainMode.OFF) stages += "ReplayGain"
        if (settings.eq.enabled && settings.eq.hasAnyGain) stages += "EQ"
        if (settings.dsp.crossfeed.isEnabled) stages += "Crossfeed"
        if (settings.dsp.appGainDb != 0.0 || settings.dsp.preampDb != 0.0 || settings.dsp.balance != 0.0) stages += "Gain"
        stages += "Output"
        return stages.joinToString(" → ")
    }

    private fun Track.toQueueItem() = QueueItem(
        trackId = id,
        title = displayTitle,
        artist = artist,
        album = album,
        durationMs = durationMs,
        codecId = codec.id,
        sampleRateHz = format?.sampleRateHz,
        bitDepth = format?.bitDepth,
        artworkUri = artworkUri,
    )

    private fun RepeatMode.toSetting() = when (this) {
        RepeatMode.OFF -> com.hifiplayer.domain.model.settings.RepeatModeSetting.OFF
        RepeatMode.ONE -> com.hifiplayer.domain.model.settings.RepeatModeSetting.ONE
        RepeatMode.ALL -> com.hifiplayer.domain.model.settings.RepeatModeSetting.ALL
    }

    private companion object {
        const val TAG = "PlaybackRepository"
    }
}
