package com.hifiplayer.core.metadata

import com.hifiplayer.domain.model.audio.Codec
import com.hifiplayer.domain.model.audio.PcmEncoding

/**
 * Result of reading a file's *actual* header (requirement 5).
 *
 * Every field is either measured or absent. [durationMs] stays null when it cannot be derived from
 * the header alone; callers then fall back to the provider's duration.
 */
data class ProbedFormat(
    val codec: Codec,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channels: Int,
    val bitrateKbps: Int?,
    val durationMs: Long?,
    val isFloat: Boolean = false,
    val isVariableBitrate: Boolean = false,
    val containerName: String,
    val notes: List<String> = emptyList(),
) {
    val pcmEncoding: PcmEncoding get() = PcmEncoding.of(bitDepth, isFloat)

    val displayLabel: String
        get() = buildString {
            append(bitDepth).append("-bit / ")
            append(
                if (sampleRateHz % 1000 == 0) "${sampleRateHz / 1000} kHz"
                else String.format(java.util.Locale.US, "%.1f kHz", sampleRateHz / 1000.0),
            )
            append(" · ")
            append(channels)
            append(if (channels == 1) " canal" else " canales")
        }
}

/**
 * Reads audio headers to determine codec, sample rate, bit depth, channel count, bitrate and
 * duration — without decoding the audio and without copying the file.
 *
 * Supported: FLAC, WAV/RIFF (PCM int and IEEE float, extensible), MPEG Layer III (.mp3),
 * ISO-BMFF (.m4a/.mp4 carrying AAC or ALAC) and Ogg (Vorbis/Opus identification).
 *
 * The probe is deliberately strict: when a header cannot be parsed it returns null instead of a
 * guess, and the UI shows "sin analizar" (requirement 5: never show an undetected spec).
 */
object AudioHeaderProbe {

    fun probe(head: ByteArray, fileSizeBytes: Long): ProbedFormat? {
        if (head.size < 16) return null
        return when {
            head.startsWithAscii("fLaC") -> probeFlac(head, fileSizeBytes)
            head.startsWithAscii("RIFF") && head.containsAscii("WAVE", 8) -> probeWave(head)
            head.startsWithAscii("OggS") -> probeOgg(head)
            head.startsWithAscii("ID3") -> probeMpegAudio(head, fileSizeBytes, id3Size = id3v2Size(head))
            head.looksLikeMpegSync() -> probeMpegAudio(head, fileSizeBytes, id3Size = 0)
            head.containsAscii("ftyp", 4) -> probeIsoBmff(head, fileSizeBytes)
            else -> null
        }
    }

    // ------------------------------------------------------------------ FLAC

    private fun probeFlac(head: ByteArray, fileSizeBytes: Long): ProbedFormat? = parseSafely<ProbedFormat?>(null) {
        val reader = BeReader(head, 4)
        var streamInfo: ProbedFormat? = null
        var isLast = false
        var guard = 0
        while (!isLast && guard++ < 64) {
            val header = reader.u8()
            isLast = (header and 0x80) != 0
            val type = header and 0x7F
            val length = reader.u24()
            when (type) {
                0 -> streamInfo = parseStreamInfo(reader.bytes(length))
                4 -> reader.skip(length) // Vorbis comments are read by TagParser, not needed here
                else -> reader.skip(length)
            }
        }
        streamInfo?.let { format ->
            format.copy(bitrateKbps = bitrateFromSize(fileSizeBytes, format.durationMs))
        }
    }

    private fun parseStreamInfo(block: ByteArray): ProbedFormat? {
        if (block.size < 34) return null
        return parseSafely<ProbedFormat?>(null) {
            val bits = BitReader(block)
            bits.readBits(16) // min block size
            bits.readBits(16) // max block size
            bits.readBits(24) // min frame size
            bits.readBits(24) // max frame size
            val sampleRate = bits.readBits(20).toInt()
            val channels = (bits.readBits(3) + 1).toInt()
            val bitDepth = (bits.readBits(5) + 1).toInt()
            val totalSamples = bits.readBits(36)
            if (sampleRate <= 0 || channels <= 0 || bitDepth <= 0) return@parseSafely null
            ProbedFormat(
                codec = Codec.FLAC,
                sampleRateHz = sampleRate,
                bitDepth = bitDepth,
                channels = channels,
                bitrateKbps = null,
                durationMs = if (totalSamples > 0) (totalSamples * 1000L) / sampleRate else null,
                isVariableBitrate = true,
                containerName = "FLAC",
            )
        }
    }

    // ------------------------------------------------------------------ WAV (RIFF)

    private fun probeWave(head: ByteArray): ProbedFormat? = parseSafely<ProbedFormat?>(null) {
        val reader = LeReader(head, 12)
        var formatCode = 1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var validBits = 0
        var byteRate = 0L
        var dataSize = 0L
        val notes = mutableListOf<String>()
        var guard = 0

        while (reader.remaining >= 8 && guard++ < 128) {
            val chunkId = reader.ascii(4)
            val chunkSize = reader.u32()
            when (chunkId) {
                "fmt " -> {
                    formatCode = reader.u16()
                    channels = reader.u16()
                    sampleRate = reader.u32().toInt()
                    byteRate = reader.u32()
                    reader.skip(2) // block align
                    bitsPerSample = reader.u16()
                    if (chunkSize > 16L) {
                        val cbSize = if (reader.has(2)) reader.u16() else 0
                        if (formatCode == 0xFFFE && cbSize >= 22) {
                            reader.skip(2) // channel mask
                            formatCode = reader.u16()
                            validBits = reader.u16()
                            reader.skip((chunkSize - 18L - 8L).coerceAtLeast(0L).toInt())
                        } else {
                            reader.skip((chunkSize - 18L).coerceAtLeast(0L).toInt())
                        }
                    }
                }
                "data" -> {
                    dataSize = chunkSize
                    break
                }
                else -> reader.skip(chunkSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        }

        if (sampleRate <= 0 || channels <= 0) return@parseSafely null
        val isFloat = formatCode == 3
        val depth = when {
            validBits > 0 -> validBits
            bitsPerSample > 0 -> bitsPerSample
            else -> 16
        }
        val effectiveByteRate = if (byteRate > 0) byteRate else sampleRate.toLong() * channels * (depth / 8)
        if (isFloat) notes += "PCM en coma flotante IEEE (32-bit float)"

        ProbedFormat(
            codec = Codec.WAV,
            sampleRateHz = sampleRate,
            bitDepth = depth,
            channels = channels,
            bitrateKbps = if (effectiveByteRate > 0) ((effectiveByteRate * 8L) / 1000L).toInt() else null,
            durationMs = if (dataSize > 0 && effectiveByteRate > 0) (dataSize * 1000L) / effectiveByteRate else null,
            isFloat = isFloat,
            isVariableBitrate = false,
            containerName = "WAV/RIFF",
            notes = notes,
        )
    }

    // ------------------------------------------------------------------ MPEG Layer III

    private val bitrateV1L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val bitrateV2L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val sampleRatesByVersion = mapOf(
        3 to intArrayOf(44_100, 48_000, 32_000),
        2 to intArrayOf(22_050, 24_000, 16_000),
        0 to intArrayOf(11_025, 12_000, 8_000),
    )

    private fun probeMpegAudio(head: ByteArray, fileSizeBytes: Long, id3Size: Int): ProbedFormat? =
        parseSafely<ProbedFormat?>(null) {
            var frameStart = -1
            var offset = id3Size
            while (offset + 4 <= head.size) {
                if ((head[offset].toInt() and 0xFF) == 0xFF && (head[offset + 1].toInt() and 0xE0) == 0xE0) {
                    frameStart = offset
                    break
                }
                offset++
            }
            if (frameStart < 0 || frameStart + 4 > head.size) return@parseSafely null

            val b1 = head[frameStart + 1].toInt() and 0xFF
            val b2 = head[frameStart + 2].toInt() and 0xFF
            val versionBits = (b1 shr 3) and 0x03
            val layerBits = (b1 shr 1) and 0x03
            val bitrateIndex = (b2 shr 4) and 0x0F
            val sampleRateIndex = (b2 shr 2) and 0x03
            val padding = (b2 shr 1) and 0x01
            val channelMode = if (frameStart + 3 < head.size) (head[frameStart + 3].toInt() and 0xC0) ushr 6 else 0

            if (layerBits != 1) return@parseSafely null // only Layer III is supported as MP3
            val rates = sampleRatesByVersion[versionBits] ?: return@parseSafely null
            if (sampleRateIndex > 2) return@parseSafely null
            val sampleRate = rates[sampleRateIndex]
            val isV1 = versionBits == 3
            val table = if (isV1) bitrateV1L3 else bitrateV2L3
            if (bitrateIndex == 0 || bitrateIndex >= table.size) return@parseSafely null
            val bitrateKbps = table[bitrateIndex]
            if (bitrateKbps == 0) return@parseSafely null

            val samplesPerFrame = if (isV1) 1152 else 576
            val sideInfoSize = when {
                isV1 && channelMode == 3 -> 17
                isV1 -> 32
                channelMode == 3 -> 9
                else -> 17
            }

            var frameCount: Long? = null
            var isVbr = false
            val xingOffset = frameStart + 4 + sideInfoSize
            if (xingOffset + 12 <= head.size) {
                val marker = String(head, xingOffset, 4, Charsets.US_ASCII)
                if (marker == "Xing" || marker == "Info") {
                    val flags = beU32(head, xingOffset + 4)
                    if (flags and 0x01L != 0L) frameCount = beU32(head, xingOffset + 8)
                    isVbr = marker == "Xing"
                }
            }
            if (frameCount == null) {
                val vbriOffset = frameStart + 4 + 32
                if (vbriOffset + 18 <= head.size && String(head, vbriOffset, 4, Charsets.US_ASCII) == "VBRI") {
                    frameCount = beU32(head, vbriOffset + 14)
                    isVbr = true
                }
            }

            val durationMs = when {
                frameCount != null && frameCount > 0 -> (frameCount * samplesPerFrame * 1000L) / sampleRate
                fileSizeBytes > 0 -> (fileSizeBytes * 8L) / bitrateKbps
                else -> null
            }

            ProbedFormat(
                codec = Codec.MP3,
                sampleRateHz = sampleRate,
                bitDepth = 16, // MP3 has no native bit depth; the decoder outputs 16-bit PCM
                channels = if (channelMode == 3) 1 else 2,
                bitrateKbps = bitrateKbps,
                durationMs = durationMs,
                isVariableBitrate = isVbr,
                containerName = "MPEG Audio",
                notes = buildList {
                    add("MPEG ${if (isV1) "1" else "2"} Layer III")
                    if (isVbr) add("Bitrate variable (cabecera Xing/VBRI)") else add("Bitrate constante")
                },
            )
        }

    // ------------------------------------------------------------------ ISO-BMFF: AAC and ALAC

    private fun probeIsoBmff(head: ByteArray, fileSizeBytes: Long): ProbedFormat? {
        var format: ProbedFormat? = null
        var mediaHeaderDurationMs: Long? = null
        var movieHeaderDurationMs: Long? = null

        walkAtoms(head, 0, head.size, 0) { type, payloadStart, payloadEnd, _ ->
            when (type) {
                "mvhd" -> movieHeaderDurationMs = parseHeaderDuration(head, payloadStart, versionAtEnd = false)
                "mdhd" -> mediaHeaderDurationMs = parseHeaderDuration(head, payloadStart, versionAtEnd = false)
                "stsd" -> format = parseSampleDescription(head, payloadStart, payloadEnd)
                else -> Unit
            }
            true
        }

        return format?.let { parsed ->
            val duration = parsed.durationMs ?: mediaHeaderDurationMs ?: movieHeaderDurationMs
            parsed.copy(
                durationMs = duration,
                bitrateKbps = parsed.bitrateKbps ?: bitrateFromSize(fileSizeBytes, duration),
                isVariableBitrate = parsed.isVariableBitrate,
            )
        }
    }

    /** mvhd/mdhd: version(1) flags(3) [creation, modification, timescale, duration] (32 or 64 bit). */
    private fun parseHeaderDuration(head: ByteArray, start: Int, versionAtEnd: Boolean): Long? =
        parseSafely<Long?>(null) {
            val reader = BeReader(head, start)
            val version = reader.u8()
            reader.skip(3) // flags
            val timescale: Long
            val duration: Long
            if (version == 1) {
                reader.skip(16); timescale = reader.u32(); duration = reader.u64()
            } else {
                reader.skip(8); timescale = reader.u32(); duration = reader.u32()
            }
            if (timescale <= 0L || duration <= 0L) null else (duration * 1000L) / timescale
        }

    private fun parseSampleDescription(head: ByteArray, start: Int, end: Int): ProbedFormat? =
        parseSafely<ProbedFormat?>(null) {
            val reader = BeReader(head, start)
            reader.skip(4) // version + flags
            val entryCount = reader.u32()
            if (entryCount <= 0L) return@parseSafely null
            val entrySize = reader.u32().toInt()
            val entryType = reader.ascii(4)
            val bodyStart = reader.offset
            val bodyEnd = (bodyStart + (entrySize - 8)).coerceAtMost(minOf(end, head.size))
            when (entryType) {
                "mp4a" -> parseAudioSampleEntry(head, bodyStart, bodyEnd, assumedAlac = false)
                "alac" -> parseAudioSampleEntry(head, bodyStart, bodyEnd, assumedAlac = true)
                else -> null
            }
        }

    private fun parseAudioSampleEntry(
        head: ByteArray,
        bodyStart: Int,
        bodyEnd: Int,
        assumedAlac: Boolean,
    ): ProbedFormat? = parseSafely<ProbedFormat?>(null) {
        val reader = BeReader(head, bodyStart)
        reader.skip(6) // reserved
        reader.skip(2) // data reference index
        reader.skip(8) // version, revision, vendor
        val channels = reader.u16()
        reader.skip(2)  // sample size field (unreliable: often 16 even for 24-bit ALAC)
        reader.skip(4)  // compression id + packet size
        val sampleRate = (reader.u32() shr 16).toInt()

        var bitDepth = 16
        var codec = if (assumedAlac) Codec.ALAC else Codec.AAC
        val notes = mutableListOf<String>()
        var declaredBitrate: Int? = null
        var declaredRate: Int? = null
        var declaredChannels: Int? = null

        walkAtoms(head, reader.offset, bodyEnd, 0) { type, payloadStart, payloadEnd, _ ->
            when (type) {
                "esds" -> {
                    val objectType = parseEsdsObjectType(head, payloadStart, payloadEnd)
                    if (objectType == 0x6D) {
                        codec = Codec.ALAC
                        notes += "ALAC declarado dentro de mp4a/esds"
                    }
                }
                "alac" -> {
                    codec = Codec.ALAC
                    parseAlacCookie(head, payloadStart, payloadEnd)?.let { cookie ->
                        bitDepth = cookie.bitDepth
                        declaredRate = cookie.sampleRateHz
                        declaredChannels = cookie.channels
                        declaredBitrate = cookie.bitrateKbps
                        notes += "ALAC: profundidad ${cookie.bitDepth}-bit confirmada en la magic cookie"
                    }
                }
                else -> Unit
            }
            true
        }

        val effectiveRate = declaredRate ?: sampleRate
        val effectiveChannels = declaredChannels ?: channels
        if (effectiveRate <= 0 || effectiveChannels <= 0) return@parseSafely null

        ProbedFormat(
            codec = codec,
            sampleRateHz = effectiveRate,
            bitDepth = bitDepth,
            channels = effectiveChannels,
            bitrateKbps = declaredBitrate,
            durationMs = null,
            isVariableBitrate = true,
            containerName = if (codec == Codec.ALAC) "ISO-BMFF (ALAC)" else "ISO-BMFF (AAC)",
            notes = notes,
        )
    }

    private fun parseEsdsObjectType(head: ByteArray, start: Int, end: Int): Int? = parseSafely<Int?>(null) {
        val reader = BeReader(head, start)
        reader.skip(4) // version + flags
        var iterations = 0
        while (reader.offset < end && iterations++ < 32) {
            val tag = reader.u8()
            var length = 0
            var shift = 0
            while (shift <= 21) {
                val byte = reader.u8()
                length = (length shl 7) or (byte and 0x7F)
                shift += 7
                if (byte and 0x80 == 0) break
            }
            when (tag) {
                0x03 -> {
                    reader.skip(2) // ES id
                    val flags = reader.u8()
                    if (flags and 0x80 != 0) reader.skip(2) // dependsOn
                    if (flags and 0x40 != 0) { val urlLength = reader.u8(); reader.skip(urlLength) }
                    if (flags and 0x20 != 0) reader.skip(2) // OCR ES id
                }
                0x04 -> return@parseSafely reader.u8() // objectTypeIndication
                else -> reader.skip(length)
            }
        }
        null
    }

    private data class AlacCookie(val bitDepth: Int, val channels: Int, val sampleRateHz: Int, val bitrateKbps: Int?)

    private fun parseAlacCookie(head: ByteArray, start: Int, end: Int): AlacCookie? = parseSafely<AlacCookie?>(null) {
        // The cookie may be wrapped as [size]["alac"][version+flags][cookie] or sit bare.
        val offset = if (end - start >= 12 && String(head, start + 4, 4, Charsets.US_ASCII) == "alac") start + 12 else start
        if (end - offset < 24) return@parseSafely null
        val reader = BeReader(head, offset)
        reader.skip(4) // frame length
        reader.skip(1) // compatible version
        val bitDepth = reader.u8()
        reader.skip(3) // pb, mb, kb
        val channels = reader.u8()
        reader.skip(2) // max run
        reader.skip(4) // max frame bytes
        val avgBitRate = reader.u32()
        val sampleRate = reader.u32().toInt()
        if (sampleRate <= 0 || channels <= 0) return@parseSafely null
        AlacCookie(
            bitDepth = bitDepth.coerceIn(8, 32),
            channels = channels,
            sampleRateHz = sampleRate,
            bitrateKbps = if (avgBitRate > 0) (avgBitRate / 1000).toInt() else null,
        )
    }

    // ------------------------------------------------------------------ Ogg

    private fun probeOgg(head: ByteArray): ProbedFormat? = parseSafely<ProbedFormat?>(null) {
        val reader = LeReader(head, 0)
        reader.skip(26)
        if (!reader.has(1)) return@parseSafely null
        val segmentCount = reader.u8()
        var payloadSize = 0
        val segmentTable = IntArray(segmentCount)
        for (index in 0 until segmentCount) {
            segmentTable[index] = reader.u8()
            payloadSize += segmentTable[index]
        }
        if (!reader.has(payloadSize)) return@parseSafely null
        val payload = reader.bytes(payloadSize)
        when {
            payload.startsWithAscii("OpusHead") -> {
                val payloadReader = LeReader(payload, 8)
                val channels = payloadReader.u8()
                payloadReader.skip(2)
                val declaredRate = payloadReader.u32().toInt()
                ProbedFormat(
                    codec = Codec.OPUS,
                    sampleRateHz = 48_000,
                    bitDepth = 16,
                    channels = channels.coerceAtLeast(1),
                    bitrateKbps = null,
                    durationMs = null,
                    containerName = "Ogg (Opus)",
                    notes = listOf("Opus decodifica siempre a 48 kHz", "Entrada declarada: $declaredRate Hz"),
                )
            }
            payload.size > 7 && payload[0].toInt() == 0x01 && String(payload, 1, 6, Charsets.US_ASCII) == "vorbis" -> {
                val payloadReader = LeReader(payload, 11)
                val channels = payloadReader.u8()
                val rate = payloadReader.u32().toInt()
                ProbedFormat(
                    codec = Codec.VORBIS,
                    sampleRateHz = rate,
                    bitDepth = 16,
                    channels = channels.coerceAtLeast(1),
                    bitrateKbps = null,
                    durationMs = null,
                    containerName = "Ogg (Vorbis)",
                )
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------ Helpers

    /** Bitrate estimate from file size, in kbps (bits / milliseconds). */
    private fun bitrateFromSize(fileSizeBytes: Long, durationMs: Long?): Int? {
        if (fileSizeBytes <= 0L || durationMs == null || durationMs <= 0L) return null
        val kbps = (fileSizeBytes * 8L) / durationMs
        return if (kbps in 1..100_000) kbps.toInt() else null
    }

    private fun beU32(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0L
        return ((bytes[offset].toLong() and 0xFF) shl 24) or ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun id3v2Size(head: ByteArray): Int = parseSafely(0) {
        var size = 0
        for (index in 0 until 4) size = (size shl 7) or (head[6 + index].toInt() and 0x7F)
        size + 10
    }

    private val CONTAINER_ATOMS = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "udta", "meta", "ilst", "wave", "edts", "dinf",
    )

    /**
     * Walks ISO-BMFF atoms (32/64-bit sizes) over the available bytes; the visitor may stop
     * descent by returning false.
     */
    private fun walkAtoms(
        bytes: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        visitor: (type: String, payloadStart: Int, payloadEnd: Int, depth: Int) -> Boolean,
    ) {
        if (depth > 8) return
        var offset = start
        while (offset + 8 <= end) {
            val reader = BeReader(bytes, offset)
            var size = reader.u32()
            val type = reader.ascii(4)
            var headerSize = 8
            if (size == 1L) {
                if (!reader.has(8)) return
                size = reader.u64()
                headerSize = 16
            }
            if (size < headerSize) return
            val payloadStart = offset + headerSize
            val payloadEnd = (offset + size).coerceAtMost(end.toLong()).toInt()
            val descend = visitor(type, payloadStart, payloadEnd, depth)
            if (descend && type in CONTAINER_ATOMS) walkAtoms(bytes, payloadStart, payloadEnd, depth + 1, visitor)
            offset = (offset + size).toInt()
            if (offset <= start) return
        }
    }

    private fun ByteArray.startsWithAscii(text: String): Boolean =
        size >= text.length && String(this, 0, text.length, Charsets.US_ASCII) == text

    private fun ByteArray.containsAscii(text: String, offset: Int): Boolean =
        size >= offset + text.length && String(this, offset, text.length, Charsets.US_ASCII) == text

    private fun ByteArray.looksLikeMpegSync(): Boolean =
        size >= 2 && (this[0].toInt() and 0xFF) == 0xFF && (this[1].toInt() and 0xE0) == 0xE0
}
