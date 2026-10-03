package com.hifiplayer.data.local.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.outcomeOf
import com.hifiplayer.core.common.time.TimeProvider
import com.hifiplayer.domain.model.settings.AppSettings
import com.hifiplayer.domain.model.settings.AppearanceSettings
import com.hifiplayer.domain.model.settings.DspSettings
import com.hifiplayer.domain.model.settings.EqPreset
import com.hifiplayer.domain.model.settings.EqSettings
import com.hifiplayer.domain.model.settings.LibrarySettings
import com.hifiplayer.domain.model.settings.PlaybackSettings
import com.hifiplayer.domain.model.settings.ReplayGainSettings
import com.hifiplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "hifi_settings")

/**
 * DataStore-backed settings (requirement 28/43).
 *
 * Reads are exposed as a [StateFlow] so every screen and the playback engine observe the same
 * values, and writes go through `edit`, which is atomic: a crash mid-write can never leave the
 * file half-updated.
 */
class SettingsRepositoryImpl(
    private val context: Context,
    scope: CoroutineScope,
    private val timeProvider: TimeProvider = TimeProvider.System,
) : SettingsRepository {

    private val store: DataStore<Preferences> = context.settingsDataStore

    override val settings: StateFlow<AppSettings> = store.data
        .catch { throwable ->
            // Corrupted or unreadable file: report it and continue with defaults instead of dying.
            AppLogger.e(TAG, "No se pudieron leer los ajustes guardados; se usarán los valores por defecto", throwable)
            if (throwable !is IOException) throw throwable
            emit(emptyPreferences())
        }
        .map { preferences -> SettingsCodec.decode(preferences) }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings.DEFAULT)

    override suspend fun updatePlayback(transform: (PlaybackSettings) -> PlaybackSettings): Outcome<Unit> =
        update { it.copy(playback = transform(it.playback)) }

    override suspend fun updateReplayGain(transform: (ReplayGainSettings) -> ReplayGainSettings): Outcome<Unit> =
        update { it.copy(replayGain = transform(it.replayGain)) }

    override suspend fun updateEq(transform: (EqSettings) -> EqSettings): Outcome<Unit> =
        update { it.copy(eq = transform(it.eq)) }

    override suspend fun updateDsp(transform: (DspSettings) -> DspSettings): Outcome<Unit> =
        update { it.copy(dsp = transform(it.dsp)) }

    override suspend fun updateLibrary(transform: (LibrarySettings) -> LibrarySettings): Outcome<Unit> =
        update { it.copy(library = transform(it.library)) }

    override suspend fun updateAppearance(transform: (AppearanceSettings) -> AppearanceSettings): Outcome<Unit> =
        update { it.copy(appearance = transform(it.appearance)) }

    override suspend fun saveEqPreset(preset: EqPreset): Outcome<EqPreset> {
        val stored = preset.copy(
            isUserDefined = true,
            updatedAtEpochMs = if (preset.updatedAtEpochMs > 0L) preset.updatedAtEpochMs else timeProvider.nowMs(),
        )
        val result = updateEq { eq ->
            val withoutSameId = eq.userPresets.filterNot { it.id == stored.id }
            eq.copy(userPresets = (withoutSameId + stored).sortedBy { it.name.lowercase() })
        }
        return when (result) {
            is Outcome.Success -> Outcome.Success(stored)
            is Outcome.Failure -> result
        }
    }

    override suspend fun deleteEqPreset(presetId: String): Outcome<Unit> =
        updateEq { eq -> eq.copy(userPresets = eq.userPresets.filterNot { it.id == presetId }) }

    override suspend fun resetToDefaults(): Outcome<Unit> = outcomeOf(TAG) {
        store.edit { preferences -> preferences.clear() }
        AppLogger.i(TAG, "Ajustes restablecidos a los valores por defecto")
    }

    /** Current snapshot without collecting the flow (handy for engine startup). */
    suspend fun current(): AppSettings = settings.value

    private suspend fun update(reducer: (AppSettings) -> AppSettings): Outcome<Unit> = outcomeOf(TAG) {
        store.edit { preferences ->
            val next = reducer(SettingsCodec.decode(preferences))
            SettingsCodec.write(preferences, next)
        }
    }

    private companion object {
        const val TAG = "SettingsRepository"
    }
}
