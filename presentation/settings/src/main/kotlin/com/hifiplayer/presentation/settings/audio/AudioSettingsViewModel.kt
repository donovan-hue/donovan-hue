package com.hifiplayer.presentation.settings.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.logging.Logger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.onFailure
import com.hifiplayer.domain.model.device.BitPerfectState
import com.hifiplayer.domain.model.device.DeviceCapabilities
import com.hifiplayer.domain.model.device.OutputDevice
import com.hifiplayer.domain.model.error.AppError
import com.hifiplayer.domain.model.playback.AudioOutputInfo
import com.hifiplayer.domain.model.settings.PlaybackSettings
import com.hifiplayer.domain.usecase.audio.ObserveActiveOutputUseCase
import com.hifiplayer.domain.usecase.audio.ObserveAudioCapabilitiesUseCase
import com.hifiplayer.domain.usecase.audio.ObserveBitPerfectStateUseCase
import com.hifiplayer.domain.usecase.audio.ObserveOutputDevicesUseCase
import com.hifiplayer.domain.usecase.audio.RefreshOutputsUseCase
import com.hifiplayer.domain.usecase.audio.RequestUsbPermissionUseCase
import com.hifiplayer.domain.usecase.audio.SelectOutputDeviceUseCase
import com.hifiplayer.domain.usecase.audio.UseSystemDefaultOutputUseCase
import com.hifiplayer.domain.usecase.playback.ObservePlaybackStateUseCase
import com.hifiplayer.domain.usecase.settings.ObserveSettingsUseCase
import com.hifiplayer.domain.usecase.settings.SetBitPerfectUseCase
import com.hifiplayer.domain.usecase.settings.SetPauseOnOutputDisconnectUseCase
import com.hifiplayer.domain.usecase.settings.SetUsbAutoRouteUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the output list, with the facts that matter for it. */
data class OutputRow(
    val device: OutputDevice,
    val capabilityLine: String,
    val permissionNote: String?,
)

data class AudioDeviceUiState(
    val outputs: List<OutputRow> = emptyList(),
    val activeOutputId: String? = null,
    val activeOutputName: String? = null,
    val capabilities: DeviceCapabilities? = null,
    val bitPerfect: BitPerfectState = BitPerfectState(),
    val audioInfo: AudioOutputInfo = AudioOutputInfo.UNKNOWN,
    val playback: PlaybackSettings = PlaybackSettings(),
    val message: String? = null,
    val messageIsError: Boolean = false,
)

/**
 * Output devices and bit-perfect (phases 9 and 10).
 *
 * One ViewModel for both because they answer the same question from two sides: which route is
 * carrying the audio, and whether that route can deliver it untouched. The capability lines come
 * from the device repository's probes; when a route never reported anything, the row says so instead
 * of showing a plausible maximum.
 */
class AudioSettingsViewModel(
    private val observePlaybackState: ObservePlaybackStateUseCase,
    observeSettings: ObserveSettingsUseCase,
    private val observeOutputDevices: ObserveOutputDevicesUseCase,
    observeActiveOutput: ObserveActiveOutputUseCase,
    observeCapabilities: ObserveAudioCapabilitiesUseCase,
    observeBitPerfectState: ObserveBitPerfectStateUseCase,
    private val refreshOutputs: RefreshOutputsUseCase,
    private val selectOutput: SelectOutputDeviceUseCase,
    private val useSystemDefault: UseSystemDefaultOutputUseCase,
    private val requestUsbPermission: RequestUsbPermissionUseCase,
    private val setBitPerfect: SetBitPerfectUseCase,
    private val setUsbAutoRoute: SetUsbAutoRouteUseCase,
    private val setPauseOnDisconnect: SetPauseOnOutputDisconnectUseCase,
    private val logger: Logger = AppLogger.logger(),
) : ViewModel() {

    private val message = MutableStateFlow<Message?>(null)

    val state: StateFlow<AudioDeviceUiState> = combine(
        combine(observeOutputDevices(), observeActiveOutput(), observeCapabilities()) { devices, active, caps ->
            Triple(devices, active, caps)
        },
        combine(observeBitPerfectState(), observeSettings(), observePlaybackState()) { bitPerfect, settings, playback ->
            Triple(bitPerfect, settings.playback, playback.audioInfo)
        },
        message,
    ) { (devices, active, caps), (bitPerfect, playbackSettings, audioInfo), currentMessage ->
        AudioDeviceUiState(
            outputs = devices.map { device ->
                OutputRow(
                    device = device,
                    capabilityLine = describeCapabilities(device),
                    permissionNote = when {
                        !device.requiresPermission -> null
                        device.hasPermission -> "permiso concedido"
                        else -> "necesita permiso para usar este dispositivo"
                    },
                )
            },
            activeOutputId = active?.id,
            activeOutputName = active?.name,
            capabilities = caps,
            bitPerfect = bitPerfect,
            audioInfo = audioInfo,
            playback = playbackSettings,
            message = currentMessage?.text,
            messageIsError = currentMessage?.isError == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AudioDeviceUiState())

    fun onRefresh() {
        viewModelScope.launch { refreshOutputs().onFailure(::report) }
    }

    /** Route the audio to one specific device, after the platform granted permission for it. */
    fun onSelectDevice(deviceId: String) {
        viewModelScope.launch {
            selectOutput(deviceId)
                .onFailure(::report)
                .also { outcome ->
                    if (outcome is Outcome.Success) {
                        message.value = Message("Salida cambiada.", isError = false)
                    }
                }
        }
    }

    fun onUseSystemDefault() {
        viewModelScope.launch {
            useSystemDefault().onFailure(::report)
        }
    }

    fun onRequestUsbPermission(deviceId: String) {
        viewModelScope.launch {
            requestUsbPermission(deviceId).onFailure(::report)
        }
    }

    /**
     * Turns bit-perfect on or off.
     *
     * The switch reflects the setting the user chose; whether it is *active* is decided by the
     * platform and shown separately, because a request is not an achievement (requirement 10).
     */
    fun onBitPerfectChange(enabled: Boolean) {
        viewModelScope.launch { setBitPerfect(enabled).onFailure(::report) }
    }

    fun onUsbAutoRouteChange(enabled: Boolean) {
        viewModelScope.launch { setUsbAutoRoute(enabled).onFailure(::report) }
    }

    fun onPauseOnDisconnectChange(enabled: Boolean) {
        viewModelScope.launch { setPauseOnDisconnect(enabled).onFailure(::report) }
    }

    fun onDismissMessage() {
        message.value = null
    }

    /**
     * What a route reported about itself, written as one line.
     *
     * Only the probes that answered are printed. An empty answer becomes "sin datos", never a default
     * like "hasta 192 kHz" that the device never claimed.
     */
    private fun describeCapabilities(device: OutputDevice): String {
        val caps = device.capabilities
        if (caps == null) return "sin datos de capacidad todavía"
        val parts = mutableListOf<String>()
        caps.maxSampleRateHz?.let { parts += "hasta ${"%.1f".format(it / 1000.0)} kHz" }
        caps.maxBitDepth?.let { parts += "$it-bit" }
        if (caps.supportsFloat) parts += "float"
        parts += when {
            caps.bitPerfectSupport.isActive -> "bit-perfect confirmado"
            caps.supportsBitPerfectMixer -> "acepta mezclador sin mezcla"
            else -> caps.bitPerfectSupport.displayName
        }
        if (device.type.isUsb) parts += "USB"
        return parts.joinToString(" · ")
    }

    private fun report(error: AppError) {
        logger.w(TAG, "Operación de audio fallida: ${error.code}")
        message.value = Message(error.userMessage, isError = true)
    }

    private data class Message(val text: String, val isError: Boolean)

    private companion object {
        const val TAG = "AudioDevices"
    }
}
