package com.hifiplayer.core.audio

import android.media.AudioDeviceInfo
import com.hifiplayer.domain.model.device.OutputDevice
import com.hifiplayer.domain.model.device.OutputRouteType

/** Maps platform device descriptions onto the app's own vocabulary. */
object AudioRouteMapper {

    fun typeOf(device: AudioDeviceInfo): OutputRouteType = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputRouteType.BUILTIN_SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputRouteType.BUILTIN_EARPIECE
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> OutputRouteType.WIRED_HEADPHONES
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> OutputRouteType.WIRED_HEADSET
        AudioDeviceInfo.TYPE_USB_DEVICE -> OutputRouteType.USB_DEVICE
        AudioDeviceInfo.TYPE_USB_HEADSET -> OutputRouteType.USB_HEADSET
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputRouteType.USB_ACCESSORY
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> OutputRouteType.BLUETOOTH_A2DP
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> OutputRouteType.BLUETOOTH_A2DP
        AudioDeviceInfo.TYPE_BLE_HEADSET -> OutputRouteType.BLUETOOTH_LE
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> OutputRouteType.BLUETOOTH_LE
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> OutputRouteType.HDMI
        AudioDeviceInfo.TYPE_HEARING_AID -> OutputRouteType.HEARING_AID
        else -> OutputRouteType.UNKNOWN
    }

    fun toOutputDevice(device: AudioDeviceInfo, isActive: Boolean, selectedId: String?): OutputDevice {
        val routeType = typeOf(device)
        return OutputDevice(
            id = stableId(device),
            name = displayName(device),
            type = routeType,
            isActive = isActive,
            isSelectedByUser = selectedId != null && selectedId == stableId(device),
            productName = device.productName?.toString()?.takeIf { it.isNotBlank() },
            vendorId = null,
            productId = null,
        )
    }

    /**
     * Stable identifier across reconnections: device ids from the platform change, so the type,
     * address and product name are combined into something the app can persist.
     */
    fun stableId(device: AudioDeviceInfo): String = buildString {
        append(device.type)
        append(':')
        append(device.address ?: "sin-dirección")
        append(':')
        append(device.productName?.toString()?.lowercase()?.replace(' ', '-') ?: "genérico")
    }

    fun displayName(device: AudioDeviceInfo): String {
        val product = device.productName?.toString()?.takeIf { it.isNotBlank() }
        val base = when (typeOf(device)) {
            OutputRouteType.USB_DEVICE -> "DAC USB"
            OutputRouteType.USB_HEADSET -> "Auriculares USB"
            OutputRouteType.USB_ACCESSORY -> "Accesorio USB"
            OutputRouteType.BLUETOOTH_A2DP -> "Bluetooth"
            OutputRouteType.BLUETOOTH_LE -> "Bluetooth LE"
            OutputRouteType.BUILTIN_SPEAKER -> "Altavoz del dispositivo"
            OutputRouteType.BUILTIN_EARPIECE -> "Auricular interno"
            OutputRouteType.WIRED_HEADPHONES -> "Auriculares con cable"
            OutputRouteType.WIRED_HEADSET -> "Auriculares con micrófono"
            OutputRouteType.HDMI -> "HDMI"
            OutputRouteType.HEARING_AID -> "Audífono"
            OutputRouteType.UNKNOWN -> "Salida de audio"
        }
        return if (product != null && !product.equals(base, ignoreCase = true)) "$base ($product)" else base
    }
}
