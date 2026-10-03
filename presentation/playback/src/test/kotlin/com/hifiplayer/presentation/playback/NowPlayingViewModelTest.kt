package com.hifiplayer.presentation.playback

import com.google.common.truth.Truth.assertThat
import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.logging.NoOpLogger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.audio.Codec
import com.hifiplayer.domain.model.audio.AudioFormatSpec
import com.hifiplayer.domain.model.error.AppError
import com.hifiplayer.domain.model.error.PlaybackError
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.playback.PlaybackState
import com.hifiplayer.domain.model.playback.PlaybackStatus
import com.hifiplayer.domain.model.playback.QueueInsertPosition
import com.hifiplayer.domain.model.playback.QueueOrigin
import com.hifiplayer.domain.model.playback.RepeatMode
import com.hifiplayer.domain.model.settings.AppSettings
import com.hifiplayer.domain.model.settings.AppearanceSettings
import com.hifiplayer.domain.model.settings.DspSettings
import com.hifiplayer.domain.model.settings.EqPreset
import com.hifiplayer.domain.model.settings.EqSettings
import com.hifiplayer.domain.model.settings.LibrarySettings
import com.hifiplayer.domain.model.settings.PlaybackSettings
import com.hifiplayer.domain.model.settings.ReplayGainSettings
import com.hifiplayer.domain.repository.ArtworkRepository
import com.hifiplayer.domain.repository.PlaybackRepository
import com.hifiplayer.domain.repository.SettingsRepository
import com.hifiplayer.domain.model.library.ArtworkSource
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Now Playing screen, and the one performance promise it makes (requirement 36).
 *
 * The playhead moves twice per second. If that moved the whole screen state, the artwork and every
 * button would rebuild 120 times a minute for a number that only one label displays. These tests pin
 * down that [NowPlayingViewModel.visuals] stays still while the playhead moves, that the playhead has
 * its own flows, and that seeking only reaches the engine when the finger leaves the bar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelTest {

    private val playback = FakePlayback()
    private val settings = FakeSettings()
    private val artwork = FakeArtwork()

    private lateinit var viewModel: NowPlayingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = NowPlayingViewModel(
            observePlaybackState = ObservePlaybackStateUseCase(playback),
            observeSettings = ObserveSettingsUseCase(settings),
            togglePlayPause = TogglePlayPauseUseCase(playback),
            nextTrack = NextTrackUseCase(playback),
            previousTrack = PreviousTrackUseCase(playback),
            seek = SeekUseCase(playback),
            setShuffle = SetShuffleUseCase(playback),
            setRepeatMode = SetRepeatModeUseCase(playback),
            resolveArtwork = ResolveTrackArtworkUseCase(artwork),
            loadArtworkBytes = LoadArtworkBytesUseCase(artwork),
            dispatchers = TestDispatchers,
            logger = NoOpLogger,
        )
        playback.stateFlow.value = PlaybackState(
            status = PlaybackStatus.READY,
            isPlaying = true,
            currentTrack = TRACK,
            durationMs = 100_000L,
            positionMs = 0L,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun kotlinx.coroutines.test.TestScope.collecting(): Int {
        var emissions = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.visuals.collect { emissions++ }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.progress.collect { }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.positionMs.collect { }
        }
        return emissions
    }

    @Test
    fun `the playhead moving does not produce a new screen state`() = runTest {
        collecting()
        val before = viewModel.visuals.value

        playback.stateFlow.value = playback.stateFlow.value.copy(positionMs = 500L)
        playback.stateFlow.value = playback.stateFlow.value.copy(positionMs = 1_000L)
        playback.stateFlow.value = playback.stateFlow.value.copy(positionMs = 1_500L)

        assertThat(viewModel.visuals.value).isEqualTo(before)
        assertThat(viewModel.visuals.value.positionMs).isEqualTo(0L)
    }

    @Test
    fun `the playhead does reach its own flows`() = runTest {
        collecting()

        playback.stateFlow.value = playback.stateFlow.value.copy(positionMs = 1_500L)

        assertThat(viewModel.positionMs.value).isEqualTo(1_500L)
        assertThat(viewModel.progress.value).isWithin(FLOAT_TOLERANCE).of(0.015f)
    }

    @Test
    fun `a real change still reaches the screen state`() = runTest {
        collecting()

        playback.stateFlow.value = playback.stateFlow.value.copy(isPlaying = false)

        assertThat(viewModel.visuals.value.isPlaying).isFalse()
    }

    @Test
    fun `dragging the bar does not seek until the finger is lifted`() = runTest {
        collecting()
        viewModel.onScrubStart()
        viewModel.onScrubChange(0.5f)

        assertThat(playback.seeks).isEmpty()

        viewModel.onScrubFinish()

        assertThat(playback.seeks).containsExactly(50_000L)
    }

    @Test
    fun `a failed action is told to the user, never swallowed`() = runTest {
        collecting()
        playback.failNext = PlaybackError.Unknown(errorCodeName = "ENGINE")

        viewModel.onTogglePlayPause()

        assertThat(viewModel.visuals.value.message).isNotNull()
        assertThat(viewModel.visuals.value.messageIsError).isTrue()
    }

    private companion object {
        const val FLOAT_TOLERANCE = 0.0001f

        val TRACK = Track(
            id = "t1",
            uri = "content://music/1.flac",
            title = "So What",
            artist = "Miles Davis",
            albumArtist = "Miles Davis",
            album = "Kind of Blue",
            albumId = "alb1",
            artistId = "art1",
            durationMs = 100_000L,
            trackNumber = 1,
            discNumber = 1,
            year = 1959,
            genre = "Jazz",
            sizeBytes = 10_000_000L,
            mimeType = "audio/flac",
            displayName = "1. So What.flac",
            relativePath = "Music/Kind of Blue/1. So What.flac",
            dateAddedEpochSec = 0L,
            lastModifiedEpochSec = 0L,
            format = AudioFormatSpec(sampleRateHz = 96_000, bitDepth = 24, channels = 2, codec = Codec.FLAC),
        )
    }
}

private object TestDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher = UnconfinedTestDispatcher()
    override val default: CoroutineDispatcher = UnconfinedTestDispatcher()
    override val io: CoroutineDispatcher = UnconfinedTestDispatcher()
    override val playback: CoroutineDispatcher = UnconfinedTestDispatcher()
}

// ---------------------------------------------------------------------------------- test doubles

private class FakePlayback : PlaybackRepository {

    val stateFlow = MutableStateFlow(PlaybackState())

    override val state: StateFlow<PlaybackState> get() = stateFlow

    val seeks = mutableListOf<Long>()
    var failNext: AppError? = null

    override suspend fun seekTo(positionMs: Long): Outcome<Unit> {
        seeks += positionMs
        return done()
    }

    override suspend fun playTracks(tracks: List<Track>, startIndex: Int, origin: QueueOrigin) = done()
    override suspend fun playTrack(track: Track, origin: QueueOrigin) = done()
    override suspend fun playPause() = done()
    override suspend fun play() = done()
    override suspend fun pause() = done()
    override suspend fun stop() = done()
    override suspend fun next() = done()
    override suspend fun previous() = done()
    override suspend fun setRepeatMode(mode: RepeatMode) = done()
    override suspend fun setShuffle(enabled: Boolean) = done()
    override suspend fun setGapless(enabled: Boolean) = done()
    override suspend fun addToQueue(track: Track, position: QueueInsertPosition) = done()
    override suspend fun addTracksToQueue(tracks: List<Track>, position: QueueInsertPosition) = done()
    override suspend fun removeFromQueue(index: Int) = done()
    override suspend fun moveInQueue(fromIndex: Int, toIndex: Int) = done()
    override suspend fun clearQueue() = done()
    override suspend fun playQueueIndex(index: Int) = done()
    override suspend fun saveQueue() = done()
    override suspend fun restoreQueue(): Outcome<Boolean> = Outcome.Success(false)

    private fun done(): Outcome<Unit> {
        val error = failNext
        return if (error == null) Outcome.Success(Unit) else Outcome.Failure(error)
    }
}

private class FakeSettings : SettingsRepository {

    private val state = MutableStateFlow(AppSettings.DEFAULT)
    override val settings: StateFlow<AppSettings> = state

    override suspend fun updatePlayback(transform: (PlaybackSettings) -> PlaybackSettings) = write { it.copy(playback = transform(it.playback)) }
    override suspend fun updateReplayGain(transform: (ReplayGainSettings) -> ReplayGainSettings) = write { it.copy(replayGain = transform(it.replayGain)) }
    override suspend fun updateEq(transform: (EqSettings) -> EqSettings) = write { it.copy(eq = transform(it.eq)) }
    override suspend fun updateDsp(transform: (DspSettings) -> DspSettings) = write { it.copy(dsp = transform(it.dsp)) }
    override suspend fun updateLibrary(transform: (LibrarySettings) -> LibrarySettings) = write { it.copy(library = transform(it.library)) }
    override suspend fun updateAppearance(transform: (AppearanceSettings) -> AppearanceSettings) = write { it.copy(appearance = transform(it.appearance)) }
    override suspend fun saveEqPreset(preset: EqPreset): Outcome<EqPreset> = Outcome.Success(preset)
    override suspend fun deleteEqPreset(presetId: String): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun resetToDefaults(): Outcome<Unit> = Outcome.Success(Unit)

    private fun write(transform: (AppSettings) -> AppSettings): Outcome<Unit> {
        state.value = transform(state.value)
        return Outcome.Success(Unit)
    }
}

/** No artwork in these tests: what is under test is the state, not the covers. */
private class FakeArtwork : ArtworkRepository {
    override suspend fun resolveArtwork(trackId: String, uri: String, folderPath: String?): Outcome<ArtworkSource?> =
        Outcome.Success(null)

    override fun cacheKeyFor(source: ArtworkSource): String = source.key
    override suspend fun bytesFor(source: ArtworkSource, targetPx: Int): Outcome<ByteArray> = Outcome.Success(ByteArray(0))
    override suspend fun cachedBytes(source: ArtworkSource): ByteArray? = null
    override suspend fun clearArtworkCache(): Outcome<Long> = Outcome.Success(0L)
    override suspend fun cacheSizeBytes(): Long = 0L
}
