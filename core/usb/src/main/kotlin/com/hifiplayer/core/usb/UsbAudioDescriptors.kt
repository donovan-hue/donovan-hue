package com.hifiplayer.core.usb

import com.hifiplayer.core.common.logging.AppLogger

/**
 * Parses the raw USB configuration descriptors of an audio device (UAC1/UAC2) to learn what the
 * DAC really advertises: sample rates, bit depths and channel counts.
 *
 * This is the honest source of "the DAC supports 24-bit/96 kHz" – it comes from the device's own
 * AudioStreaming format descriptors, not from marketing or assumptions (requirement 10).
 *
 * Only the descriptor kinds that matter are walked; anything unknown is skipped without failing,
 * and a malformed descriptor set yields empty sets (never invented values).
 */
object UsbAudioDescriptors {

    data class AudioStreamingFormat(
        val altSetting: Int,
        val interfaceNumber: Int,
        val channels: Int,
        /** Bits per subframe: 16, 24, 32... */
        val bitDepth: Int,
        val sampleRates: Set<Int>,
    )

    data class ParsedAudioDevice(
        val formats: List<AudioStreamingFormat>,
        val audioClassVersion: String?,
        val isAsynchronous: Boolean?,
        val controlInterfaceNumber: Int?,
        val streamingInterfaceNumbers: Set<Int>,
        val notes: List<String>,
    ) {
        val sampleRates: Set<Int> get() = formats.flatMap { it.sampleRates }.toSet()
        val bitDepths: Set<Int> get() = formats.map { it.bitDepth }.toSet()
        val channelCounts: Set<Int> get() = formats.map { it.channels }.toSet()
        val maxSampleRate: Int? get() = sampleRates.maxOrNull()
        val maxBitDepth: Int? get() = bitDepths.maxOrNull()
    }

    /** Descriptor types. */
    private const val TYPE_INTERFACE = 0x04
    private const val TYPE_ENDPOINT = 0x05
    private const val TYPE_CS_INTERFACE = 0x24
    private const val CLASS_AUDIO = 0x01

    // AudioStreaming interface subclasses and class-specific descriptor subtypes.
    private const val AS_SUBCLASS = 0x02
    private const val AS_GENERAL = 0x01
    private const val AS_FORMAT_TYPE = 0x02
    private const val FORMAT_TYPE_I = 0x01

    fun parse(rawDescriptors: ByteArray?): ParsedAudioDevice? {
        if (rawDescriptors == null || rawDescriptors.size < 16) return null
        val notes = mutableListOf<String>()
        val formats = mutableListOf<AudioStreamingFormat>()
        var audioClassVersion: String? = null
        var controlInterface: Int? = null
        var streamingInterfaces = mutableSetOf<Int>()
        var currentAltSetting = 0
        var currentInterfaceNumber = -1
        var currentIsStreaming = false
        var currentAudioSubclass = -1
        var pendingChannels: Int? = null
        var pendingSubframeBytes: Int? = null
        var isAsynchronous: Boolean? = null

        var offset = 0
        while (offset + 2 <= rawDescriptors.size) {
            val length = rawDescriptors[offset].toInt() and 0xFF
            if (length == 0 || offset + length > rawDescriptors.size) break
            val descriptorType = rawDescriptors[offset + 1].toInt() and 0xFF

            when (descriptorType) {
                TYPE_INTERFACE -> {
                    if (length >= 9) {
                        currentInterfaceNumber = rawDescriptors[offset + 2].toInt() and 0xFF
                        currentAltSetting = rawDescriptors[offset + 3].toInt() and 0xFF
                        val interfaceClass = rawDescriptors[offset + 5].toInt() and 0xFF
                        currentAudioSubclass = rawDescriptors[offset + 6].toInt() and 0xFF
                        currentIsStreaming = interfaceClass == CLASS_AUDIO && currentAudioSubclass == AS_SUBCLASS
                        if (currentIsStreaming) streamingInterfaces += currentInterfaceNumber
                        if (interfaceClass == CLASS_AUDIO && currentAudioSubclass == 0x01) {
                            controlInterface = currentInterfaceNumber
                        }
                    }
                }

                TYPE_CS_INTERFACE -> {
                    if (length >= 3) {
                        val subtype = rawDescriptors[offset + 2].toInt() and 0xFF
                        when {
                            subtype == 0x01 && length >= 10 -> {
                                // HEADER: bcdADC (UAC1) or bcdADC + category (UAC2)
                                val bcdLow = rawDescriptors[offset + 3].toInt() and 0xFF
                                val bcdHigh = rawDescriptors[offset + 4].toInt() and 0xFF
                                audioClassVersion = "UAC${bcdHigh}.${bcdLow.toString(16)}"
                                if (bcdLow == 0x20) audioClassVersion = "UAC2.0"
                                else if (bcdLow == 0x30) audioClassVersion = "UAC3.0"
                                else audioClassVersion = "UAC1.0"
                            }
                            subtype == AS_GENERAL && length >= 8 && currentIsStreaming -> {
                                // bInterfaceNumber(1) bTerminalLink(1) bDelay(1) wFormatTag(2)
                                val terminalLink = rawDescriptors[offset + 4].toInt() and 0xFF
                                if (terminalLink == 0) notes += "El streaming no declara terminal (posible descriptor incompleto)"
                            }
                            subtype == AS_FORMAT_TYPE && length >= 8 && currentIsStreaming -> {
                                val formatType = rawDescriptors[offset + 3].toInt() and 0xFF
                                if (formatType == FORMAT_TYPE_I) {
                                    val channels = rawDescriptors[offset + 4].toInt() and 0xFF
                                    val subframeBytes = rawDescriptors[offset + 5].toInt() and 0xFF
                                    val bitResolution = rawDescriptors[offset + 6].toInt() and 0xFF
                                    val sampleFreqType = rawDescriptors[offset + 7].toInt() and 0xFF
                                    val rates = mutableSetOf<Int>()
                                    var cursor = offset + 8
                                    if (sampleFreqType == 0) {
                                        // Continuous: tLower, tUpper (3 bytes each, little-endian, in Hz)
                                        if (cursor + 6 <= offset + length) {
                                            val lower = read24Le(rawDescriptors, cursor)
                                            val upper = read24Le(rawDescriptors, cursor + 3)
                                            notes += "Rango continuo declarado: $lower-$upper Hz"
                                        }
                                    } else {
                                        repeat(sampleFreqType.coerceAtMost(16)) {
                                            if (cursor + 3 <= offset + length) {
                                                val rate = read24Le(rawDescriptors, cursor)
                                                if (rate > 0) rates += rate
                                                cursor += 3
                                            }
                                        }
                                    }
                                    pendingChannels = channels
                                    pendingSubframeBytes = subframeBytes
                                    formats += AudioStreamingFormat(
                                        altSetting = currentAltSetting,
                                        interfaceNumber = currentInterfaceNumber,
                                        channels = channels,
                                        bitDepth = if (bitResolution > 0) bitResolution else subframeBytes * 8,
                                        sampleRates = rates,
                                    )
                                } else {
                                    notes += "Formato tipo $formatType no soportado por el analizador (se ignoran sus tasas)"
                                }
                            }
                        }
                    }
                }

                TYPE_ENDPOINT -> {
                    if (length >= 7 && currentIsStreaming) {
                        val attributes = rawDescriptors[offset + 3].toInt() and 0xFF
                        val transferType = attributes and 0x03
                        if (transferType == 0x01) { // isochronous
                            val syncType = (attributes shr 2) and 0x03
                            isAsynchronous = isAsynchronous ?: (syncType == 0x01)
                        }
                    }
                }
            }
            offset += length
        }

        if (formats.isEmpty() && streamingInterfaces.isEmpty()) {
            AppLogger.w("UsbAudioDescriptors", "El dispositivo USB no expone interfaces de audio reconocibles")
            return null
        }

        if (pendingChannels != null && pendingSubframeBytes != null) {
            notes += "Último formato: $pendingChannels canales, ${pendingSubframeBytes * 8}-bit por subframe"
        }

        return ParsedAudioDevice(
            formats = formats,
            audioClassVersion = audioClassVersion,
            isAsynchronous = isAsynchronous,
            controlInterfaceNumber = controlInterface,
            streamingInterfaceNumbers = streamingInterfaces,
            notes = notes,
        )
    }

    private fun read24Le(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or ((bytes[offset + 2].toInt() and 0xFF) shl 16)
}
