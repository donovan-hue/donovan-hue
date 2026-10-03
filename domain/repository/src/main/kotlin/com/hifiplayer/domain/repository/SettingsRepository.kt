package com.hifiplayer.domain.repository

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.settings.AppSettings
import com.hifiplayer.domain.model.settings.EqPreset
import com.hifiplayer.domain.model.settings.EqSettings
import com.hifiplayer.domain.model.settings.LibrarySettings
import com.hifiplayer.domain.model.settings.DspSettings
import com.hifiplayer.domain.model.settings.PlaybackSettings
import com.hifiplayer.domain.model.settings.AppearanceSettings
import com.hifiplayer.domain.model.settings.ReplayGainSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * DataStore-backed settings (requirement 28/43). Values survive process death, so every screen
 * can rebuild itself after rotation or a cold start without losing user intent.
 */
interface SettingsRepository {

    val settings: StateFlow<AppSettings>

    suspend fun updatePlayback(transform: (PlaybackSettings) -> PlaybackSettings): Outcome<Unit>

    suspend fun updateReplayGain(transform: (ReplayGainSettings) -> ReplayGainSettings): Outcome<Unit>

    suspend fun updateEq(transform: (EqSettings) -> EqSettings): Outcome<Unit>

    suspend fun updateDsp(transform: (DspSettings) -> DspSettings): Outcome<Unit>

    suspend fun updateLibrary(transform: (LibrarySettings) -> LibrarySettings): Outcome<Unit>

    suspend fun updateAppearance(transform: (AppearanceSettings) -> AppearanceSettings): Outcome<Unit>

    suspend fun saveEqPreset(preset: EqPreset): Outcome<EqPreset>

    suspend fun deleteEqPreset(presetId: String): Outcome<Unit>

    suspend fun resetToDefaults(): Outcome<Unit>
}
