package com.hifiplayer.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.hifiplayer.core.common.config.AudioConfig
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.time.TimeProvider
import com.hifiplayer.domain.model.audio.PcmEncoding
import com.hifiplayer.domain.model.device.BitPerfectSupport
import com.hifiplayer.domain.model.device.CapabilityEvidence
import com.hifiplayer.domain.model.device.DecoderSupport
import com.hifiplayer.domain.model.device.DeviceCapabilities
import com.hifiplayer.domain.model.audio.Codec
import com.hifiplayer.domain.model.device.OutputRouteType
import com.hifiplayer.domain.model.device.UsbAudioDetails

/**
 * Requirement 10: query what the hardware really supports and keep the evidence.
 *
 * Everything the app claims comes from one of these queries:
 *  - `AudioDeviceInfo.getAudioProfiles()` (API 34+) – the device's own declared profiles;
 *  - `AudioTrack.isDirectPlaybackSupported()` – whether a rate/depth can bypass the mixer;
 *  - `AudioManager` mixer properties – what the system mixer is doing right now;
 *  - `AudioManager.getSupportedMixerAttributes()` (API 34+) – bit-perfect capable formats;
 *  - `MediaCodecList` – which decoders exist on this device.
 *
 * No defaults are invented: when a probe cannot answer, the corresponding set stays empty and a
 * warning explains why (requirement 10/44).
 */
class AudioCapabilitiesManager(
    private val context: Context,
    private val timeProvider: TimeProvider = TimeProvider.System,
) {

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val cache = mutableMapOf<String, DeviceCapabilities>()

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun activeOutputDevice(): AudioDeviceInfo? {
        val manager = audioManager ?: return null
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull()
    }

    fun outputDevices(): List<AudioDeviceInfo> =
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList() ?: emptyList()

    fun mixerSampleRateHz(): Int? = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()

    fun mixerFramesPerBuffer(): Int? = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()

    fun nativeOutputSampleRate(): Int? = runCatching { AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) }.getOrNull()

    /**
     * Full capability probe for one route. Results are cached briefly because probing opens
     * AudioTrack instances, which is not free (requirement 34: keep the UI responsive).
     */
    fun capabilitiesFor(
        device: AudioDeviceInfo,
        usbDetails: UsbAudioDetails? = null,
        forceRefresh: Boolean = false,
    ): DeviceCapabilities {
        val routeId = AudioRouteMapper.stableId(device)
        val cached = cache[routeId]
        val now = timeProvider.nowMs()
        if (!forceRefresh && cached != null && now - cached.probedAtEpochMs < AudioConfig.CAPABILITY_CACHE_TTL_MS) {
            return cached
        }
        val probe = probe(device, usbDetails, now)
        cache[routeId] = probe
        return probe
    }

    fun invalidate(routeId: String? = null) {
        if (routeId == null) cache.clear() else cache.remove(routeId)
    }

    private fun probe(device: AudioDeviceInfo, usbDetails: UsbAudioDetails?, nowMs: Long): DeviceCapabilities {
        val routeType = AudioRouteMapper.typeOf(device)
        val evidence = mutableListOf<CapabilityEvidence>()
        val warnings = mutableListOf<String>()
        val rates = linkedSetOf<Int>()
        val depths = linkedSetOf<Int>()
        val encodings = linkedSetOf<PcmEncoding>()

        // ---- 1) The device's own declared audio profiles (API 34+) -------------------------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val profiles = runCatching { device.audioProfiles }.getOrNull()
            if (!profiles.isNullOrEmpty()) {
                profiles.forEach { profile ->
                    profile.sampleRates.filter { it > 0 }.forEach(rates::add)
                    // AudioProfile.getFormat() is the *encoding* of the profile.
                    when (profile.format) {
                        AudioFormat.ENCODING_PCM_16BIT -> depths += 16
                        AudioFormat.ENCODING_PCM_FLOAT -> depths += 32
                        ENCODING_PCM_24BIT_PACKED -> depths += 24
                        ENCODING_PCM_32BIT -> depths += 32
                    }
                }
                evidence += CapabilityEvidence(
                    source = "AudioDeviceInfo.getAudioProfiles()",
                    detail = "${profiles.size} perfiles declarados, ${rates.size} frecuencias",
                )
            } else {
                warnings += "El dispositivo no declara perfiles de audio (getAudioProfiles vacío)"
            }
        }

        // ---- 2) Encodings reported by the device -----------------------------------------
        device.encodings.forEach { encoding ->
            when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> encodings += PcmEncoding.PCM_16
                AudioFormat.ENCODING_PCM_FLOAT -> encodings += PcmEncoding.PCM_32_FLOAT
                ENCODING_PCM_24BIT_PACKED -> encodings += PcmEncoding.PCM_24
                ENCODING_PCM_32BIT -> encodings += PcmEncoding.PCM_32_INT
            }
        }
        device.sampleRates.filter { it > 0 }.forEach {
            rates += it
        }
        device.encodings?.let {
            evidence += CapabilityEvidence(
                source = "AudioDeviceInfo.encodings / sampleRates",
                detail = "${it.size} codificaciones, ${device.sampleRates.size} frecuencias reportadas",
            )
        }

        // ---- 3) Direct (mixer-less) playback support per candidate format ----------------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var supportedCount = 0
            val candidates = if (rates.isEmpty()) CANDIDATE_RATES else rates.toList()
            candidates.sorted().forEach { rate ->
                candidateEncodingFor(rate)?.let { (encoding, depth, pcm) ->
                    if (isDirectPlaybackSupported(rate, encoding, channels = 2)) {
                        rates += rate
                        depths += depth
                        encodings += pcm
                        supportedCount++
                    }
                }
            }
            evidence += CapabilityEvidence(
                source = "AudioTrack.isDirectPlaybackSupported()",
                detail = "$supportedCount formatos pueden emitirse sin mezclador",
            )
            if (supportedCount == 0) {
                warnings += "Ninguna combinación de frecuencia/profundidad puede saltarse el mezclador en esta salida"
            }
        } else {
            warnings += "Android ${Build.VERSION.RELEASE}: no existe API para verificar la ruta sin mezclador"
        }

        // ---- 4) Mixer attributes: bit-perfect availability (API 34+) ---------------------
        var bitPerfectMixer = false
        var bitPerfectSupport = BitPerfectSupport.API_NOT_SUPPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val supported = supportedBitPerfectAttributes(device)
            bitPerfectMixer = supported.any { (_, mixerRate) -> mixerRate in rates || rates.isEmpty() }
            bitPerfectSupport = when {
                supported.isNotEmpty() -> BitPerfectSupport.UNKNOWN // verificado al activarlo con un formato concreto
                !routeType.canCarryBitPerfect -> BitPerfectSupport.NEEDS_WIRED_OR_USB_OUTPUT
                else -> BitPerfectSupport.DEVICE_NOT_CAPABLE
            }
            evidence += CapabilityEvidence(
                source = "AudioManager.getSupportedMixerAttributes()",
                detail = if (supported.isEmpty()) {
                    "sin atributos de mezclador sin mezcla"
                } else {
                    supported.joinToString(", ") { (rate, _) -> "${rate} Hz" }
                },
            )
        }

        // ---- 5) USB descriptor data (authoritative for DACs) ------------------------------
        if (usbDetails != null) {
            usbDetails.rawSampleRates.forEach(rates::add)
            usbDetails.rawBitDepths.forEach(depths::add)
            evidence += CapabilityEvidence(
                source = "Descriptors USB (UAC ${usbDetails.audioClassVersion ?: "?"})",
                detail = "${usbDetails.rawSampleRates.size} frecuencias y ${usbDetails.rawBitDepths.size} profundidades declaradas por el DAC",
            )
            if (usbDetails.isAsynchronous == true) {
                evidence += CapabilityEvidence("Endpoint USB", "transferencia asíncrona (reloj controlado por el DAC)")
            }
        }

        if (rates.isEmpty()) warnings += "No se pudo verificar ninguna frecuencia de muestreo para esta salida"
        if (depths.isEmpty()) warnings += "No se pudo verificar ninguna profundidad de bits para esta salida"

        val maxChannels = device.channelCounts?.maxOrNull()?.takeIf { it > 0 } ?: 2

        return DeviceCapabilities(
            routeId = AudioRouteMapper.stableId(device),
            routeName = AudioRouteMapper.displayName(device),
            routeType = routeType,
            supportedSampleRates = rates,
            supportedBitDepths = depths,
            supportedPcmEncodings = encodings,
            maxChannelCount = maxChannels,
            bitPerfectSupport = bitPerfectSupport,
            supportsBitPerfectMixer = bitPerfectMixer,
            mixerSampleRateHz = mixerSampleRateHz(),
            mixerFramesPerBuffer = mixerFramesPerBuffer(),
            isActive = activeOutputDevice()?.let { AudioRouteMapper.stableId(it) == AudioRouteMapper.stableId(device) } ?: false,
            usb = usbDetails,
            evidence = evidence,
            warnings = warnings,
            probedAtEpochMs = nowMs,
        )
    }

    /** Returns (sampleRate, mixerRate) pairs advertised as bit-perfect capable. */
    fun supportedBitPerfectAttributes(device: AudioDeviceInfo): List<Pair<Int, Int>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return emptyList()
        val manager = audioManager ?: return emptyList()
        return try {
            manager.getSupportedMixerAttributes(device)
                .filter { it.mixerBehavior == AudioMixerBehaviorBitPerfect }
                .map { it.format.sampleRate to it.format.sampleRate }
        } catch (exception: UnsupportedOperationException) {
            emptyList()
        } catch (exception: SecurityException) {
            AppLogger.w(TAG, "Sin permiso para consultar atributos de mezclador (MODIFY_AUDIO_SETTINGS)")
            emptyList()
        } catch (exception: Exception) {
            AppLogger.w(TAG, "Error consultando atributos de mezclador: ${exception.javaClass.simpleName}")
            emptyList()
        }
    }

    fun isDirectPlaybackSupported(sampleRate: Int, encoding: Int, channels: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val channelMask = if (channels <= 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(encoding)
            .setChannelMask(channelMask)
            .build()
        return try {
            AudioTrack.isDirectPlaybackSupported(format, audioAttributes)
        } catch (exception: Exception) {
            false
        }
    }

    /** Decoders available on this exact device (requirement 5: ALAC is not a given). */
    fun decoderSupport(codec: Codec): DecoderSupport = runCatching {
        val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        val mimeCandidates = codec.mimeTypes.toTypedArray()
        var hardware = false
        var software = false
        var name: String? = null
        list.codecInfos.forEach { info ->
            if (!info.isEncoder && info.supportedTypes.any { type -> mimeCandidates.any { it.equals(type, ignoreCase = true) } }) {
                val isHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isHardwareAccelerated
                } else {
                    !info.name.startsWith("OMX.google") && !info.name.startsWith("c2.android")
                }
                if (isHardware) {
                    hardware = true
                    name = name ?: info.name
                } else {
                    software = true
                    name = name ?: info.name
                }
            }
        }
        DecoderSupport(
            codec = codec,
            hardwareDecoderAvailable = hardware,
            softwareDecoderAvailable = software,
            decoderName = name,
            notes = when {
                hardware && software -> null
                software -> "Solo decodificador por software: puede consumir más batería y limitar frecuencias altas"
                !hardware && !software -> "Ningún decodificador disponible en este dispositivo"
                else -> null
            },
        )
    }.getOrElse { throwable ->
        AppLogger.w(TAG, "No se pudieron consultar decodificadores: ${throwable.javaClass.simpleName}")
        DecoderSupport(codec, hardwareDecoderAvailable = false, softwareDecoderAvailable = false, notes = "Consulta fallida")
    }

    /** Pre-flight verification used before making any claim in the UI. */
    fun verify(sampleRateHz: Int, bitDepth: Int, channels: Int): Outcome<DeviceCapabilities> {
        val device = activeOutputDevice()
            ?: return Outcome.Failure(
                com.hifiplayer.domain.model.error.AudioDeviceError.OutputUnavailable("", "no hay salida de audio activa"),
            )
        val capabilities = capabilitiesFor(device)
        return Outcome.Success(capabilities)
    }

    private fun candidateEncodingFor(sampleRate: Int): Triple<Int, Int, PcmEncoding>? = when {
        sampleRate > 48_000 -> Triple(ENCODING_PCM_24BIT_PACKED, 24, PcmEncoding.PCM_24)
        else -> Triple(AudioFormat.ENCODING_PCM_16BIT, 16, PcmEncoding.PCM_16)
    }

    private companion object {
        const val TAG = "AudioCapabilitiesManager"

        // Constants that only exist from API 31 onward are inlined to keep the check legal on 26+.
        const val ENCODING_PCM_24BIT_PACKED = 21
        const val ENCODING_PCM_32BIT = 22
        const val AudioMixerBehaviorBitPerfect = 1

        val CANDIDATE_RATES = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)
    }
}
