package com.hifiplayer.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.audio.AudioFormatSpec
import com.hifiplayer.domain.model.device.BitPerfectBlocker
import com.hifiplayer.domain.model.device.BitPerfectState
import com.hifiplayer.domain.model.device.BitPerfectSupport
import com.hifiplayer.domain.model.error.AudioDeviceError

/**
 * Requirement 6 and 10: the real bit-perfect path.
 *
 * On Android 14+ an app can ask the platform for a non-mixing output route
 * (`AudioMixerAttributes` with `MIXER_BEHAVIOR_BIT_PERFECT`). This controller:
 *
 *  1. asks the route which formats it can deliver without mixing,
 *  2. requests exactly the file's format,
 *  3. **reads the request back** and only then reports success,
 *  4. refuses to claim anything when the DSP chain is active, the route is wireless, or the
 *     platform silently kept the mixer on.
 *
 * If any step fails, the returned state explains *why* in user-facing language.
 */
class BitPerfectController(private val context: Context) {

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    var isApiAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        private set

    /** Last state we produced, so the UI and the service agree on what is happening. */
    @Volatile
    private var lastState: BitPerfectState = BitPerfectState()

    fun currentState(): BitPerfectState = lastState

    /**
     * Attempts to activate bit-perfect for [format] on [device].
     *
     * @param dspActive whether any DSP stage is currently modifying the signal
     * @param dspChainDescription human-readable chain, shown while bit-perfect is unavailable
     */
    fun activate(
        device: AudioDeviceInfo?,
        format: AudioFormatSpec,
        dspActive: Boolean,
        dspChainDescription: String,
        volumeAttenuatesSignal: Boolean = false,
    ): BitPerfectState {
        val blockers = mutableListOf<BitPerfectBlocker>()
        val evidence = mutableListOf<String>()

        if (!isApiAvailable) {
            blockers += BitPerfectBlocker.ANDROID_TOO_OLD
            return finish(device, format, requested = true, active = false, BitPerfectSupport.API_NOT_SUPPORTED, blockers, evidence, dspActive, dspChainDescription)
        }

        val manager = audioManager
        if (manager == null) {
            blockers += BitPerfectBlocker.EXCLUSIVE_MODE_UNAVAILABLE
            return finish(device, format, true, false, BitPerfectSupport.DEVICE_NOT_CAPABLE, blockers, evidence, dspActive, dspChainDescription)
        }

        if (device == null) {
            blockers += BitPerfectBlocker.NO_WIRED_OR_USB_OUTPUT
            return finish(null, format, true, false, BitPerfectSupport.NEEDS_WIRED_OR_USB_OUTPUT, blockers, evidence, dspActive, dspChainDescription)
        }

        val routeType = AudioRouteMapper.typeOf(device)
        if (!routeType.canCarryBitPerfect) {
            blockers += BitPerfectBlocker.NO_WIRED_OR_USB_OUTPUT
            evidence += "La salida activa es ${routeType.displayName}: la plataforma remuestrea las salidas inalámbricas"
            return finish(device, format, true, false, BitPerfectSupport.NEEDS_WIRED_OR_USB_OUTPUT, blockers, evidence, dspActive, dspChainDescription)
        }

        if (dspActive) {
            blockers += BitPerfectBlocker.DSP_CHAIN_ACTIVE
        }
        if (volumeAttenuatesSignal) {
            blockers += BitPerfectBlocker.SYSTEM_VOLUME_ACTIVE
        }
        if (!format.isLossless) {
            blockers += BitPerfectBlocker.LOSSY_SOURCE
        }

        val offered = supportedFormats(device)
        evidence += if (offered.isEmpty()) {
            "La salida no ofrece ningún formato sin mezcla (getSupportedMixerAttributes vacío)"
        } else {
            "Formatos sin mezcla ofrecidos: ${offered.joinToString(", ")}"
        }

        if (offered.isEmpty()) {
            blockers += BitPerfectBlocker.EXCLUSIVE_MODE_UNAVAILABLE
            return finish(device, format, true, false, BitPerfectSupport.DEVICE_NOT_CAPABLE, blockers, evidence, dspActive, dspChainDescription)
        }

        val exact = offered.any { it.sampleRateHz == format.sampleRateHz && it.bitDepth == format.bitDepth && it.channels == format.channels }
        if (!exact) {
            blockers += BitPerfectBlocker.FORMAT_NOT_OFFERED_BY_DAC
            evidence += "Se pidió ${format.label} y el DAC ofrece: ${offered.joinToString(", ") { it.label }}"
            return finish(device, format, true, false, BitPerfectSupport.DEVICE_NOT_CAPABLE, blockers, evidence, dspActive, dspChainDescription)
        }

        if (dspActive || volumeAttenuatesSignal) {
            return finish(device, format, true, false, BitPerfectSupport.DEVICE_NOT_CAPABLE, blockers, evidence, dspActive, dspChainDescription)
        }

        val requested = buildMixerAttributes(format)
        val accepted = requestAttributes(manager, device, requested)
        if (!accepted) {
            blockers += BitPerfectBlocker.MIXER_ATTRIBUTES_REJECTED
            return finish(device, format, true, false, BitPerfectSupport.DEVICE_NOT_CAPABLE, blockers, evidence, dspActive, dspChainDescription)
        }

        // Read-back: without this the app would be trusting the platform blindly.
        val applied = readBackAttributes(manager, device)
        val verified = applied != null &&
            applied.sampleRateHz == format.sampleRateHz &&
            applied.bitDepth == format.bitDepth &&
            applied.channels == format.channels

        evidence += if (verified) {
            "Lectura de comprobación: el sistema confirma ${applied!!.label} sin mezcla"
        } else {
            "Lectura de comprobación: el sistema devolvió ${applied?.label ?: "nada"}; se mantiene el mezclador"
        }

        return finish(
            device = device,
            format = format,
            requested = true,
            active = verified,
            support = if (verified) BitPerfectSupport.ACTIVE else BitPerfectSupport.DEGRADED,
            blockers = blockers,
            evidence = evidence,
            dspActive = dspActive,
            dspChainDescription = dspChainDescription,
        )
    }

    /** Releases the exclusive route so the system mixer takes over again. */
    fun deactivate(device: AudioDeviceInfo?): BitPerfectState {
        val manager = audioManager
        if (manager != null && device != null && isApiAvailable) {
            removeAttributes(manager, device)
        }
        lastState = BitPerfectState(requested = false, isActive = false, support = BitPerfectSupport.UNKNOWN)
        return lastState
    }

    /** Formats the route advertises as bit-perfect capable, as parsed specs. */
    fun supportedFormats(device: AudioDeviceInfo): List<MixerFormat> {
        if (!isApiAvailable) return emptyList()
        val manager = audioManager ?: return emptyList()
        return try {
            manager.getSupportedMixerAttributes(device)
                .filter { it.mixerBehavior == MIXER_BEHAVIOR_BIT_PERFECT }
                .map { attributes ->
                    val format = attributes.format
                    MixerFormat(
                        sampleRateHz = format.sampleRate,
                        bitDepth = when (format.encoding) {
                            AudioFormat.ENCODING_PCM_16BIT -> 16
                            AudioFormat.ENCODING_PCM_FLOAT -> 32
                            ELSE_ENCODING_PCM_24BIT -> 24
                            ELSE_ENCODING_PCM_32BIT -> 32
                            else -> 0
                        },
                        channels = when (format.channelMask) {
                            AudioFormat.CHANNEL_OUT_MONO -> 1
                            AudioFormat.CHANNEL_OUT_STEREO -> 2
                            else -> Integer.bitCount(format.channelMask)
                        },
                    )
                }
                .filter { it.bitDepth > 0 }
                .distinct()
        } catch (exception: SecurityException) {
            AppLogger.w(TAG, "Falta MODIFY_AUDIO_SETTINGS para consultar el mezclador")
            emptyList()
        } catch (exception: Exception) {
            AppLogger.w(TAG, "No se pudieron consultar formatos sin mezcla: ${exception.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun requestAttributes(manager: AudioManager, device: AudioDeviceInfo, format: MixerFormat): Boolean {
        if (!isApiAvailable) return false
        return try {
            val attributes = buildMixerAttributesObject(format)
            manager.setPreferredMixerAttributes(audioAttributes, device, attributes)
        } catch (exception: SecurityException) {
            AppLogger.w(TAG, "MODIFY_AUDIO_SETTINGS denegado al solicitar bit-perfect")
            false
        } catch (exception: Exception) {
            AppLogger.w(TAG, "El sistema rechazó la solicitud bit-perfect: ${exception.javaClass.simpleName}")
            false
        }
    }

    private fun readBackAttributes(manager: AudioManager, device: AudioDeviceInfo): MixerFormat? {
        if (!isApiAvailable) return null
        return try {
            val applied = manager.getPreferredMixerAttributes(audioAttributes, device) ?: return null
            if (applied.mixerBehavior != MIXER_BEHAVIOR_BIT_PERFECT) return null
            MixerFormat(
                sampleRateHz = applied.format.sampleRate,
                bitDepth = when (applied.format.encoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> 16
                    AudioFormat.ENCODING_PCM_FLOAT -> 32
                    ELSE_ENCODING_PCM_24BIT -> 24
                    ELSE_ENCODING_PCM_32BIT -> 32
                    else -> 0
                },
                channels = when (applied.format.channelMask) {
                    AudioFormat.CHANNEL_OUT_MONO -> 1
                    AudioFormat.CHANNEL_OUT_STEREO -> 2
                    else -> Integer.bitCount(applied.format.channelMask)
                },
            )
        } catch (exception: Exception) {
            AppLogger.w(TAG, "No se pudo leer la configuración de mezclador aplicada")
            null
        }
    }

    private fun removeAttributes(manager: AudioManager, device: AudioDeviceInfo) {
        try {
            manager.clearPreferredMixerAttributes(audioAttributes, device)
        } catch (exception: Exception) {
            AppLogger.w(TAG, "No se pudo liberar la ruta exclusiva: ${exception.javaClass.simpleName}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun buildMixerAttributesObject(format: MixerFormat): android.media.AudioMixerAttributes =
        AudioMixerAttributesCompat.build(format)

    private fun buildMixerAttributes(format: AudioFormatSpec): MixerFormat = MixerFormat(
        sampleRateHz = format.sampleRateHz,
        bitDepth = format.bitDepth,
        channels = format.channels,
    )

    private fun finish(
        device: AudioDeviceInfo?,
        format: AudioFormatSpec,
        requested: Boolean,
        active: Boolean,
        support: BitPerfectSupport,
        blockers: List<BitPerfectBlocker>,
        evidence: List<String>,
        dspActive: Boolean,
        dspChainDescription: String,
    ): BitPerfectState {
        val state = BitPerfectState(
            requested = requested,
            isActive = active,
            support = support,
            routeId = device?.let(AudioRouteMapper::stableId),
            routeName = device?.let(AudioRouteMapper::displayName),
            sourceFormat = format,
            deliveredFormat = if (active) format else null,
            dspActive = dspActive,
            dspChainDescription = dspChainDescription,
            blockers = blockers,
            evidence = evidence,
        )
        lastState = state
        if (active) {
            AppLogger.i(TAG, "Bit-perfect activo: ${format.label} en ${state.routeName ?: "salida"}")
        } else {
            AppLogger.i(
                TAG,
                "Bit-perfect no disponible: ${blockers.firstOrNull()?.name ?: support.name}",
            )
        }
        return state
    }

    /** Failure helper exposed to the repository for user-facing messages. */
    fun asError(state: BitPerfectState): AudioDeviceError = AudioDeviceError.BitPerfectUnavailable(
        blockers = state.blockers.map { it.explanation }.ifEmpty { listOf("Bit-perfect no disponible en esta configuración de audio.") },
    )

    data class MixerFormat(val sampleRateHz: Int, val bitDepth: Int, val channels: Int) {
        val label: String
            get() = "$bitDepth-bit / ${AudioFormatSpec.formatSampleRate(sampleRateHz)}"
    }

    private companion object {
        const val TAG = "BitPerfectController"
        const val MIXER_BEHAVIOR_BIT_PERFECT = 1
        const val ELSE_ENCODING_PCM_24BIT = 21
        const val ELSE_ENCODING_PCM_32BIT = 22
    }
}
