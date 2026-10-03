package com.hifiplayer.domain.repository

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.device.AudioDeviceEvent
import com.hifiplayer.domain.model.device.BitPerfectState
import com.hifiplayer.domain.model.device.CapabilityReport
import com.hifiplayer.domain.model.device.DecoderSupport
import com.hifiplayer.domain.model.device.DeviceCapabilities
import com.hifiplayer.domain.model.device.OutputDevice
import com.hifiplayer.domain.model.audio.Codec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything about the *hardware* side (requirements 9, 10): which outputs exist, what they can
 * really do, whether a bit-perfect path is available, and what the decoders can handle.
 */
interface AudioDeviceRepository {

    val outputDevices: StateFlow<List<OutputDevice>>

    val activeOutput: StateFlow<OutputDevice?>

    /** Verbatim capabilities of the active route, or null while unverified. */
    val activeCapabilities: StateFlow<DeviceCapabilities?>

    val bitPerfectState: StateFlow<BitPerfectState>

    /** Attach/detach/permission events so the UI can react without polling. */
    val deviceEvents: Flow<AudioDeviceEvent>

    suspend fun refreshOutputs(): Outcome<List<OutputDevice>>

    suspend fun capabilitiesFor(deviceId: String): Outcome<DeviceCapabilities>

    /** Full report for the diagnostics screen ("compartir informe"). */
    suspend fun capabilityReport(): Outcome<CapabilityReport>

    suspend fun decoderSupport(codec: Codec): Outcome<DecoderSupport>

    /** Asks the user (system dialog) for permission to talk to a USB device. */
    suspend fun requestUsbPermission(deviceId: String): Outcome<Unit>

    suspend fun selectOutput(deviceId: String): Outcome<Unit>

    suspend fun clearSelectedOutput(): Outcome<Unit>

    /** Verifies whether the current route can deliver [requestedLabel] untouched. */
    suspend fun verifyOutputFor(sampleRateHz: Int, bitDepth: Int, channels: Int): Outcome<DeviceCapabilities>
}
