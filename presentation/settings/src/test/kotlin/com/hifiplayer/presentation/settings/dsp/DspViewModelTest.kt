package com.hifiplayer.presentation.settings.dsp

import com.google.common.truth.Truth.assertThat
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.audio.CrossfeedMode
import com.hifiplayer.domain.model.device.BitPerfectState
import com.hifiplayer.domain.model.audio.ReplayGainMode
import com.hifiplayer.domain.model.playback.PlaybackState
import com.hifiplayer.domain.model.library.Track
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
import com.hifiplayer.domain.repository.PlaybackRepository
import com.hifiplayer.domain.repository.SettingsRepository
import com.hifiplayer.domain.usecase.dsp.DeleteEqPresetUseCase
import com.hifiplayer.domain.usecase.dsp.SaveEqPresetUseCase
import com.hifiplayer.domain.usecase.dsp.SetAppGainUseCase
import com.hifiplayer.domain.usecase.dsp.SetBalanceUseCase
import com.hifiplayer.domain.usecase.dsp.SetCrossfeedUseCase
import com.hifiplayer.domain.usecase.dsp.SetEqEnabledUseCase
import com.hifiplayer.domain.usecase.dsp.SetEqPresetUseCase
import com.hifiplayer.domain.usecase.dsp.SetPreampUseCase
import com.hifiplayer.domain.usecase.dsp.SetReplayGainModeUseCase
import com.hifiplayer.domain.usecase.dsp.UpdateEqBandUseCase
import com.hifiplayer.domain.usecase.dsp.UpdateReplayGainSettingsUseCase
import com.hifiplayer.domain.usecase.playback.ObservePlaybackStateUseCase
import com.hifiplayer.domain.usecase.settings.ObserveSettingsUseCase
import com.hifiplayer.domain.usecase.settings.SetBitPerfectUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * What the DSP screens promise the user (phases 11, 12 and 13).
 *
 * The three things worth locking down: a band is only written when the finger lifts, the EQ is never
 * switched on by choosing a preset, and the frequency labels are the ones a person types into a
 * specification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DspViewModelTest {

    private val settings = FakeSettings()

    private fun viewModel(playback: FakePlayback = FakePlayback()) = DspViewModel(
        observeSettings = ObserveSettingsUseCase(settings),
        observePlaybackState = ObservePlaybackStateUseCase(playback),
        setEqEnabled = SetEqEnabledUseCase(settings),
        updateEqBand = UpdateEqBandUseCase(settings),
        setEqPreset = SetEqPresetUseCase(settings),
        saveEqPreset = SaveEqPresetUseCase(settings),
        deleteEqPreset = DeleteEqPresetUseCase(settings),
        setPreamp = SetPreampUseCase(settings),
        setReplayGainMode = SetReplayGainModeUseCase(settings),
        updateReplayGain = UpdateReplayGainSettingsUseCase(settings),
        setCrossfeed = SetCrossfeedUseCase(settings),
        setBalance = SetBalanceUseCase(settings),
        setAppGain = SetAppGainUseCase(settings),
        setBitPerfect = SetBitPerfectUseCase(settings),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The ViewModel shares its state while it has a subscriber, so every test subscribes first. */
    private fun kotlinx.coroutines.test.TestScope.collecting(viewModel: DspViewModel): DspViewModel {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        return viewModel
    }

    @Test
    fun `the screen shows the ten bands the specification asks for`() = runTest {
        val state = collecting(viewModel()).state.value

        assertThat(state.bands).hasSize(10)
        assertThat(state.bands.first().frequencyLabel).isEqualTo("30 Hz")
        assertThat(state.bands.last().frequencyLabel).isEqualTo("12 kHz")
        assertThat(state.presets.map { it.id }).contains(EqPreset.FLAT_ID)
    }

    @Test
    fun `dragging a slider drafts the value and only the release writes it`() = runTest {
        val vm = collecting(viewModel())
        val band = vm.state.value.bands.first().band

        vm.onBandDrag(band.copy(gainDb = 6.5))
        assertThat(settings.writes).isEqualTo(0) // still nothing in the data store
        assertThat(vm.state.value.bands.first().band.gainDb).isEqualTo(6.5)

        vm.onBandCommit(band.copy(gainDb = 6.5))
        assertThat(settings.writes).isEqualTo(1)
        assertThat(settings.current.eq.bands.first().gainDb).isEqualTo(6.5)
    }

    @Test
    fun `choosing a preset never switches the equaliser on by itself`() = runTest {
        val vm = collecting(viewModel())

        vm.onSelectPreset(EqPreset.BASS_BOOST_ID)

        val eq = settings.current.eq
        assertThat(eq.enabled).isFalse()
        assertThat(eq.bands).isEqualTo(EqPreset.byId(EqPreset.BASS_BOOST_ID).bands)
    }

    @Test
    fun `an equaliser that clips is flagged`() = runTest {
        val vm = collecting(viewModel())

        vm.onEqEnabledChange(true)
        val band = vm.state.value.bands[2].band.copy(gainDb = 12.0)
        vm.onBandDrag(band)
        vm.onBandCommit(band)

        assertThat(settings.current.eq.clippingRisk).isTrue()
        assertThat(settings.current.eq.bands[2].gainDb).isEqualTo(12.0)
        assertThat(vm.state.value.clippingRisk).isTrue()
    }

    @Test
    fun `a positive app gain is flagged as a clipping risk`() = runTest {
        val vm = collecting(viewModel())

        vm.onAppGainChange(3.0)

        assertThat(settings.current.dsp.appGainDb).isEqualTo(3.0)
        assertThat(vm.state.value.clippingRisk).isTrue()
    }

    @Test
    fun `replay gain mode and crossfeed reach the settings`() = runTest {
        val vm = collecting(viewModel())

        vm.onReplayGainModeChange(ReplayGainMode.ALBUM)
        vm.onCrossfeedChange(CrossfeedMode.MEDIUM)

        assertThat(settings.current.replayGain.mode).isEqualTo(ReplayGainMode.ALBUM)
        assertThat(settings.current.dsp.crossfeed).isEqualTo(CrossfeedMode.MEDIUM)
        assertThat(vm.state.value.crossfeed).isEqualTo(CrossfeedMode.MEDIUM)
    }

    @Test
    fun `a custom preset is saved only when it has a name`() = runTest {
        val vm = collecting(viewModel())

        vm.onSavePreset("   ")
        assertThat(settings.savedPresets).isEmpty()

        vm.onSavePreset("Mi curva")
        assertThat(settings.savedPresets.map { it.name }).containsExactly("Mi curva")
    }

    @Test
    fun `frequency labels are written the way people write them`() {
        assertThat(formatFrequency(20.0)).isEqualTo("20 Hz")
        assertThat(formatFrequency(750.5)).isEqualTo("750.5 Hz")
        assertThat(formatFrequency(1000.0)).isEqualTo("1 kHz")
        assertThat(formatFrequency(3200.0)).isEqualTo("3.2 kHz")
        assertThat(formatFrequency(20000.0)).isEqualTo("20 kHz")
    }
}

// ---------------------------------------------------------------------------------- test doubles

private class FakeSettings : SettingsRepository {

    private val state = MutableStateFlow(AppSettings.DEFAULT)

    var writes = 0
        private set

    val savedPresets = mutableListOf<EqPreset>()

    val current: AppSettings get() = state.value

    override val settings: StateFlow<AppSettings> = state

    override suspend fun updatePlayback(transform: (PlaybackSettings) -> PlaybackSettings): Outcome<Unit> =
        write { it.copy(playback = transform(it.playback)) }

    override suspend fun updateReplayGain(
        transform: (ReplayGainSettings) -> ReplayGainSettings,
    ): Outcome<Unit> = write { it.copy(replayGain = transform(it.replayGain)) }

    override suspend fun updateEq(transform: (EqSettings) -> EqSettings): Outcome<Unit> =
        write { it.copy(eq = transform(it.eq)) }

    override suspend fun updateDsp(transform: (DspSettings) -> DspSettings): Outcome<Unit> =
        write { it.copy(dsp = transform(it.dsp)) }

    override suspend fun updateLibrary(transform: (LibrarySettings) -> LibrarySettings): Outcome<Unit> =
        write { it.copy(library = transform(it.library)) }

    override suspend fun updateAppearance(
        transform: (AppearanceSettings) -> AppearanceSettings,
    ): Outcome<Unit> = write { it.copy(appearance = transform(it.appearance)) }

    override suspend fun saveEqPreset(preset: EqPreset): Outcome<EqPreset> {
        savedPresets += preset
        write { it.copy(eq = it.eq.copy(userPresets = it.eq.userPresets + preset)) }
        return Outcome.Success(preset)
    }

    override suspend fun deleteEqPreset(presetId: String): Outcome<Unit> =
        write { app -> app.copy(eq = app.eq.copy(userPresets = app.eq.userPresets.filterNot { it.id == presetId })) }

    override suspend fun resetToDefaults(): Outcome<Unit> = write { AppSettings.DEFAULT }

    private fun write(transform: (AppSettings) -> AppSettings): Outcome<Unit> {
        writes++
        state.value = transform(state.value)
        return Outcome.Success(Unit)
    }
}

/**
 * Only the playback snapshot is read by the DSP screens, so the fake implements that snapshot and
 * returns a "not implemented here" failure for the transport calls no DSP test can reach.
 */
private class FakePlayback : PlaybackRepository {

    private val mutable = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutable

    /** Deja el estado **verificado** de bit-perfect, como lo mediría el motor. */
    fun setBitPerfectActive(active: Boolean) {
        mutable.value = mutable.value.copy(
            audioInfo = mutable.value.audioInfo.copy(
                bitPerfect = BitPerfectState(requested = active, isActive = active),
            ),
        )
    }

    override suspend fun playTracks(tracks: List<Track>, startIndex: Int, origin: QueueOrigin) = unreachable()
    override suspend fun playTrack(track: Track, origin: QueueOrigin) = unreachable()
    override suspend fun playPause() = unreachable()
    override suspend fun play() = unreachable()
    override suspend fun pause() = unreachable()
    override suspend fun stop() = unreachable()
    override suspend fun next() = unreachable()
    override suspend fun previous() = unreachable()
    override suspend fun seekTo(positionMs: Long) = unreachable()
    override suspend fun setRepeatMode(mode: RepeatMode) = unreachable()
    override suspend fun setShuffle(enabled: Boolean) = unreachable()
    override suspend fun setGapless(enabled: Boolean) = unreachable()
    override suspend fun addToQueue(track: Track, position: QueueInsertPosition) = unreachable()
    override suspend fun addTracksToQueue(tracks: List<Track>, position: QueueInsertPosition) = unreachable()
    override suspend fun removeFromQueue(index: Int) = unreachable()
    override suspend fun moveInQueue(fromIndex: Int, toIndex: Int) = unreachable()
    override suspend fun clearQueue() = unreachable()
    override suspend fun playQueueIndex(index: Int) = unreachable()
    override suspend fun saveQueue() = unreachable()
    override suspend fun restoreQueue() = unreachable()

    private fun unreachable(): Outcome<Nothing> =
        throw AssertionError("the DSP screens must not touch the transport")

    // ---------------------------------------------------------------------------------------
    // Los avisos de bit-perfect. La app decía «el ecualizador no se aplica» con solo estar
    // pedido, aunque el sistema no lo hubiera concedido y el ecualizador estuviera sonando.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `pedir bit-perfect sin que el sistema lo conceda no marca los efectos como anulados`() = runTest {
        val playback = FakePlayback()
        val vm = collecting(viewModel(playback))

        assertThat(vm.state.value.bitPerfectRequested).isTrue()
        assertThat(vm.state.value.bitPerfectActive).isFalse()
        // Lo importante: los efectos SÍ se aplican, y la pantalla no puede decir lo contrario.
        assertThat(vm.state.value.effectsBypassed).isFalse()
        assertThat(vm.state.value.bitPerfectUnavailable).isTrue()
    }

    @Test
    fun `con bit-perfect concedido de verdad los efectos quedan anulados`() = runTest {
        val playback = FakePlayback()
        playback.setBitPerfectActive(true)
        val vm = collecting(viewModel(playback))

        assertThat(vm.state.value.effectsBypassed).isTrue()
        assertThat(vm.state.value.bitPerfectUnavailable).isFalse()
    }

    @Test
    fun `desactivar bit-perfect desde el aviso escribe el ajuste`() = runTest {
        val vm = collecting(viewModel())

        vm.onBitPerfectChange(false)

        assertThat(settings.current.playback.bitPerfectEnabled).isFalse()
    }
}
