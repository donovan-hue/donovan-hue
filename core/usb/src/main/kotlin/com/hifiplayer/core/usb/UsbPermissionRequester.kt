package com.hifiplayer.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.error.AudioDeviceError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Requirement 9: asking the user for permission to talk to a USB DAC, with a bounded wait so a
 * never-answered dialog can never hang the app.
 */
class UsbPermissionRequester(private val context: Context) {

    private val usbManager: UsbManager?
        get() = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    suspend fun request(deviceId: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Outcome<Unit> {
        val manager = usbManager ?: return Outcome.Failure(
            AudioDeviceError.Unknown("El dispositivo no expone USB Host (UsbManager no disponible)"),
        )
        val device = manager.deviceList.values.firstOrNull { it.deviceId == deviceId }
            ?: return Outcome.Failure(
                AudioDeviceError.OutputUnavailable(deviceId.toString(), "el dispositivo USB ya no está conectado"),
            )

        if (manager.hasPermission(device)) return Outcome.Success(Unit)

        val result = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_USB_PERMISSION -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        result.complete(granted)
                    }
                    ACTION_USB_DETACHED -> result.complete(false)
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(ACTION_USB_DETACHED)
        }
        ContextCompatRegister(context, receiver, filter)

        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                deviceId,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags,
            )
            manager.requestPermission(device, permissionIntent)

            val granted = withTimeoutOrNull(timeoutMs) { result.await() }
            return when (granted) {
                true -> Outcome.Success(Unit)
                false -> Outcome.Failure(AudioDeviceError.UsbPermissionDenied(device.productName ?: device.deviceName))
                null -> Outcome.Failure(
                    AudioDeviceError.Unknown("El sistema no respondió a la solicitud de permiso USB en ${timeoutMs / 1000} s"),
                )
            }
        } catch (exception: Exception) {
            AppLogger.w(TAG, "Error solicitando permiso USB: ${exception.javaClass.simpleName}")
            return Outcome.Failure(AudioDeviceError.Unknown("Error solicitando permiso USB: ${exception.message}"))
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun ContextCompatRegister(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Permission broadcasts are system-sent, so they must be registered as exported.
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION: String = "com.hifiplayer.app.USB_PERMISSION"
        private const val ACTION_USB_DETACHED: String = UsbManager.ACTION_USB_DEVICE_DETACHED
        private const val TAG = "UsbPermissionRequester"
        private const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
