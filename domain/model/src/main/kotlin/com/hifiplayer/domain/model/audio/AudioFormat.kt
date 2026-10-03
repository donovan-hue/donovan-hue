package com.hifiplayer.domain.model.audio

/**
 * Audio codec families the app understands (requirement 5: FLAC, WAV, ALAC, MP3, AAC as a
 * minimum). [isLossless] describes the *format*, never the device: whether we can actually
 * decode it here is decided at runtime by `domain.model.device.DecoderSupport`.
 */
enum class Codec(
    val id: String,
    val displayName: String,
    val isLossless: Boolean,
    val containerExtensions: List<String>,
    val mimeTypes: List<String>,
) {
    FLAC("flac", "FLAC", true, listOf("flac"), listOf("audio/flac", "audio/x-flac")),
    ALAC("alac", "ALAC", true, listOf("m4a", "mp4", "caf"), listOf("audio/alac", "audio/x-alac", "audio/mp4")),
    WAV("wav", "WAV / PCM", true, listOf("wav", "wave"), listOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")),
    AIFF("aiff", "AIFF / PCM", true, listOf("aiff", "aif", "aifc"), listOf("audio/aiff", "audio/x-aiff")),
    MP3("mp3", "MP3", false, listOf("mp3"), listOf("audio/mpeg", "audio/mp3", "audio/x-mpeg")),
    AAC("aac", "AAC", false, listOf("m4a", "mp4", "aac"), listOf("audio/aac", "audio/mp4a-latm", "audio/mp4")),
    VORBIS("vorbis", "Vorbis", false, listOf("ogg", "oga"), listOf("audio/ogg", "application/ogg")),
    OPUS("opus", "Opus", false, listOf("opus", "ogg"), listOf("audio/opus", "audio/ogg")),
    DSD("dsd", "DSD (DSF/DFF)", true, listOf("dsf", "dff"), listOf("audio/x-dsf", "audio/x-dff")),
    UNKNOWN("unknown", "Desconocido", false, emptyList(), emptyList());

    companion object {
        /** Lossless formats treated as first-class citizens by the library and the UI. */
        val LOSSLESS_TARGETS: List<Codec> = listOf(FLAC, ALAC, WAV, AIFF)

        /** Lossy formats supported for playback: decoded as-is, never "enhanced". */
        val LOSSY_TARGETS: List<Codec> = listOf(MP3, AAC, VORBIS, OPUS)

        /** Everything the scanner will look at by default. */
        val SCANNABLE: List<Codec> = LOSSLESS_TARGETS + LOSSY_TARGETS

        fun fromExtension(extension: String?): Codec {
            val ext = extension?.lowercase()?.removePrefix(".") ?: return UNKNOWN
            return entries.firstOrNull { ext in it.containerExtensions } ?: UNKNOWN
        }

        fun fromMimeType(mimeType: String?): Codec {
            val mime = mimeType?.lowercase() ?: return UNKNOWN
            return entries.filter { it != UNKNOWN }.firstOrNull { mime in it.mimeTypes } ?: UNKNOWN
        }

        /**
         * Container formats are ambiguous (`.m4a` can be AAC or ALAC, `.ogg` can be Vorbis or
         * Opus). This returns the best candidate; the real codec is confirmed by probing the
         * file header in `core:metadata`, and the UI only shows what the probe confirmed.
         */
        fun fromExtensionAndMime(extension: String?, mimeType: String?, isLosslessHint: Boolean): Codec {
            val byMime = fromMimeType(mimeType)
            val byExt = fromExtension(extension)
            return when {
                byMime == FLAC || byExt == FLAC -> FLAC
                byMime == MP3 || byExt == MP3 -> MP3
                byMime == WAV || byExt == WAV -> WAV
                byMime == AIFF || byExt == AIFF -> AIFF
                byMime == DSD -> DSD
                byMime == ALAC -> ALAC
                byMime == AAC -> AAC
                byMime == OPUS -> OPUS
                byMime == VORBIS -> VORBIS
                isLosslessHint && byExt in listOf(ALAC, AAC) -> ALAC
                byExt != UNKNOWN -> byExt
                else -> UNKNOWN
            }
        }
    }
}

/** PCM sample representation. 24-bit int and 32-bit float are never interchangeable. */
enum class PcmEncoding(val bits: Int, val isFloat: Boolean, val displayName: String, val bytesPerSample: Int) {
    PCM_16(16, false, "16-bit int", 2),
    PCM_24(24, false, "24-bit int", 3),
    PCM_32_INT(32, false, "32-bit int", 4),
    PCM_32_FLOAT(32, true, "32-bit float", 4);

    companion object {
        fun of(bits: Int, isFloat: Boolean = false): PcmEncoding = when {
            isFloat -> PCM_32_FLOAT
            bits <= 16 -> PCM_16
            bits <= 24 -> PCM_24
            else -> PCM_32_INT
        }
    }
}

/**
 * A fully described audio format. Every number the UI shows comes from one of these, produced
 * by parsing the file header or by querying the platform – never assumed (requirement 5/10).
 */
data class AudioFormatSpec(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channels: Int,
    val codec: Codec,
    val pcmEncoding: PcmEncoding? = PcmEncoding.of(bitDepth),
    val bitrateKbps: Int? = null,
    val isDsd: Boolean = codec == Codec.DSD,
    val isVariableBitrate: Boolean = false,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be > 0 (was $sampleRateHz)" }
        require(channels > 0) { "channels must be > 0 (was $channels)" }
    }

    val isLossless: Boolean get() = codec.isLossless
    val isFloat: Boolean get() = pcmEncoding?.isFloat == true

    /** Hi-res: anything above CD quality. */
    val isHighResolution: Boolean get() = bitDepth > 16 || sampleRateHz > 48_000

    val channelLayoutName: String
        get() = when (channels) {
            1 -> "Mono"
            2 -> "Estéreo"
            3 -> "2.1"
            4 -> "Cuadrafónico"
            6 -> "5.1"
            8 -> "7.1"
            else -> "$channels canales"
        }

    /** e.g. "24-bit / 96 kHz" – the exact label shown in the player. */
    val label: String
        get() = if (isDsd) {
            "DSD ${(sampleRateHz / 44_100)}x"
        } else {
            buildString {
                append(bitDepth).append("-bit")
                append(" / ")
                append(formatSampleRate(sampleRateHz))
                if (!isLossless && bitrateKbps != null) append(" · ").append(bitrateKbps).append(" kbps")
            }
        }

    /** True when everything that matters for a bit-perfect comparison is identical. */
    fun matchesExactly(other: AudioFormatSpec): Boolean =
        sampleRateHz == other.sampleRateHz && bitDepth == other.bitDepth &&
            isFloat == other.isFloat && channels == other.channels

    companion object {
        fun formatSampleRate(hz: Int): String =
            if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format(java.util.Locale.US, "%.1f kHz", hz / 1000.0)
    }
}

fun formatSampleRate(hz: Int): String = AudioFormatSpec.formatSampleRate(hz)
