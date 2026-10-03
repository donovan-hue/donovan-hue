package com.hifiplayer.domain.model.device

import com.hifiplayer.domain.model.audio.AudioFormatSpec
import com.hifiplayer.domain.model.audio.Codec
import com.hifiplayer.domain.model.audio.PcmEncoding

/** Physical/logical kind of an output route. */
public enum class OutputRouteType(public val displayName: String) {
    BUILTIN_SPEAKER("Altavoz del dispositivo"),
    BUILTIN_EARPIECE("Auricular interno"),
    WIRED_HEADPHONES("Auriculares con cable"),
    WIRED_HEADSET("Auriculares con micrófono"),
    USB_DEVICE("DAC USB"),
    USB_HEADSET("Auriculares USB"),
    USB_ACCESSORY("Accesorio USB"),
    BLUETOOTH_A2DP("Bluetooth A2DP"),
    BLUETOOTH_LE("Bluetooth LE"),
    HDMI("HDMI"),
    HEARING_AID("Audífono"),
    UNKNOWN("Salida desconocida"),
    ;

    public val isUsb: Boolean get() = this == USB_DEVICE || this == USB_HEADSET || this == USB_ACCESSORY
    public val isWireless: Boolean get() = this == BLUETOOTH_A2DP || this == BLUETOOTH_LE
    /** Only wired/USB paths can carry an unmodified bitstream to a DAC. */
    public val canCarryBitPerfect: Boolean get() = isUsb || this == WIRED_HEADPHONES || this == WIRED_HEADSET || this == HDMI
}

/** Result of asking the platform whether the bit-perfect (non-mixing) path is available. */
public enum class BitPerfectSupport(public val displayName: String, public val isActive: Boolean) {
    /** Android < 14 has no mixer-attribute API at all. */
    API_NOT_SUPPORTED("Requiere Android 14 o superior", isActive = false),
    /** API exists and works, but only for a wired/USB output device. */
    NEEDS_WIRED_OR_USB_OUTPUT("Conecta un DAC USB o salida con cable", isActive = false),
    /** The device/mixer answered that it cannot run in bit-perfect mode for this format. */
    DEVICE_NOT_CAPABLE("El dispositivo no ofrece ruta bit-perfect para este formato", isActive = false),
    /** Requested but the platform silently kept mixing. */
    DEGRADED("Solicitado pero el sistema mantiene el mezclador", isActive = false),
    ACTIVE("Bit-perfect activo", isActive = true),
    UNKNOWN("No verificado", isActive = false),
}

/** One piece of measured evidence, so every claim in the UI can be traced back to a query. */
public data class CapabilityEvidence(
    public val source: String,
    public val detail: String,
) {
    public val line: String get() = "$source → $detail"
}

/** USB audio descriptor information read directly from the device (UAC1/UAC2). */
public data class UsbAudioDetails(
    public val deviceName: String?,
    public val manufacturer: String?,
    public val productName: String?,
    public val vendorId: Int,
    public val productId: Int,
    public val audioClassVersion: String?,
    public val interfaceNumber: Int?,
    public val altSetting: Int?,
    public val isAsynchronous: Boolean?,
    public val rawSampleRates: Set<Int>,
    public val rawBitDepths: Set<Int>,
    public val rawChannelCounts: Set<Int>,
) {
    public val vendorProductLabel: String
        get() = "VID:%04X PID:%04X".format(vendorId, productId)
}

/**
 * What a specific output route *actually* supports, as reported by the platform in this session.
 *
 * Rules of engagement for the UI:
 *  - Never print a sample rate / bit depth that is not contained here.
 *  - [evidence] is displayed in the diagnostics screen verbatim.
 *  - [warnings] explains why a capability is missing instead of silently hiding it.
 */
public data class DeviceCapabilities(
    public val routeId: String,
    public val routeName: String,
    public val routeType: OutputRouteType,
    public val supportedSampleRates: Set<Int> = emptySet(),
    public val supportedBitDepths: Set<Int> = emptySet(),
    public val supportedPcmEncodings: Set<PcmEncoding> = emptySet(),
    public val maxChannelCount: Int = 2,
    public val bitPerfectSupport: BitPerfectSupport = BitPerfectSupport.UNKNOWN,
    public val supportsBitPerfectMixer: Boolean = false,
    public val mixerSampleRateHz: Int? = null,
    public val mixerFramesPerBuffer: Int? = null,
    public val isActive: Boolean = false,
    public val usb: UsbAudioDetails? = null,
    public val evidence: List<CapabilityEvidence> = emptyList(),
    public val warnings: List<String> = emptyList(),
    public val probedAtEpochMs: Long = 0L,
) {
    public val isUsb: Boolean get() = routeType.isUsb

    /** Highest rate the route reports, or null when nothing was reported. */
    public val maxSampleRateHz: Int? get() = supportedSampleRates.maxOrNull()
    public val maxBitDepth: Int? get() = supportedBitDepths.maxOrNull()
    public val supports24Bit: Boolean get() = 24 in supportedBitDepths || PcmEncoding.PCM_24 in supportedPcmEncodings
    public val supportsFloat: Boolean get() = PcmEncoding.PCM_32_FLOAT in supportedPcmEncodings
    public val supportsHighRes: Boolean
        get() = (maxBitDepth ?: 0) >= 24 || (maxSampleRateHz ?: 0) > 48_000

    /**
     * Short, honest summary such as "hasta 24-bit / 192 kHz" or
     * "hasta 24-bit / 48 kHz (el sistema mezcla a 48 kHz)".
     */
    public val summary: String
        get() {
            val depth = maxBitDepth ?: return "Capacidades no verificadas"
            val rate = maxSampleRateHz ?: return "$depth-bit (frecuencia no verificada)"
            val base = "hasta $depth-bit / ${AudioFormatSpec.formatSampleRate(rate)}"
            val reported = supportedSampleRates.size
            return "$base ($reported frecuencias verificadas)"
        }

    public fun supports(sampleRateHz: Int, bitDepth: Int): Boolean =
        sampleRateHz in supportedSampleRates && bitDepth in supportedBitDepths

    public fun supports(spec: AudioFormatSpec): Boolean =
        supports(spec.sampleRateHz, spec.bitDepth) || (spec.isFloat && supportsFloat)
}

/** Which decoders this specific device can run for a given codec. */
public data class DecoderSupport(
    public val codec: Codec,
    public val hardwareDecoderAvailable: Boolean,
    public val softwareDecoderAvailable: Boolean,
    public val decoderName: String? = null,
    public val notes: String? = null,
) {
    public val playable: Boolean get() = hardwareDecoderAvailable || softwareDecoderAvailable
    public val isSoftwareOnly: Boolean get() = !hardwareDecoderAvailable && softwareDecoderAvailable
}

/** Aggregated, shareable report used by the diagnostics screen and bug reports. */
public data class CapabilityReport(
    public val deviceManufacturer: String,
    public val deviceModel: String,
    public val androidRelease: String,
    public val androidSdkInt: Int,
    public val appVersion: String,
    public val buildFingerprint: String,
    public val decoders: List<DecoderSupport>,
    public val outputs: List<DeviceCapabilities>,
    public val activeOutput: DeviceCapabilities?,
    public val mixerSampleRateHz: Int?,
    public val mixerFramesPerBuffer: Int?,
    public val isLowLatencyOutputSupported: Boolean,
    public val bitPerfectApiAvailable: Boolean,
    public val generatedAtEpochMs: Long,
    public val notes: List<String> = emptyList(),
) {
    /** Plain-text version for "share diagnostics" – deliberately verbose and copy-pasteable. */
    public fun toPlainText(): String = buildString {
        appendLine("HiFi Player · Informe de capacidades")
        appendLine("Generado: ${java.time.Instant.ofEpochMilli(generatedAtEpochMs)}")
        appendLine("App: $appVersion")
        appendLine()
        appendLine("== Dispositivo ==")
        appendLine("$deviceManufacturer $deviceModel · Android $androidRelease (API $androidSdkInt)")
        appendLine("Build: $buildFingerprint")
        appendLine("API bit-perfect (Android 14+): ${if (bitPerfectApiAvailable) "disponible" else "no disponible"}")
        appendLine("Mezclador del sistema: ${mixerSampleRateHz ?: "?"} Hz, ${mixerFramesPerBuffer ?: "?"} frames/buffer")
        appendLine("Salida de baja latencia: ${if (isLowLatencyOutputSupported) "sí" else "no"}")
        appendLine()
        appendLine("== Salidas ==")
        outputs.forEach { o ->
            appendLine("- ${o.routeName} [${o.routeType.displayName}]${if (o.isActive) " (activa)" else ""}")
            appendLine("    ${o.summary}")
            appendLine("    bit-perfect: ${o.bitPerfectSupport.displayName}")
            if (o.supportedPcmEncodings.isNotEmpty()) {
                appendLine("    PCM: ${o.supportedPcmEncodings.joinToString { it.displayName }}")
            }
            o.usb?.let { usb ->
                appendLine("    USB: ${usb.productName ?: usb.deviceName ?: "?"} ${usb.vendorProductLabel} UAC ${usb.audioClassVersion ?: "?"}")
                if (usb.isAsynchronous == true) appendLine("    Transferencia asíncrona: sí")
            }
            o.evidence.forEach { appendLine("    evidencia: ${it.line}") }
            o.warnings.forEach { appendLine("    aviso: $it") }
        }
        appendLine()
        appendLine("== Decodificadores ==")
        decoders.forEach { d ->
            appendLine(
                "- ${d.codec.displayName}: " +
                    when {
                        d.hardwareDecoderAvailable && d.softwareDecoderAvailable -> "hardware + software"
                        d.hardwareDecoderAvailable -> "hardware (${d.decoderName ?: "?"})"
                        d.softwareDecoderAvailable -> "software (${d.decoderName ?: "?"})"
                        else -> "NO DISPONIBLE"
                    } + (d.notes?.let { " · $it" } ?: ""),
            )
        }
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("== Notas ==")
            notes.forEach { appendLine("- $it") }
        }
    }
}
