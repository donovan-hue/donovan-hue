package com.hifiplayer.core.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.domain.model.device.AudioDeviceEvent
import com.hifiplayer.domain.model.device.OutputDevice
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Requirement 9: react to hardware changes without polling.
 *
 * Listens to the platform's own device callbacks and publishes both the current output list and
 * a stream of events (connected / disconnected / route changed). The rest of the app decides what
 * to do – for example pausing playback when a DAC disappears instead of silently switching to the
 * phone speaker.
 */
class AudioDeviceMonitor(
    private val context: Context,
    private val capabilitiesManager: AudioCapabilitiesManager,
    private val usbScanner: com.hifiplayer.core.usb.UsbAudioDeviceScanner? = null,
) {

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _outputs = MutableStateFlow<List<OutputDevice>>(emptyList())
    val outputs: StateFlow<List<OutputDevice>> = _outputs.asStateFlow()

    private val _events = MutableSharedFlow<AudioDeviceEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AudioDeviceEvent> = _events.asSharedFlow()

    private var previousDevices: Map<String, OutputDevice> = emptyMap()
    private var registered = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refresh(triggeredBy = "dispositivo añadido")
            addedDevices.forEach { device ->
                val output = AudioRouteMapper.toOutputDevice(device, isActive = false, selectedId = null)
                AppLogger.i(TAG, "Salida añadida: ${output.name} (${output.type.displayName})")
                _events.tryEmit(AudioDeviceEvent.Connected(output.name, output))
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val removedIds = removedDevices.map { AudioRouteMapper.stableId(it) }
            refresh(triggeredBy = "dispositivo retirado")
            removedDevices.forEach { device ->
                val output = AudioRouteMapper.toOutputDevice(device, isActive = false, selectedId = null)
                AppLogger.i(TAG, "Salida retirada: ${output.name}")
                _events.tryEmit(AudioDeviceEvent.Disconnected(output.name, output.id))
            }
            val stillPresent = _outputs.value.map { it.id }.toSet()
            removedIds.filter { it in stillPresent }.forEach { id ->
                AppLogger.w(TAG, "Una salida retirada sigue reportada por el sistema: $id")
            }
        }
    }

    fun start() {
        val manager = audioManager ?: return
        if (registered) return
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        registered = true
        refresh(triggeredBy = "lectura inicial")
    }

    fun stop() {
        val manager = audioManager ?: return
        if (!registered) return
        runCatching { manager.unregisterAudioDeviceCallback(callback) }
        registered = false
    }

    /** Re-reads the platform device list and publishes any change. */
    fun refresh(triggeredBy: String = "manual") {
        val devices = capabilitiesManager.outputDevices()
        val activeId = capabilitiesManager.activeOutputDevice()?.let(AudioRouteMapper::stableId)
        val usbDevices = usbScanner?.findAudioDevices()?.associateBy { it.deviceName }.orEmpty()

        val mapped = devices.map { device ->
            val base = AudioRouteMapper.toOutputDevice(device, isActive = AudioRouteMapper.stableId(device) == activeId, selectedId = null)
            if (base.type.isUsb) {
                val usb = usbDevices.values.firstOrNull { usbDevice ->
                    usbDevice.productName != null && base.productName != null &&
                        usbDevice.productName.equals(base.productName, ignoreCase = true)
                }
                if (usb != null) base.copy(vendorId = usb.vendorId, productId = usb.productId) else base
            } else {
                base
            }
        }

        _outputs.value = mapped
        val current = mapped.associateBy { it.id }
        val previous = previousDevices
        if (previous.isNotEmpty()) {
            val active = mapped.firstOrNull { it.isActive }
            if (active?.id != previous.values.firstOrNull { it.isActive }?.id) {
                _events.tryEmit(
                    AudioDeviceEvent.RouteChanged(
                        deviceName = active?.name ?: "sin salida",
                        output = active,
                        previousDeviceId = previous.values.firstOrNull { it.isActive }?.id,
                    ),
                )
            }
        }
        previousDevices = current
        AppLogger.d(TAG, "Salidas actualizadas ($triggeredBy): ${mapped.size}")
    }

    private companion object {
        const val TAG = "AudioDeviceMonitor"
    }
}
