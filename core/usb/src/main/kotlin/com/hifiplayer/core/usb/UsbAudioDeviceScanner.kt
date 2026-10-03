package com.hifiplayer.core.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.domain.model.device.UsbAudioDetails

/**
 * Requirement 9: find USB audio devices and describe them truthfully.
 *
 * Descriptor parsing needs permission on some platforms; when it is not granted yet, the device
 * is still listed (so the user sees it and can grant access) but its detailed capabilities stay
 * null instead of being invented.
 */
class UsbAudioDeviceScanner(private val context: Context) {

    private val usbManager: UsbManager?
        get() = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    fun isUsbHostSupported(): Boolean = context.packageManager.hasSystemFeature("android.hardware.usb.host")

    fun findAudioDevices(): List<UsbAudioDevice> {
        val manager = usbManager ?: return emptyList()
        return manager.deviceList.values
            .filter { it.isAudioDevice() }
            .map { device -> describe(device, hasPermission = manager.hasPermission(device)) }
            .sortedBy { it.deviceName }
    }

    fun findByDeviceId(deviceId: Int): UsbAudioDevice? {
        val manager = usbManager ?: return null
        val device = manager.deviceList.values.firstOrNull { it.deviceId == deviceId } ?: return null
        return describe(device, hasPermission = manager.hasPermission(device))
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager?.hasPermission(device) == true

    private fun describe(device: UsbDevice, hasPermission: Boolean): UsbAudioDevice {
        val parsed = if (hasPermission) {
            readDescriptors(device)
        } else {
            null
        }
        val productName = device.productName?.takeIf { it.isNotBlank() }
        val manufacturer = device.manufacturerName?.takeIf { it.isNotBlank() }
        val details = UsbAudioDetails(
            deviceName = device.deviceName,
            manufacturer = manufacturer,
            productName = productName,
            vendorId = device.vendorId,
            productId = device.productId,
            audioClassVersion = parsed?.audioClassVersion,
            interfaceNumber = parsed?.controlInterfaceNumber,
            altSetting = parsed?.formats?.firstOrNull()?.altSetting,
            isAsynchronous = parsed?.isAsynchronous,
            rawSampleRates = parsed?.sampleRates ?: emptySet(),
            rawBitDepths = parsed?.bitDepths ?: emptySet(),
            rawChannelCounts = parsed?.channelCounts ?: emptySet(),
        )
        return UsbAudioDevice(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            productName = productName,
            manufacturer = manufacturer,
            vendorId = device.vendorId,
            productId = device.productId,
            hasPermission = hasPermission,
            details = details,
            descriptorNotes = parsed?.notes ?: emptyList(),
            interfaceCount = device.interfaceCount,
        )
    }

    private fun readDescriptors(device: UsbDevice): UsbAudioDescriptors.ParsedAudioDevice? {
        val manager = usbManager ?: return null
        val connection = try {
            manager.openDevice(device)
        } catch (security: SecurityException) {
            AppLogger.w(TAG, "Sin permiso para abrir el dispositivo USB")
            null
        } catch (exception: Exception) {
            AppLogger.w(TAG, "No se pudo abrir el dispositivo USB: ${exception.javaClass.simpleName}")
            null
        } ?: return null

        return try {
            val raw = connection.rawDescriptors
            UsbAudioDescriptors.parse(raw)
        } catch (exception: Exception) {
            AppLogger.w(TAG, "No se pudieron leer los descriptores USB: ${exception.javaClass.simpleName}")
            null
        } finally {
            try {
                connection.close()
            } catch (exception: Exception) {
                AppLogger.w(TAG, "Error al cerrar la conexión USB")
            }
        }
    }

    private fun UsbDevice.isAudioDevice(): Boolean {
        if (deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        for (index in 0 until interfaceCount) {
            val usbInterface = getInterface(index)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) return true
        }
        return false
    }

    data class UsbAudioDevice(
        val deviceId: Int,
        val deviceName: String,
        val productName: String?,
        val manufacturer: String?,
        val vendorId: Int,
        val productId: Int,
        val hasPermission: Boolean,
        val details: UsbAudioDetails,
        val descriptorNotes: List<String> = emptyList(),
        val interfaceCount: Int = 0,
    ) {
        val displayName: String
            get() = productName ?: manufacturer ?: "DAC USB"

        val capabilitySummary: String
            get() {
                val rates = details.rawSampleRates
                val depths = details.rawBitDepths
                if (rates.isEmpty() || depths.isEmpty()) return "Capacidades por verificar (requiere permiso USB)"
                val maxRate = rates.maxOrNull() ?: return "Capacidades por verificar (requiere permiso USB)"
                val maxDepth = depths.maxOrNull() ?: return "Capacidades por verificar (requiere permiso USB)"
                return "hasta $maxDepth-bit / ${maxRate / 1000} kHz · ${rates.size} frecuencias declaradas por el DAC"
            }
    }

    private companion object {
        const val TAG = "UsbAudioDeviceScanner"
    }
}
