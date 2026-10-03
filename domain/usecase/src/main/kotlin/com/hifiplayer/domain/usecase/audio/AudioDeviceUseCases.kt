package com.hifiplayer.domain.usecase.audio

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.audio.Codec
import com.hifiplayer.domain.model.device.AudioDeviceEvent
import com.hifiplayer.domain.model.device.BitPerfectState
import com.hifiplayer.domain.model.device.CapabilityReport
import com.hifiplayer.domain.model.device.DecoderSupport
import com.hifiplayer.domain.model.device.DeviceCapabilities
import com.hifiplayer.domain.model.device.OutputDevice
import com.hifiplayer.domain.repository.AudioDeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Requirements 9, 10, 30: output devices, real capabilities and bit-perfect state. */
class ObserveOutputDevicesUseCase(private val devices: AudioDeviceRepository) {
    operator fun invoke(): StateFlow<List<OutputDevice>> = devices.outputDevices
}

class ObserveActiveOutputUseCase(private val devices: AudioDeviceRepository) {
    operator fun invoke(): StateFlow<OutputDevice?> = devices.activeOutput
}

class ObserveAudioCapabilitiesUseCase(private val devices: AudioDeviceRepository) {
    operator fun invoke(): StateFlow<DeviceCapabilities?> = devices.activeCapabilities
}

class ObserveBitPerfectStateUseCase(private val devices: AudioDeviceRepository) {
    operator fun invoke(): StateFlow<BitPerfectState> = devices.bitPerfectState
}

class ObserveAudioDeviceEventsUseCase(private val devices: AudioDeviceRepository) {
    operator fun invoke(): Flow<AudioDeviceEvent> = devices.deviceEvents
}

class RefreshOutputsUseCase(private val devices: AudioDeviceRepository) {
    suspend operator fun invoke(): Outcome<List<OutputDevice>> = devices.refreshOutputs()
}

class SelectOutputDeviceUseCase(private val devices: AudioDeviceRepository) {
    suspend operator fun invoke(deviceId: String): Outcome<Unit> {
        if (deviceId.isBlank()) {
            return Outcome.Failure(
                com.hifiplayer.domain.model.error.AudioDeviceError.OutputUnavailable("", "identificador vacío"),
            )
        }
        return devices.selectOutput(deviceId)
    }
}

class UseSystemDefaultOutputUseCase(private val devices: AudioDeviceRepository) {
    suspend operator fun invoke(): Outcome<Unit> = devices.clearSelectedOutput()
}

class RequestUsbPermissionUseCase(private val devices: AudioDeviceRepository) {
    suspend operator fun invoke(deviceId: String): Outcome<Unit> = devices.requestUsbPermission(deviceId)
}

class GetCapabilityReportUseCase(private val devices: AudioDeviceRepository) {
    suspend operator fun invoke(): Outcome<CapabilityReport> = devices.capabilityReport()
}

class GetDecoderSupportUseCase(private val devices: AudioDeviceRepository) {
    suspend operator fun invoke(codec: Codec): Outcome<DecoderSupport> = devices.decoderSupport(codec)
}

/** Pre-flight check used before claiming anything in the UI (requirement 10). */
class VerifyOutputUseCase(private val devices: AudioDeviceRepository) {
    suspend operator fun invoke(sampleRateHz: Int, bitDepth: Int, channels: Int): Outcome<DeviceCapabilities> =
        devices.verifyOutputFor(sampleRateHz, bitDepth, channels)
}
