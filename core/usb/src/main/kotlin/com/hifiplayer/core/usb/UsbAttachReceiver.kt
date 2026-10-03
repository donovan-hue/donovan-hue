package com.hifiplayer.core.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.IntentCompat
import com.hifiplayer.core.common.logging.AppLogger

/**
 * Declared in the manifest so Android can hand the app the "USB DAC connected" event
 * (requirement 9). The receiver only records the event: arbitration of what to do with it
 * (route audio, ask permission, show a message) happens in the device repository.
 */
class UsbAttachReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        when (action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                AppLogger.i(TAG, "DAC USB conectado: ${device?.productName ?: device?.deviceName ?: "dispositivo desconocido"}")
            UsbManager.ACTION_USB_DEVICE_DETACHED ->
                AppLogger.i(TAG, "DAC USB desconectado: ${device?.productName ?: device?.deviceName ?: "dispositivo desconocido"}")
            else -> AppLogger.d(TAG, "Evento USB no manejado: $action")
        }
        // The running PlaybackService observes AudioDeviceCallback / USB events itself; this
        // receiver guarantees the process wakes up even when nothing is playing, so the UI can
        // show the device and its verified capabilities immediately.
    }

    private companion object {
        const val TAG = "UsbAttachReceiver"
    }
}
