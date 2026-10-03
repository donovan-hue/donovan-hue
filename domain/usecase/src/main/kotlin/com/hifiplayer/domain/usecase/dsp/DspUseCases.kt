package com.hifiplayer.domain.usecase.dsp

import com.hifiplayer.core.common.config.AudioConfig
import com.hifiplayer.domain.model.audio.CrossfeedMode
import com.hifiplayer.domain.model.audio.ReplayGainMode
import com.hifiplayer.domain.model.error.IllegalState
import com.hifiplayer.domain.model.settings.EqBand
import com.hifiplayer.domain.model.settings.EqPreset
import com.hifiplayer.domain.model.settings.EqSettings
import com.hifiplayer.domain.repository.SettingsRepository
import com.hifiplayer.core.common.result.Outcome

/**
 * Requirements 6, 12, 13, 14, 15: everything that can intentionally alter the signal goes
 * through these use cases, and all of it is disabled while bit-perfect is active.
 */
class SetEqEnabledUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean): Outcome<Unit> =
        settings.updateEq { it.copy(enabled = enabled) }
}

class UpdateEqBandUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(band: EqBand): Outcome<Unit> = settings.updateEq { eq ->
        val index = eq.bands.indexOfFirst { it.id == band.id }
        if (index < 0) return@updateEq eq
        eq.copy(
            bands = eq.bands.toMutableList().also { it[index] = band.clamped() },
            presetId = EqPreset.CUSTOM_ID,
            presetName = "Custom",
        )
    }
}

class SetEqPresetUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(presetId: String): Outcome<Unit> = settings.updateEq { eq ->
        val preset = eq.userPresets.firstOrNull { it.id == presetId } ?: EqPreset.byId(presetId)
        eq.copy(presetId = preset.id, presetName = preset.name, bands = preset.bands)
    }
}

class SaveEqPresetUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(name: String, bands: List<EqBand>): Outcome<EqPreset> {
        val clean = name.trim()
        if (clean.isEmpty()) return Outcome.Failure(IllegalState("El preset necesita un nombre"))
        if (bands.size < AudioConfig.MIN_EQ_BANDS) {
            return Outcome.Failure(IllegalState("Un preset necesita al menos ${AudioConfig.MIN_EQ_BANDS} bandas"))
        }
        return settings.saveEqPreset(EqPreset(id = "user_${clean.lowercase().replace(' ', '_')}", name = clean, bands = bands, isUserDefined = true))
    }
}

class DeleteEqPresetUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(presetId: String): Outcome<Unit> {
        if (EqPreset.BUILT_IN.any { it.id == presetId }) {
            return Outcome.Failure(IllegalState("Los presets incluidos no se pueden eliminar, solo editar como copia"))
        }
        return settings.deleteEqPreset(presetId)
    }
}

class SetPreampUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(db: Double): Outcome<Unit> = settings.updateEq {
        it.copy(preampDb = db.coerceIn(AudioConfig.MIN_PREAMP_DB.toDouble(), AudioConfig.MAX_PREAMP_DB.toDouble()))
    }
}

class SetReplayGainModeUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(mode: ReplayGainMode): Outcome<Unit> =
        settings.updateReplayGain { it.copy(mode = mode) }
}

class UpdateReplayGainSettingsUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(
        preampDb: Double? = null,
        preventClipping: Boolean? = null,
        fallbackGainDb: Double? = null,
    ): Outcome<Unit> = settings.updateReplayGain { current ->
        current.copy(
            preampDb = preampDb ?: current.preampDb,
            preventClipping = preventClipping ?: current.preventClipping,
            fallbackGainDb = fallbackGainDb ?: current.fallbackGainDb,
        )
    }
}

class SetCrossfeedUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(mode: CrossfeedMode): Outcome<Unit> =
        settings.updateDsp { it.copy(crossfeed = mode) }
}

class SetBalanceUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(balance: Double): Outcome<Unit> =
        settings.updateDsp { it.copy(balance = balance.coerceIn(-1.0, 1.0)) }
}

class SetAppGainUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(db: Double): Outcome<Unit> = settings.updateDsp {
        it.copy(appGainDb = db.coerceIn(AudioConfig.MIN_PREAMP_DB.toDouble(), AudioConfig.MAX_PREAMP_DB.toDouble()))
    }
}

class ResetDspUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(): Outcome<Unit> = settings.updateEq { EqSettings.DEFAULT }
}

/** Clamps user input to what the biquad implementation can actually render. */
private fun EqBand.clamped(): EqBand = copy(
    frequencyHz = frequencyHz.coerceIn(AudioConfig.EQ_MIN_FREQ_HZ, AudioConfig.EQ_MAX_FREQ_HZ),
    gainDb = gainDb.coerceIn(AudioConfig.EQ_MIN_GAIN_DB, AudioConfig.EQ_MAX_GAIN_DB),
    q = q.coerceIn(AudioConfig.EQ_MIN_Q, AudioConfig.EQ_MAX_Q),
)
