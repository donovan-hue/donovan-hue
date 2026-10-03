package com.hifiplayer.presentation.playback.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.logging.Logger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.onFailure
import com.hifiplayer.domain.model.audio.Codec
import com.hifiplayer.domain.model.device.CapabilityReport
import com.hifiplayer.domain.model.device.DecoderSupport
import com.hifiplayer.domain.model.error.AppError
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.playback.PlaybackState
import com.hifiplayer.domain.model.settings.AppSettings
import com.hifiplayer.domain.usecase.audio.GetCapabilityReportUseCase
import com.hifiplayer.domain.usecase.audio.GetDecoderSupportUseCase
import com.hifiplayer.domain.usecase.audio.ObserveActiveOutputUseCase
import com.hifiplayer.domain.usecase.audio.ObserveAudioCapabilitiesUseCase
import com.hifiplayer.domain.usecase.audio.ObserveBitPerfectStateUseCase
import com.hifiplayer.domain.usecase.audio.ObserveOutputDevicesUseCase
import com.hifiplayer.domain.usecase.audio.RefreshOutputsUseCase
import com.hifiplayer.domain.usecase.audio.VerifyOutputUseCase
import com.hifiplayer.domain.usecase.playback.ObservePlaybackStateUseCase
import com.hifiplayer.domain.usecase.settings.ObserveSettingsUseCase
import com.hifiplayer.presentation.playback.AudioPathUi
import com.hifiplayer.presentation.playback.buildAudioPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One decoder row of the capability report. */
data class DecoderRow(
    val codec: Codec,
    val playable: Boolean,
    val isSoftwareOnly: Boolean,
    val decoderName: String?,
    val notes: String?,
)

data class AudioInfoUiState(
    val audio: AudioPathUi = AudioPathUi(),
    val track: Track? = null,
    val activeRouteName: String? = null,
    val activeRouteType: String? = null,
    val supportLabel: String? = null,
    val mixerLabel: String? = null,
    val blockers: List<String> = emptyList(),
    val evidence: List<String> = emptyList(),
    val decoders: List<DecoderRow> = emptyList(),
    val report: CapabilityReport? = null,
    val verifications: List<VerificationRow> = emptyList(),
    val loading: Boolean = true,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

/** Result of asking the platform whether it will deliver a specific format untouched. */
data class VerificationRow(
    val formatLabel: String,
    val deliveredLabel: String,
    val bitPerfectAchieved: Boolean,
    val message: String,
)

/**
 * Audio Information (phase 8): the whole signal path, from the file to the jack.
 *
 * Everything shown here comes from three real sources: the engine's own measurements (source,
 * decoded, output, decoder name, DSP stages), the device repository's probes (routes, sample rates,
 * mixer behaviour) and the platform's decoder list. Nothing is inferred from the file name, and a
 * figure that was not measured is displayed as "no detectado" by the card itself.
 */
class AudioInfoViewModel(
    private val observePlaybackState: ObservePlaybackStateUseCase,
    observeSettings: ObserveSettingsUseCase,
    observeActiveOutput: ObserveActiveOutputUseCase,
    private val observeCapabilities: ObserveAudioCapabilitiesUseCase,
    observeBitPerfectState: ObserveBitPerfectStateUseCase,
    observeOutputDevices: ObserveOutputDevicesUseCase,
    private val refreshOutputs: RefreshOutputsUseCase,
    private val getCapabilityReport: GetCapabilityReportUseCase,
    private val getDecoderSupport: GetDecoderSupportUseCase,
    private val verifyOutput: VerifyOutputUseCase,
    private val logger: Logger = AppLogger.logger(),
) : ViewModel() {

    private val message = MutableStateFlow<Message?>(null)
    private val report = MutableStateFlow<CapabilityReport?>(null)
    private val verifications = MutableStateFlow<List<VerificationRow>>(emptyList())
    private val verifying = Mutex()

    private val decoders = MutableStateFlow<List<DecoderRow>>(emptyList())

    val state: StateFlow<AudioInfoUiState> = combine(
        combine(observePlaybackState(), observeSettings(), observeActiveOutput()) { playback, settings, output ->
            Triple(playback, settings, output)
        },
        combine(observeCapabilities(), observeBitPerfectState(), observeOutputDevices()) { caps, bitPerfect, devices ->
            DevicePart(caps, bitPerfect, devices.size)
        },
        combine(report, verifications, message) { report, verifications, message ->
            Triple(report, verifications, message)
        },
        decoders,
    ) { (playback, settings, output), devicePart, (report, verifications, currentMessage), decoders ->
        AudioInfoUiState(
            audio = buildAudioPath(playback.audioInfo, settings),
            track = playback.currentTrack,
            activeRouteName = output?.name ?: devicePart.capabilities?.routeName,
            activeRouteType = (output?.type ?: devicePart.capabilities?.routeType)?.displayName,
            supportLabel = devicePart.bitPerfect.support.displayName,
            mixerLabel = devicePart.capabilities?.let { caps ->
                val rate = caps.mixerSampleRateHz ?: return@let "el sistema no lo reporta"
                val buffer = caps.mixerFramesPerBuffer
                if (buffer != null) "${"%.1f".format(rate / 1000.0)} kHz · $buffer frames" else "${"%.1f".format(rate / 1000.0)} kHz"
            },
            blockers = devicePart.bitPerfect.blockers.map { it.explanation },
            evidence = devicePart.bitPerfect.evidence,
            decoders = decoders,
            report = report,
            verifications = verifications,
            loading = false,
            message = currentMessage?.text,
            messageIsError = currentMessage?.isError == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AudioInfoUiState())

    init {
        onRefresh()
    }

    /** Re-probes the platform: routes, capabilities, decoder list. Real calls, no cached guesses. */
    fun onRefresh() {
        viewModelScope.launch {
            refreshOutputs().onFailure(::report)
            when (val outcome = getCapabilityReport()) {
                is Outcome.Success -> report.value = outcome.value
                is Outcome.Failure -> report(outcome.error)
            }
        }
        viewModelScope.launch {
            val rows = mutableListOf<DecoderRow>()
            Codec.entries.filter { it != Codec.UNKNOWN }.forEach { codec ->
                when (val outcome = getDecoderSupport(codec)) {
                    is Outcome.Success -> rows += outcome.value.toRow()
                    is Outcome.Failure -> logger.w(TAG, "Sin datos del decodificador ${codec.id}: ${outcome.error.code}")
                }
            }
            decoders.value = rows
        }
    }

    /**
     * Asks the platform whether the current track's exact format comes out untouched.
     *
     * This is the only place the app makes that claim, and it only does it after the platform
     * answered — the row shows the requested format, the delivered format and the platform's reason.
     */
    fun onVerifyCurrentTrack() {
        val format = currentFormat() ?: return
        viewModelScope.launch {
            verifying.withLock {
                val outcome = verifyOutput(format.sampleRateHz, format.bitDepth, format.channels)
                val requested = format.label
                when (outcome) {
                    is Outcome.Success -> {
                        val caps = outcome.value
                        val achieved = caps.bitPerfectSupport.isActive
                        val row = VerificationRow(
                            formatLabel = requested,
                            deliveredLabel = format.label,
                            bitPerfectAchieved = achieved,
                            message = caps.bitPerfectSupport.displayName,
                        )
                        verifications.value = (listOf(row) + verifications.value).take(MAX_VERIFICATIONS)
                    }

                    is Outcome.Failure -> report(outcome.error)
                }
            }
        }
    }

    fun onDismissMessage() {
        message.value = null
    }

    private fun currentFormat() = state.value.track?.format

    private fun DecoderSupport.toRow() = DecoderRow(
        codec = codec,
        playable = playable,
        isSoftwareOnly = isSoftwareOnly,
        decoderName = decoderName,
        notes = notes,
    )

    private fun report(error: AppError) {
        logger.w(TAG, "Consulta de audio fallida: ${error.code}")
        message.value = Message(error.userMessage, isError = true)
    }

    /** The three device readings that must stay in sync with each other. */
    private data class DevicePart(
        val capabilities: com.hifiplayer.domain.model.device.DeviceCapabilities?,
        val bitPerfect: com.hifiplayer.domain.model.device.BitPerfectState,
        val deviceCount: Int,
    )

    private data class Message(val text: String, val isError: Boolean)

    private companion object {
        const val TAG = "AudioInfo"
        const val MAX_VERIFICATIONS = 5
    }
}
