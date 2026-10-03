package com.hifiplayer.domain.model.device

/**
 * Hardware events the app must react to (requirement 9): USB DAC attached/detached, output
 * route changed, permissions granted/denied. Never polled – pushed by the platform callbacks
 * and re-emitted here.
 */
sealed interface AudioDeviceEvent {
    val deviceName: String

    data class Connected(override val deviceName: String, val output: OutputDevice) : AudioDeviceEvent
    data class Disconnected(override val deviceName: String, val deviceId: String) : AudioDeviceEvent
    data class PermissionGranted(override val deviceName: String, val deviceId: String) : AudioDeviceEvent
    data class PermissionDenied(override val deviceName: String, val deviceId: String) : AudioDeviceEvent
    data class RouteChanged(override val deviceName: String, val output: OutputDevice?, val previousDeviceId: String?) : AudioDeviceEvent
    data class CapabilitiesChanged(override val deviceName: String, val capabilities: DeviceCapabilities) : AudioDeviceEvent
}
