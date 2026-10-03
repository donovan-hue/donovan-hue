package com.hifiplayer.data.repository.devices

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import com.hifiplayer.core.audio.AudioCapabilitiesManager
import com.hifiplayer.core.audio.AudioDeviceMonitor
import com.hifiplayer.core.audio.AudioRouteMapper
import com.hifiplayer.core.audio.BitPerfectController
import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.error.TypedAppException
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.outcomeOf
import com.hifiplayer.core.common.time.TimeProvider
import com.hifiplayer.core.usb.UsbAudioDeviceScanner
import com.hifiplayer.core.usb.UsbPermissionRequester
import com.hifiplayer.domain.model.audio.AudioFormatSpec
import com.hifiplayer.domain.model.audio.Codec
import com.hifiplayer.domain.model.device.AudioDeviceEvent
import com.hifiplayer.domain.model.device.BitPerfectState
import com.hifiplayer.domain.model.device.CapabilityReport
import com.hifiplayer.domain.model.device.DecoderSupport
import com.hifiplayer.domain.model.device.DeviceCapabilities
import com.hifiplayer.domain.model.device.OutputDevice
import com.hifiplayer.domain.model.error.AudioDeviceError
import com.hifiplayer.domain.repository.AudioDeviceRepository
import com.hifiplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Requirements 9 and 10 from the hardware side.
 *
 * Everything here is a query to the platform or to the DAC itself; nothing is assumed. When a
 * value cannot be verified it stays null/empty and the corresponding warning is kept, because the
 * product rule is that the app may never claim more than it measured.
 */
class AudioDeviceRepositoryImpl(
    private val context: Context,
    private val capabilitiesManager: AudioCapabilitiesManager,
    private val monitor: AudioDeviceMonitor,
    private val bitPerfectController: BitPerfectController,
    private val usbScanner: UsbAudioDeviceScanner,
    private val usbPermissionRequester: UsbPermissionRequester,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    scope: CoroutineScope,
    private val timeProvider: TimeProvider = TimeProvider.System,
) : AudioDeviceRepository {

    private val _capabilities = MutableStateFlow<DeviceCapabilities?>(null)
    private val _bitPerfect = MutableStateFlow(bitPerfectController.currentState())

    override val outputDevices: StateFlow<List<OutputDevice>> =
        combine(monitor.outputs, settingsRepository.settings) { devices, settings ->
            val selectedId = settings.playback.preferredOutputDeviceId
            devices.map { device -> device.copy(isSelectedByUser = selectedId != null && device.id == selectedId) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val activeOutput: StateFlow<OutputDevice?> =
        combine(outputDevices, settingsRepository.settings) { devices, settings ->
            val selectedId = settings.playback.preferredOutputDeviceId
            // The device the user picked wins over the platform's idea of "active": that is what
            // the engine will actually try to use.
            devices.firstOrNull { selectedId != null && it.id == selectedId }
                ?: devices.firstOrNull { it.isActive }
                ?: devices.firstOrNull()
        }.stateIn(scope, SharingStarted.Eagerly, null)

    override val activeCapabilities: StateFlow<DeviceCapabilities?> = _capabilities.asStateFlow()

    override val bitPerfectState: StateFlow<BitPerfectState> = _bitPerfect.asStateFlow()

    override val deviceEvents: Flow<AudioDeviceEvent> = monitor.events

    fun startMonitoring() {
        monitor.start()
        refreshCapabilities()
    }

    fun stopMonitoring() {
        monitor.stop()
    }

    override suspend fun refreshOutputs(): Outcome<List<OutputDevice>> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            monitor.refresh(triggeredBy = "petición de la app")
            refreshCapabilities()
            outputDevices.value
        }
    }

    override suspend fun capabilitiesFor(deviceId: String): Outcome<DeviceCapabilities> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            val device = capabilitiesManager.outputDevices()
                .firstOrNull { AudioRouteMapper.stableId(it) == deviceId }
                ?: throw TypedAppException(AudioDeviceError.OutputUnavailable(deviceId, "la salida ya no está disponible"))
            val usbDetails = if (AudioRouteMapper.typeOf(device).isUsb) usbDetailsFor(device.productName?.toString()) else null
            capabilitiesManager.capabilitiesFor(device, usbDetails, forceRefresh = true)
        }
    }

    override suspend fun capabilityReport(): Outcome<CapabilityReport> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val outputs = capabilitiesManager.outputDevices().map { device ->
                val usb = if (AudioRouteMapper.typeOf(device).isUsb) usbDetailsFor(device.productName?.toString()) else null
                capabilitiesManager.capabilitiesFor(device, usb)
            }
            val decoders = Codec.SCANNABLE.map { capabilitiesManager.decoderSupport(it) }
            val active = outputs.firstOrNull { it.isActive }
            val notes = buildList {
                add("Los valores provienen de consultas al sistema (perfiles, direct playback, atributos de mezclador) y de los descriptores USB.")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    add("Android 13 o inferior: no existe la API de mezclador bit-perfect; siempre hay mezcla del sistema.")
                }
                outputs.flatMap { it.warnings }.distinct().forEach { add(it) }
            }
            CapabilityReport(
                deviceManufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                androidRelease = Build.VERSION.RELEASE,
                androidSdkInt = Build.VERSION.SDK_INT,
                appVersion = "${packageInfo.versionName} (${packageInfo.longVersionCode})",
                buildFingerprint = Build.FINGERPRINT,
                decoders = decoders,
                outputs = outputs,
                activeOutput = active,
                mixerSampleRateHz = capabilitiesManager.mixerSampleRateHz(),
                mixerFramesPerBuffer = capabilitiesManager.mixerFramesPerBuffer(),
                isLowLatencyOutputSupported = capabilitiesManager.mixerFramesPerBuffer() != null,
                bitPerfectApiAvailable = bitPerfectController.isApiAvailable,
                generatedAtEpochMs = timeProvider.nowMs(),
                notes = notes,
            )
        }
    }

    override suspend fun decoderSupport(codec: Codec): Outcome<DecoderSupport> = withContext(dispatchers.io) {
        outcomeOf(TAG) { capabilitiesManager.decoderSupport(codec) }
    }

    override suspend fun requestUsbPermission(deviceId: String): Outcome<Unit> = withContext(dispatchers.io) {
        val numericId = deviceId.toIntOrNull()
            ?: return@withContext Outcome.Failure(AudioDeviceError.Unknown("identificador USB inválido: $deviceId"))
        val result = usbPermissionRequester.request(numericId)
        if (result is Outcome.Success) {
            capabilitiesManager.invalidate()
            monitor.refresh(triggeredBy = "permiso USB concedido")
            refreshCapabilities()
        }
        result
    }

    override suspend fun selectOutput(deviceId: String): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            val exists = capabilitiesManager.outputDevices().any { AudioRouteMapper.stableId(it) == deviceId }
            if (!exists) throw TypedAppException(AudioDeviceError.OutputUnavailable(deviceId, "esa salida no está conectada"))
            settingsRepository.updatePlayback { it.copy(preferredOutputDeviceId = deviceId) }
            AppLogger.i(TAG, "Salida preferida: $deviceId")
        }
    }

    override suspend fun clearSelectedOutput(): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            settingsRepository.updatePlayback { it.copy(preferredOutputDeviceId = null) }
            Unit
        }
    }

    override suspend fun verifyOutputFor(sampleRateHz: Int, bitDepth: Int, channels: Int): Outcome<DeviceCapabilities> =
        withContext(dispatchers.io) {
            outcomeOf(TAG) {
                val device = capabilitiesManager.activeOutputDevice()
                    ?: throw TypedAppException(AudioDeviceError.OutputUnavailable("", "no hay salida de audio activa"))
                val capabilities = capabilitiesManager.capabilitiesFor(device)
                AppLogger.i(
                    TAG,
                    "Verificación ${bitDepth}-bit/${sampleRateHz} Hz x$channels en ${capabilities.routeName}: " +
                        if (capabilities.supports(sampleRateHz, bitDepth)) "soportado" else "no soportado",
                )
                capabilities
            }
        }

    override suspend fun activateBitPerfect(
        format: AudioFormatSpec,
        dspActive: Boolean,
        dspChainDescription: String,
        volumeAttenuatesSignal: Boolean,
    ): Outcome<BitPerfectState> = withContext(dispatchers.playback) {
        outcomeOf(TAG) {
            val device = selectedAudioDeviceInfo()
            val state = bitPerfectController.activate(
                device = device,
                format = format,
                dspActive = dspActive,
                dspChainDescription = dspChainDescription,
                volumeAttenuatesSignal = volumeAttenuatesSignal,
            )
            _bitPerfect.value = state
            state
        }
    }

    override suspend fun deactivateBitPerfect(): Outcome<BitPerfectState> = withContext(dispatchers.playback) {
        outcomeOf(TAG) {
            val state = bitPerfectController.deactivate(selectedAudioDeviceInfo())
            _bitPerfect.value = state
            state
        }
    }

    // ---------------------------------------------------------------- internals

    private fun selectedAudioDeviceInfo(): android.media.AudioDeviceInfo? {
        val selectedId = settingsRepository.settings.value.playback.preferredOutputDeviceId
        val devices = capabilitiesManager.outputDevices()
        return devices.firstOrNull { selectedId != null && AudioRouteMapper.stableId(it) == selectedId }
            ?: capabilitiesManager.activeOutputDevice()
    }

    private fun usbDetailsFor(productName: String?): com.hifiplayer.domain.model.device.UsbAudioDetails? {
        val match = usbScanner.findAudioDevices().firstOrNull { candidate ->
            productName == null || candidate.productName?.equals(productName, ignoreCase = true) == true
        } ?: return null
        return match.details
    }

    private fun refreshCapabilities() {
        val device = capabilitiesManager.activeOutputDevice() ?: run {
            _capabilities.value = null
            return
        }
        val usbDetails = if (AudioRouteMapper.typeOf(device).isUsb) usbDetailsFor(device.productName?.toString()) else null
        _capabilities.value = capabilitiesManager.capabilitiesFor(device, usbDetails)
    }

    private companion object {
        const val TAG = "AudioDeviceRepository"
    }
}
