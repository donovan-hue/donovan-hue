package com.hifiplayer.domain.usecase.settings

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.audio.ReplayGainMode
import com.hifiplayer.domain.model.settings.AppSettings
import com.hifiplayer.domain.model.settings.ArtworkSize
import com.hifiplayer.domain.model.settings.RepeatModeSetting
import com.hifiplayer.domain.model.settings.ResamplePolicy
import com.hifiplayer.domain.model.settings.ThemeMode
import com.hifiplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.StateFlow

/** Requirement 28: settings surface for every group in the UI. */
class ObserveSettingsUseCase(private val settings: SettingsRepository) {
    operator fun invoke(): StateFlow<AppSettings> = settings.settings
}

class SetBitPerfectUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean): Outcome<Unit> =
        settings.updatePlayback { it.copy(bitPerfectEnabled = enabled) }
}

class SetGaplessUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean): Outcome<Unit> =
        settings.updatePlayback { it.copy(gaplessEnabled = enabled) }
}

class SetResamplePolicyUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(policy: ResamplePolicy): Outcome<Unit> =
        settings.updatePlayback { it.copy(resamplePolicy = policy) }
}

class SetUsbAutoRouteUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean): Outcome<Unit> =
        settings.updatePlayback { it.copy(usbAutoRoute = enabled) }
}

class SetPauseOnOutputDisconnectUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean): Outcome<Unit> =
        settings.updatePlayback { it.copy(pauseOnOutputDisconnect = enabled) }
}

class SetResumeOnStartUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean): Outcome<Unit> =
        settings.updatePlayback { it.copy(resumePlaybackOnStart = enabled) }
}

class SetAutoPlayOnOpenUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean): Outcome<Unit> =
        settings.updatePlayback { it.copy(autoPlayOnOpen = enabled) }
}

class SetRepeatSettingUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(mode: RepeatModeSetting): Outcome<Unit> =
        settings.updatePlayback { it.copy(repeatMode = mode) }
}

class SetShuffleSettingUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean): Outcome<Unit> =
        settings.updatePlayback { it.copy(shuffleEnabled = enabled) }
}

class SetReplayGainModeSettingUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(mode: ReplayGainMode): Outcome<Unit> =
        settings.updateReplayGain { it.copy(mode = mode) }
}

class SetScanOnStartupUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean): Outcome<Unit> =
        settings.updateLibrary { it.copy(scanOnStartup = enabled) }
}

class SetAutomaticScanningUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean): Outcome<Unit> =
        settings.updateLibrary { it.copy(automaticScanning = enabled) }
}

class SetAnalyzeFormatsUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean): Outcome<Unit> =
        settings.updateLibrary { it.copy(analyzeFormatsOnScan = enabled) }
}

class AddExcludedFolderUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(path: String): Outcome<Unit> =
        settings.updateLibrary { it.copy(excludedFolders = it.excludedFolders + path) }
}

class RemoveExcludedFolderUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(path: String): Outcome<Unit> =
        settings.updateLibrary { it.copy(excludedFolders = it.excludedFolders - path) }
}

class SetThemeModeUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(mode: ThemeMode): Outcome<Unit> =
        settings.updateAppearance { it.copy(themeMode = mode) }
}

class SetArtworkSizeUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(size: ArtworkSize): Outcome<Unit> =
        settings.updateAppearance { it.copy(artworkSize = size) }
}

class SetAnimationsEnabledUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean): Outcome<Unit> =
        settings.updateAppearance { it.copy(animationsEnabled = enabled) }
}

class ResetSettingsUseCase(private val settings: SettingsRepository) {
    suspend operator fun invoke(): Outcome<Unit> = settings.resetToDefaults()
}

/** Storage group (requirement 28): cache size + clear cache. */
class GetCacheSizeUseCase(
    private val artwork: com.hifiplayer.domain.repository.ArtworkRepository,
) {
    suspend operator fun invoke(): Long = artwork.cacheSizeBytes()
}

class ClearCacheUseCase(
    private val artwork: com.hifiplayer.domain.repository.ArtworkRepository,
) {
    suspend operator fun invoke(): Outcome<Long> = artwork.clearArtworkCache()
}
