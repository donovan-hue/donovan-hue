package com.hifiplayer.presentation.settings.dsp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.logging.Logger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.onFailure
import com.hifiplayer.domain.model.audio.CrossfeedMode
import com.hifiplayer.domain.model.audio.ReplayGainMode
import com.hifiplayer.domain.model.error.AppError
import com.hifiplayer.domain.model.settings.EqBand
import com.hifiplayer.domain.model.settings.EqPreset
import com.hifiplayer.domain.model.settings.EqSettings
import com.hifiplayer.domain.model.settings.ReplayGainSettings
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One band plus the label of the frequency, ready to draw. */
data class EqBandRow(
    val band: EqBand,
    val index: Int,
    val frequencyLabel: String,
)

data class DspUiState(
    val eq: EqSettings = EqSettings.DEFAULT,
    val replayGain: ReplayGainSettings = ReplayGainSettings(),
    val crossfeed: CrossfeedMode = CrossfeedMode.OFF,
    val preampDb: Double = 0.0,
    val appGainDb: Double = 0.0,
    val balance: Double = 0.0,
    val clippingProtection: Boolean = true,
    /** Lo que el usuario pidió en Ajustes → Audio. */
    val bitPerfectRequested: Boolean = true,
    /** Lo que el sistema ha concedido **de verdad** para la pista que suena, verificado. */
    val bitPerfectActive: Boolean = false,
    val trackGainLabel: String? = null,
    val albumGainLabel: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    val bands: List<EqBandRow>
        get() = eq.bands.mapIndexed { index, band ->
            EqBandRow(band = band, index = index, frequencyLabel = formatFrequency(band.frequencyHz))
        }

    /** The preset list the screen offers: the built-in ones plus whatever the user saved. */
    val presets: List<EqPreset> get() = EqPreset.BUILT_IN + eq.userPresets

    val clippingRisk: Boolean get() = eq.clippingRisk || appGainDb > 0.0 || preampDb > 0.0

    /**
     * Si los efectos se están aplicando o no.
     *
     * Lo decide el estado **verificado**, nunca lo que se pidió: hasta la 0.17.2 las pantallas
     * decían «el ecualizador no se aplica» en cuanto el usuario tenía bit-perfect activado, aunque
     * el sistema no lo hubiera concedido y el ecualizador sí estuviera sonando. Eso era mentira, y
     * además asustaba: el usuario veía rojo por todas partes y concluía que nada funcionaba.
     */
    val effectsBypassed: Boolean get() = bitPerfectActive

    /** Bit-perfect pedido, pero no concedido: el caso normal en un teléfono sin DAC USB. */
    val bitPerfectUnavailable: Boolean get() = bitPerfectRequested && !bitPerfectActive
}

/**
 * The DSP screens (phases 11, 12 and 13): ReplayGain, the parametric EQ and crossfeed.
 *
 * They share one ViewModel because they write to the same settings object and all three are read by
 * the same engine, so a single writer means the engine can never see a half-applied state. The EQ is
 * edited band by band and written on release, which is what makes the sliders usable on a phone
 * without hammering the data store.
 */
class DspViewModel(
    observeSettings: ObserveSettingsUseCase,
    observePlaybackState: ObservePlaybackStateUseCase,
    private val setEqEnabled: SetEqEnabledUseCase,
    private val updateEqBand: UpdateEqBandUseCase,
    private val setEqPreset: SetEqPresetUseCase,
    private val saveEqPreset: SaveEqPresetUseCase,
    private val deleteEqPreset: DeleteEqPresetUseCase,
    private val setPreamp: SetPreampUseCase,
    private val setReplayGainMode: SetReplayGainModeUseCase,
    private val updateReplayGain: UpdateReplayGainSettingsUseCase,
    private val setCrossfeed: SetCrossfeedUseCase,
    private val setBalance: SetBalanceUseCase,
    private val setAppGain: SetAppGainUseCase,
    private val setBitPerfect: SetBitPerfectUseCase,
    private val logger: Logger = AppLogger.logger(),
) : ViewModel() {

    private val message = MutableStateFlow<Message?>(null)

    /**
     * Local copy of the band the user is dragging.
     *
     * A slider that only moved when the data store answered would feel broken; the value is written
     * when the gesture ends and this copy keeps the UI smooth in between.
     */
    private val pendingBand = MutableStateFlow<EqBand?>(null)

    val state: StateFlow<DspUiState> = combine(
        combine(observeSettings(), observePlaybackState()) { settings, playback -> settings to playback },
        pendingBand,
        message,
    ) { (settings, playback), pending, currentMessage ->
        val merged = if (pending == null) settings.eq else settings.eq.replaceBand(pending)
        DspUiState(
            eq = merged,
            replayGain = settings.replayGain,
            crossfeed = settings.dsp.crossfeed,
            preampDb = settings.dsp.preampDb,
            appGainDb = settings.dsp.appGainDb,
            balance = settings.dsp.balance,
            clippingProtection = settings.dsp.clippingProtectionEnabled,
            bitPerfectRequested = settings.playback.bitPerfectEnabled,
            // Verificado, medido por el motor para el formato que se está reproduciendo.
            bitPerfectActive = playback.audioInfo.bitPerfect.isActive,
            // The ReplayGain figures of the track that is playing, straight from its tags: null when
            // the file carries none, so the screen can say "sin datos" instead of showing a zero.
            trackGainLabel = playback.currentTrack?.replayGain?.trackGainDb?.let { "%+.2f dB".format(it) },
            albumGainLabel = playback.currentTrack?.replayGain?.albumGainDb?.let { "%+.2f dB".format(it) },
            message = currentMessage?.text,
            messageIsError = currentMessage?.isError == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DspUiState())

    // ------------------------------------------------------------------ EQ

    fun onEqEnabledChange(enabled: Boolean) = set { setEqEnabled(enabled) }

    /**
     * Apaga (o enciende) bit-perfect desde la pantalla que lo está explicando.
     *
     * El aviso dice «este efecto no se aplica mientras bit-perfect esté activo»: sin esta acción el
     * usuario solo puede leerlo y salir a buscarlo en otro sitio.
     */
    fun onBitPerfectChange(enabled: Boolean) = set { setBitPerfect(enabled) }

    fun onBandDrag(band: EqBand) {
        pendingBand.value = band
    }

    /** Called when the finger leaves the slider: the value becomes real. */
    fun onBandCommit(band: EqBand) {
        pendingBand.value = null
        set { updateEqBand(band) }
    }

    fun onSelectPreset(presetId: String) = set { setEqPreset(presetId) }

    fun onSavePreset(name: String) {
        viewModelScope.launch {
            val current = state.value.eq.bands
            saveEqPreset(name, current)
                .onFailure(::report)
                .also { outcome ->
                    if (outcome is Outcome.Success) {
                        message.value = Message("Preset «${outcome.value.name}» guardado.", isError = false)
                    }
                }
        }
    }

    fun onDeletePreset(presetId: String) {
        viewModelScope.launch {
            deleteEqPreset(presetId).onFailure(::report)
        }
    }

    fun onPreampChange(db: Double) = set { setPreamp(db) }

    // ------------------------------------------------------------------ ReplayGain

    fun onReplayGainModeChange(mode: ReplayGainMode) = set { setReplayGainMode(mode) }

    fun onReplayGainPreampChange(db: Double) {
        set { updateReplayGain(preampDb = db) }
    }

    fun onPreventClippingChange(enabled: Boolean) {
        set { updateReplayGain(preventClipping = enabled) }
    }

    fun onAlbumGainPreferenceChange(enabled: Boolean) {
        set { updateReplayGain(preferAlbumGainInAlbumQueue = enabled) }
    }

    // ------------------------------------------------------------------ Crossfeed y ganancia

    fun onCrossfeedChange(mode: CrossfeedMode) = set { setCrossfeed(mode) }

    fun onBalanceChange(value: Double) = set { setBalance(value) }

    fun onAppGainChange(db: Double) = set { setAppGain(db) }

    fun onDismissMessage() {
        message.value = null
    }

    private fun set(block: suspend () -> Outcome<*>) {
        viewModelScope.launch { block().onFailure(::report) }
    }

    private fun report(error: AppError) {
        logger.w(TAG, "Ajuste de procesamiento no aplicado: ${error.code}")
        message.value = Message(error.userMessage, isError = true)
    }

    private data class Message(val text: String, val isError: Boolean)

    private companion object {
        const val TAG = "Dsp"
    }
}

/** Replaces one band by id, leaving the order and the rest of the chain untouched. */
internal fun EqSettings.replaceBand(band: EqBand): EqSettings =
    copy(bands = bands.map { if (it.id == band.id) band else it })

/** 20 Hz reads better than 20.0 Hz, and 1 kHz better than 1000 Hz. */
internal fun formatFrequency(hz: Double): String = when {
    hz >= 1000.0 -> {
        val k = hz / 1000.0
        if (k == k.toInt().toDouble()) "${k.toInt()} kHz" else "%.1f kHz".format(k)
    }

    hz == hz.toInt().toDouble() -> "${hz.toInt()} Hz"
    else -> "%.1f Hz".format(hz)
}
