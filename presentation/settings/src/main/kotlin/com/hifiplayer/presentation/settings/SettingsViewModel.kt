package com.hifiplayer.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.logging.Logger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.onFailure
import com.hifiplayer.domain.model.device.BitPerfectState
import com.hifiplayer.domain.model.error.AppError
import com.hifiplayer.domain.model.library.LibraryStats
import com.hifiplayer.domain.model.library.ScanProgress
import com.hifiplayer.domain.model.settings.AppSettings
import com.hifiplayer.domain.model.settings.ArtworkSize
import com.hifiplayer.domain.model.settings.RepeatModeSetting
import com.hifiplayer.domain.model.settings.ResamplePolicy
import com.hifiplayer.domain.model.settings.ThemeMode
import com.hifiplayer.domain.repository.MusicRepository
import com.hifiplayer.domain.usecase.audio.ObserveBitPerfectStateUseCase
import com.hifiplayer.domain.usecase.library.GetLibraryStatsUseCase
import com.hifiplayer.domain.usecase.settings.ClearCacheUseCase
import com.hifiplayer.domain.usecase.settings.GetCacheSizeUseCase
import com.hifiplayer.domain.usecase.settings.ObserveSettingsUseCase
import com.hifiplayer.domain.usecase.settings.ResetSettingsUseCase
import com.hifiplayer.domain.usecase.settings.SetAnimationsEnabledUseCase
import com.hifiplayer.domain.usecase.settings.SetArtworkSizeUseCase
import com.hifiplayer.domain.usecase.settings.SetAutoPlayOnOpenUseCase
import com.hifiplayer.domain.usecase.settings.SetAutomaticScanningUseCase
import com.hifiplayer.domain.usecase.settings.SetGaplessUseCase
import com.hifiplayer.domain.usecase.settings.SetPauseOnOutputDisconnectUseCase
import com.hifiplayer.domain.usecase.settings.SetRepeatSettingUseCase
import com.hifiplayer.domain.usecase.settings.SetResamplePolicyUseCase
import com.hifiplayer.domain.usecase.settings.SetResumeOnStartUseCase
import com.hifiplayer.domain.usecase.settings.SetShuffleSettingUseCase
import com.hifiplayer.domain.usecase.settings.SetScanOnStartupUseCase
import com.hifiplayer.domain.usecase.settings.SetShowTechnicalInfoInListsUseCase
import com.hifiplayer.domain.usecase.settings.SetThemeModeUseCase
import com.hifiplayer.domain.usecase.settings.SetUsbAutoRouteUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the settings screens read, straight from the settings the app really uses. */
data class SettingsUiState(
    val settings: AppSettings = AppSettings.DEFAULT,
    val stats: LibraryStats? = null,
    val scan: ScanProgress = ScanProgress.IDLE,
    val bitPerfect: BitPerfectState = BitPerfectState(),
    val cacheSizeBytes: Long = 0L,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

/**
 * Settings (requirement 28).
 *
 * Each screen reads the live [AppSettings], so a switch here and the engine's behaviour can never
 * disagree. Every toggle writes through a use case; there is no local copy of a setting anywhere in
 * the UI.
 */
class SettingsViewModel(
    private val musicRepository: MusicRepository,
    observeSettings: ObserveSettingsUseCase,
    getStats: GetLibraryStatsUseCase,
    observeBitPerfectState: ObserveBitPerfectStateUseCase,
    private val getCacheSize: GetCacheSizeUseCase,
    private val clearCache: ClearCacheUseCase,
    private val resetSettings: ResetSettingsUseCase,
    private val setGapless: SetGaplessUseCase,
    private val setResamplePolicy: SetResamplePolicyUseCase,
    private val setRepeatSetting: SetRepeatSettingUseCase,
    private val setShuffleSetting: SetShuffleSettingUseCase,
    private val setUsbAutoRoute: SetUsbAutoRouteUseCase,
    private val setPauseOnOutputDisconnect: SetPauseOnOutputDisconnectUseCase,
    private val setResumeOnStart: SetResumeOnStartUseCase,
    private val setAutoPlayOnOpen: SetAutoPlayOnOpenUseCase,
    private val setScanOnStartup: SetScanOnStartupUseCase,
    private val setAutomaticScanning: SetAutomaticScanningUseCase,
    private val setThemeMode: SetThemeModeUseCase,
    private val setArtworkSize: SetArtworkSizeUseCase,
    private val setAnimations: SetAnimationsEnabledUseCase,
    private val setShowTechnicalInfo: SetShowTechnicalInfoInListsUseCase,
    private val logger: Logger = AppLogger.logger(),
) : ViewModel() {

    private val message = MutableStateFlow<Message?>(null)
    private val cacheSize = MutableStateFlow(0L)

    val state: StateFlow<SettingsUiState> = combine(
        combine(observeSettings(), getStats(), musicRepository.scanProgress) { settings, stats, scan ->
            Triple(settings, stats, scan)
        },
        observeBitPerfectState(),
        combine(cacheSize, message) { size, msg -> size to msg },
    ) { (settings, stats, scan), bitPerfect, (size, currentMessage) ->
        SettingsUiState(
            settings = settings,
            stats = stats,
            scan = scan,
            bitPerfect = bitPerfect,
            cacheSizeBytes = size,
            message = currentMessage?.text,
            messageIsError = currentMessage?.isError == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        onRefreshCacheSize()
    }

    // ------------------------------------------------------------------ reproducción

    fun onGaplessChange(enabled: Boolean) = set { setGapless(enabled) }

    fun onResamplePolicyChange(policy: ResamplePolicy) = set { setResamplePolicy(policy) }

    fun onRepeatSettingChange(mode: RepeatModeSetting) = set { setRepeatSetting(mode) }

    fun onShuffleSettingChange(enabled: Boolean) = set { setShuffleSetting(enabled) }

    fun onUsbAutoRouteChange(enabled: Boolean) = set { setUsbAutoRoute(enabled) }

    fun onPauseOnDisconnectChange(enabled: Boolean) = set { setPauseOnOutputDisconnect(enabled) }

    fun onResumeOnStartChange(enabled: Boolean) = set { setResumeOnStart(enabled) }

    fun onAutoPlayOnOpenChange(enabled: Boolean) = set { setAutoPlayOnOpen(enabled) }

    // ------------------------------------------------------------------ biblioteca

    fun onScanOnStartupChange(enabled: Boolean) = set { setScanOnStartup(enabled) }

    fun onAutomaticScanningChange(enabled: Boolean) = set { setAutomaticScanning(enabled) }

    // ------------------------------------------------------------------ apariencia

    fun onThemeModeChange(mode: ThemeMode) = set { setThemeMode(mode) }

    fun onArtworkSizeChange(size: ArtworkSize) = set { setArtworkSize(size) }

    fun onAnimationsChange(enabled: Boolean) = set { setAnimations(enabled) }

    fun onShowTechnicalInfoChange(enabled: Boolean) = set { setShowTechnicalInfo(enabled) }

    // ------------------------------------------------------------------ almacenamiento y estado

    fun onRefreshCacheSize() {
        viewModelScope.launch { cacheSize.value = getCacheSize() }
    }

    fun onClearCache() {
        viewModelScope.launch {
            when (val outcome = clearCache()) {
                is Outcome.Success -> {
                    cacheSize.value = getCacheSize()
                    message.value = Message(
                        "Caché de portadas liberada: ${humanBytes(outcome.value)}.",
                        isError = false,
                    )
                }

                is Outcome.Failure -> report(outcome.error)
            }
        }
    }

    fun onResetSettings() {
        viewModelScope.launch {
            resetSettings()
                .onFailure(::report)
                .also { outcome ->
                    if (outcome is Outcome.Success) {
                        message.value = Message("Ajustes devueltos a sus valores por defecto.", isError = false)
                        onRefreshCacheSize()
                    }
                }
        }
    }

    fun onDismissMessage() {
        message.value = null
    }

    private fun set(block: suspend () -> Outcome<*>) {
        viewModelScope.launch { block().onFailure(::report) }
    }

    private fun report(error: AppError) {
        logger.w(TAG, "Ajuste no aplicado: ${error.code}")
        message.value = Message(error.userMessage, isError = true)
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    private data class Message(val text: String, val isError: Boolean)

    private companion object {
        const val TAG = "Settings"
    }
}

/**
 * The appearance settings the shell needs before it draws anything.
 *
 * MainActivity reads this to build the theme, which is why it is a ViewModel instead of the activity
 * reading DataStore: the UI keeps its single rule of never touching a repository.
 */
class ThemeViewModel(
    observeSettings: ObserveSettingsUseCase,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = observeSettings()
        .map { it.appearance.themeMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.DARK)

    val showTechnicalInfo: StateFlow<Boolean> = observeSettings()
        .map { it.appearance.showTechnicalInfoInLists }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
}
