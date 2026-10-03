package com.hifiplayer.core.metadata

import com.hifiplayer.domain.model.audio.Codec
import com.hifiplayer.domain.model.audio.ReplayGainInfo
import com.hifiplayer.domain.model.audio.ReplayGainSource
import java.nio.charset.Charset

/**
 * Metadata read from inside the file (requirement 18). A null field means "the file does not
 * carry that tag" – the UI then shows a neutral placeholder instead of inventing text.
 */
data class ParsedTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val year: Int? = null,
    val composer: String? = null,
    val copyright: String? = null,
    val comment: String? = null,
    val replayGain: ReplayGainInfo = ReplayGainInfo.EMPTY,
    val hasEmbeddedArtwork: Boolean = false,
    val embeddedArtworkMime: String? = null,
    val embeddedArtworkBytes: Long? = null,
    val embeddedArtworkWidth: Int? = null,
    val embeddedArtworkHeight: Int? = null,
    val tagFormat: String,
) {
    val isEmpty: Boolean
        get() = title == null && artist == null && album == null && genre == null &&
            !replayGain.hasAnyValue && !hasEmbeddedArtwork
}

/**
 * Parses tags straight from the file header bytes, so it works with `content://` URIs from
 * MediaStore and SAF without copying the file or asking for filesystem access.
 *
 * Supported: Vorbis comments (FLAC/Ogg), ID3v2.2/2.3/2.4 (MP3) and the iTunes-style `ilst`
 * atoms of MP4/M4A (AAC/ALAC), including ReplayGain and embedded artwork descriptors.
 */
object TagParser {

    fun parse(head: ByteArray, codec: Codec, fileSizeBytes: Long): ParsedTags? = when {
        head.startsWithAscii("fLaC") -> parseFlacComments(head)
        head.startsWithAscii("ID3") -> parseId3v2(head)
        head.containsAscii("ftyp", 4) -> parseIlsTags(head)
        else -> when (codec) {
            Codec.FLAC -> parseFlacComments(head)
            Codec.MP3 -> parseId3v2(head)
            Codec.AAC, Codec.ALAC -> parseIlsTags(head)
            else -> null
        }
    }

    // ------------------------------------------------------------------ Vorbis comments (FLAC)

    private fun parseFlacComments(head: ByteArray): ParsedTags? = parseSafely<ParsedTags?>(null) {
        val reader = BeReader(head, 4)
        var comments: List<String>? = null
        var artworkMime: String? = null
        var artworkBytes: Long? = null
        var artworkWidth: Int? = null
        var artworkHeight: Int? = null
        var isLast = false
        var guard = 0

        while (!isLast && guard++ < 128) {
            val header = reader.u8()
            isLast = (header and 0x80) != 0
            val type = header and 0x7F
            val length = reader.u24()
            when (type) {
                4 -> {
                    val block = reader.bytes(length)
                    comments = readVorbisCommentBlock(block)
                }
                6 -> {
                    val block = reader.bytes(length)
                    parsePictureBlock(block)?.let { picture ->
                        artworkMime = picture.mimeType
                        artworkBytes = picture.sizeBytes
                        artworkWidth = picture.width
                        artworkHeight = picture.height
                    }
                }
                else -> reader.skip(length)
            }
        }

        if (comments == null && artworkMime == null) return@parseSafely null

        val map = buildMap {
            comments?.forEach { entry ->
                val separator = entry.indexOf('=')
                if (separator > 0) put(entry.substring(0, separator).uppercase(), entry.substring(separator + 1))
            }
        }

        ParsedTags(
            title = map["TITLE"],
            artist = map["ARTIST"],
            album = map["ALBUM"],
            albumArtist = map["ALBUMARTIST"] ?: map["ALBUM ARTIST"],
            genre = map["GENRE"],
            trackNumber = map["TRACKNUMBER"]?.substringBefore('/')?.trim()?.toIntOrNull(),
            trackTotal = map["TRACKTOTAL"]?.toIntOrNull() ?: map["TOTALTRACKS"]?.toIntOrNull(),
            discNumber = map["DISCNUMBER"]?.substringBefore('/')?.trim()?.toIntOrNull(),
            discTotal = map["DISCTOTAL"]?.toIntOrNull() ?: map["TOTALDISCS"]?.toIntOrNull(),
            year = (map["DATE"] ?: map["YEAR"])?.take(4)?.toIntOrNull(),
            composer = map["COMPOSER"],
            copyright = map["COPYRIGHT"],
            comment = map["COMMENT"] ?: map["DESCRIPTION"],
            replayGain = ReplayGainInfo(
                trackGainDb = map["REPLAYGAIN_TRACK_GAIN"]?.toGainDb(),
                albumGainDb = map["REPLAYGAIN_ALBUM_GAIN"]?.toGainDb(),
                trackPeak = map["REPLAYGAIN_TRACK_PEAK"]?.toDoubleOrNull(),
                albumPeak = map["REPLAYGAIN_ALBUM_PEAK"]?.toDoubleOrNull(),
                trackLoudnessLufs = map["REPLAYGAIN_TRACK_RANGE"]?.toGainDb(),
                source = if (map.keys.any { it.startsWith("REPLAYGAIN_") }) ReplayGainSource.VORBIS_COMMENT else ReplayGainSource.NONE,
            ),
            hasEmbeddedArtwork = artworkMime != null || (artworkBytes ?: 0L) > 0L,
            embeddedArtworkMime = artworkMime,
            embeddedArtworkBytes = artworkBytes,
            embeddedArtworkWidth = artworkWidth,
            embeddedArtworkHeight = artworkHeight,
            tagFormat = "Vorbis comment (FLAC)",
        )
    }

    private fun readVorbisCommentBlock(block: ByteArray): List<String> = parseSafely(emptyList()) {
        val reader = LeReader(block, 0)
        val vendorLength = reader.u32().toInt()
        reader.skip(vendorLength)
        val count = reader.u32().toInt()
        val comments = ArrayList<String>(count.coerceAtMost(256))
        var index = 0
        while (index < count && reader.remaining > 4) {
            val length = reader.u32().toInt()
            if (length <= 0 || length > reader.remaining) break
            comments += String(reader.bytes(length), Charsets.UTF_8)
            index++
        }
        comments
    }

    private data class PictureBlock(val mimeType: String, val sizeBytes: Long, val width: Int, val height: Int)

    private fun parsePictureBlock(block: ByteArray): PictureBlock? = parseSafely<PictureBlock?>(null) {
        val reader = BeReader(block, 0)
        reader.skip(4) // picture type
        val mimeLength = reader.u32().toInt()
        val mime = String(reader.bytes(mimeLength), Charsets.US_ASCII)
        val descriptionLength = reader.u32().toInt()
        reader.skip(descriptionLength)
        val width = reader.u32().toInt()
        val height = reader.u32().toInt()
        reader.skip(4) // color depth
        reader.skip(4) // indexed colors
        val dataLength = reader.u32()
        PictureBlock(mimeType = mime, sizeBytes = dataLength, width = width, height = height)
    }

    // ------------------------------------------------------------------ ID3v2 (MP3)

    private fun parseId3v2(head: ByteArray): ParsedTags? = parseSafely<ParsedTags?>(null) {
        val reader = BeReader(head, 0)
        reader.skip(3) // "ID3"
        val majorVersion = reader.u8()
        val minorVersion = reader.u8()
        val flags = reader.u8()
        val tagSize = syncSafe32(head, 6)
        val tagEnd = (10 + tagSize).coerceAtMost(head.size)
        var offset = 10

        // Unsynchronisation expands bytes; we only trust the parsed region.
        val isUnsynchronised = flags and 0x80 != 0
        val hasExtendedHeader = flags and 0x40 != 0
        if (hasExtendedHeader && offset + 4 <= tagEnd) {
            val extendedSize = if (majorVersion >= 4) syncSafe32(head, offset) else beU32(head, offset).toInt() + 4
            offset += extendedSize
        }

        val frames = mutableMapOf<String, String>()
        var replayGain = ReplayGainInfo.EMPTY
        var hasArtwork = false
        var artworkMime: String? = null
        var artworkBytes: Long? = null

        while (offset + (if (majorVersion >= 3) 10 else 6) <= tagEnd) {
            if (head[offset].toInt() == 0) break // padding reached
            val frameId: String
            val frameSize: Int
            if (majorVersion >= 3) {
                frameId = String(head, offset, 4, Charsets.US_ASCII)
                frameSize = if (majorVersion >= 4) syncSafe32(head, offset + 4) else beU32(head, offset + 4).toInt()
                offset += 10
            } else {
                frameId = String(head, offset, 3, Charsets.US_ASCII)
                frameSize = ((head[offset + 3].toInt() and 0xFF) shl 16) or ((head[offset + 4].toInt() and 0xFF) shl 8) or
                    (head[offset + 5].toInt() and 0xFF)
                offset += 6
            }
            if (frameSize <= 0 || offset + frameSize > head.size) break
            val payload = head.copyOfRange(offset, offset + frameSize)
            offset += frameSize

            when {
                frameId == "TXXX" -> {
                    val (description, value) = readDescriptionValue(payload)
                    when (description.uppercase()) {
                        "REPLAYGAIN_TRACK_GAIN" -> replayGain = replayGain.copy(trackGainDb = value.toGainDb(), source = ReplayGainSource.ID3V2_TXXX)
                        "REPLAYGAIN_ALBUM_GAIN" -> replayGain = replayGain.copy(albumGainDb = value.toGainDb(), source = ReplayGainSource.ID3V2_TXXX)
                        "REPLAYGAIN_TRACK_PEAK" -> replayGain = replayGain.copy(trackPeak = value.toDoubleOrNull(), source = ReplayGainSource.ID3V2_TXXX)
                        "REPLAYGAIN_ALBUM_PEAK" -> replayGain = replayGain.copy(albumPeak = value.toDoubleOrNull(), source = ReplayGainSource.ID3V2_TXXX)
                    }
                }
                frameId == "RVA2" -> {
                    val gain = readRva2Gain(payload)
                    if (gain != null) replayGain = replayGain.copy(trackGainDb = gain, source = ReplayGainSource.ID3V2_RVA2)
                }
                frameId == "APIC" -> {
                    val picture = readAttachedPicture(payload)
                    if (picture != null) {
                        hasArtwork = true
                        artworkMime = picture.first
                        artworkBytes = picture.second
                    }
                }
                frameId.startsWith("T") -> {
                    val text = readEncodedText(payload)
                    if (text != null) frames[frameId] = text
                }
            }
        }

        if (frames.isEmpty() && !hasArtwork && !replayGain.hasAnyValue) return@parseSafely null

        ParsedTags(
            title = frames["TIT2"],
            artist = frames["TPE1"],
            album = frames["TALB"],
            albumArtist = frames["TPE2"],
            genre = frames["TCON"]?.trim('(', ')'),
            trackNumber = frames["TRCK"]?.substringBefore('/')?.toIntOrNull(),
            trackTotal = frames["TRCK"]?.substringAfter('/', "")?.toIntOrNull(),
            discNumber = frames["TPOS"]?.substringBefore('/')?.toIntOrNull(),
            discTotal = frames["TPOS"]?.substringAfter('/', "")?.toIntOrNull(),
            year = (frames["TDRC"] ?: frames["TYER"])?.take(4)?.toIntOrNull(),
            composer = frames["TCOM"],
            copyright = frames["TCOP"],
            comment = frames["COMM"],
            replayGain = replayGain,
            hasEmbeddedArtwork = hasArtwork,
            embeddedArtworkMime = artworkMime,
            embeddedArtworkBytes = artworkBytes,
            tagFormat = "ID3v2.$majorVersion.$minorVersion",
        )
    }

    private fun readEncodedText(payload: ByteArray): String? = parseSafely<String?>(null) {
        if (payload.isEmpty()) return@parseSafely null
        val encoding = payload[0].toInt()
        val body = payload.copyOfRange(1, payload.size)
        when (encoding) {
            0 -> String(body, Charsets.ISO_8859_1)
            1 -> String(body, Charsets.UTF_16)
            2 -> String(body, Charsets.UTF_16BE)
            3 -> String(body, Charsets.UTF_8)
            else -> String(body, Charsets.ISO_8859_1)
        }.trim('\u0000', ' ', '\u0001')
    }

    private fun readDescriptionValue(payload: ByteArray): Pair<String, String> = parseSafely("" to "") {
        val encoding = payload[0].toInt()
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val body = payload.copyOfRange(1, payload.size)
        val text = String(body, charset)
        val separatorIndex = text.indexOf('\u0000')
        if (separatorIndex < 0) text to "" else text.substring(0, separatorIndex) to text.substring(separatorIndex + 1).trim('\u0000')
    }

    /** RVA2: master channel gain is stored in 0.25 dB steps, referenced to 0 dB = 512. */
    private fun readRva2Gain(payload: ByteArray): Double? = parseSafely<Double?>(null) {
        val reader = BeReader(payload, 0)
        // identification is a null-terminated latin1 string
        while (reader.has(1) && reader.u8() != 0) Unit
        if (!reader.has(3)) return@parseSafely null
        reader.skip(1) // channel type
        val gainRaw = reader.u16()
        val peakRaw = reader.u16()
        val gain = (if (gainRaw >= 32768) gainRaw - 65536 else gainRaw) * 0.25
        if (peakRaw == 0) null else gain
    }

    private fun readAttachedPicture(payload: ByteArray): Pair<String, Long>? = parseSafely<Pair<String, Long>?>(null) {
        val encoding = payload[0].toInt()
        val charset = if (encoding == 1 || encoding == 2) Charsets.UTF_16 else Charsets.ISO_8859_1
        val body = payload.copyOfRange(1, payload.size)
        val mimeEnd = indexOfNull(body, charset, 0)
        if (mimeEnd < 0) return@parseSafely null
        val mime = String(body, 0, mimeEnd, Charsets.ISO_8859_1).trim()
        val description = if (mimeEnd + 3 <= body.size) computeNullTerminatedLength(body, mimeEnd + 3, charset) else 0
        val dataStart = mimeEnd + 3 + description
        val dataLength = (body.size - dataStart).toLong().coerceAtLeast(0L)
        mime to dataLength
    }

    private fun indexOfNull(bytes: ByteArray, charset: Charset, from: Int): Int {
        val terminatorSize = if (charset == Charsets.UTF_16 || charset == Charsets.UTF_16BE) 2 else 1
        var index = from
        while (index + terminatorSize <= bytes.size) {
            if (terminatorSize == 1 && bytes[index].toInt() == 0) return index
            if (terminatorSize == 2 && bytes[index].toInt() == 0 && bytes[index + 1].toInt() == 0) return index
            index += terminatorSize
        }
        return -1
    }

    private fun computeNullTerminatedLength(bytes: ByteArray, from: Int, charset: Charset): Int {
        val end = indexOfNull(bytes, charset, from)
        return if (end < 0) 0 else end - from
    }

    // ------------------------------------------------------------------ MP4/M4A ilst

    private fun parseIlsTags(head: ByteArray): ParsedTags? {
        val results = mutableMapOf<String, String>()
        var artworkBytes: Long? = null
        var artworkMime: String? = null
        var replayGain = ReplayGainInfo.EMPTY

        fun visitIlst(items: Map<String, ByteArray>) {
            for ((key, payload) in items) {
                when (key) {
                    "©nam" -> results["title"] = payload.decodeUtf8OrLatin()
                    "©ART" -> results["artist"] = payload.decodeUtf8OrLatin()
                    "aART" -> results["albumArtist"] = payload.decodeUtf8OrLatin()
                    "©alb" -> results["album"] = payload.decodeUtf8OrLatin()
                    "©gen" -> results["genre"] = payload.decodeUtf8OrLatin()
                    "©day" -> results["year"] = payload.decodeUtf8OrLatin().take(4)
                    "©wrt" -> results["composer"] = payload.decodeUtf8OrLatin()
                    "cprt" -> results["copyright"] = payload.decodeUtf8OrLatin()
                    "trkn" -> {
                        if (payload.size >= 6) {
                            results["trackNumber"] = (payload[3].toInt() and 0xFF).toString()
                            results["trackTotal"] = (payload[5].toInt() and 0xFF).toString()
                        }
                    }
                    "disk" -> {
                        if (payload.size >= 6) {
                            results["discNumber"] = (payload[3].toInt() and 0xFF).toString()
                            results["discTotal"] = (payload[5].toInt() and 0xFF).toString()
                        }
                    }
                    "covr" -> {
                        artworkBytes = payload.size.toLong()
                        artworkMime = when (payload.firstOrNull()?.toInt()) {
                            13 -> "image/jpeg"
                            14 -> "image/png"
                            else -> null
                        }
                    }
                    else -> if (key.startsWith("----")) Unit
                }
            }
        }

        walkAtoms(head, 0, head.size, 0) { type, payloadStart, payloadEnd, _ ->
            if (type == "ilst") {
                visitIlst(readListItemPayloads(head, payloadStart, payloadEnd))
                false
            } else {
                true
            }
        }

        if (results.isEmpty() && artworkBytes == null && !replayGain.hasAnyValue) return null

        return ParsedTags(
            title = results["title"],
            artist = results["artist"],
            album = results["album"],
            albumArtist = results["albumArtist"],
            genre = results["genre"],
            trackNumber = results["trackNumber"]?.toIntOrNull(),
            trackTotal = results["trackTotal"]?.toIntOrNull(),
            discNumber = results["discNumber"]?.toIntOrNull(),
            discTotal = results["discTotal"]?.toIntOrNull(),
            year = results["year"]?.toIntOrNull(),
            composer = results["composer"],
            copyright = results["copyright"],
            replayGain = replayGain,
            hasEmbeddedArtwork = artworkBytes != null,
            embeddedArtworkMime = artworkMime,
            embeddedArtworkBytes = artworkBytes,
            tagFormat = "MP4 ilst (iTunes)",
        )
    }

    /** Reads the `data` atom inside each ilst item and returns its raw payload. */
    private fun readListItemPayloads(head: ByteArray, start: Int, end: Int): Map<String, ByteArray> {
        val items = LinkedHashMap<String, ByteArray>()
        var offset = start
        while (offset + 8 <= end) {
            val size = beU32(head, offset).toInt()
            if (size < 8 || offset + size > end) break
            val key = String(head, offset + 4, 4, Charsets.UTF_8)
            // Child atoms: "data" (8-byte header + 8 bytes of type/locale) or "mean"/"name" pairs.
            var childOffset = offset + 8
            val itemEnd = offset + size
            while (childOffset + 8 <= itemEnd) {
                val childSize = beU32(head, childOffset).toInt()
                if (childSize < 8 || childOffset + childSize > itemEnd) break
                val childType = String(head, childOffset + 4, 4, Charsets.US_ASCII)
                if (childType == "data" && childSize > 16) {
                    val typeFlags = beU32(head, childOffset + 8)
                    val dataType = (typeFlags and 0x00FFFFFFL).toInt()
                    val payloadStart = childOffset + 16
                    val payload = head.copyOfRange(payloadStart, childOffset + childSize)
                    // Images are reported as type 13 (JPEG) / 14 (PNG); text keeps the payload as-is.
                    items[key] = if (dataType == 13 || dataType == 14) byteArrayOf(dataType.toByte()) + payload else payload
                    break
                }
                childOffset += childSize
            }
            offset += size
        }
        return items
    }

    private fun ByteArray.decodeUtf8OrLatin(): String = parseSafely("") {
        if (isEmpty()) return@parseSafely ""
        val first = this[0].toInt() and 0xFF
        val body = if (first == 13 || first == 14) copyOfRange(1, size) else this
        val text = String(body, Charsets.UTF_8)
        text.replace("\u0000", "").trim()
    }

    // ------------------------------------------------------------------ shared atom walking

    private val CONTAINER_ATOMS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "meta", "ilst")

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
            var size = beU32(bytes, offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            var headerSize = 8
            if (size == 1L) {
                if (offset + 16 > end) return
                size = beU64(bytes, offset + 8)
                headerSize = 16
            }
            if (size < headerSize) return
            val payloadStart = offset + headerSize
            val payloadEnd = (offset + size).coerceAtMost(end.toLong()).toInt()
            var descend = visitor(type, payloadStart, payloadEnd, depth)
            // "meta" carries a 4-byte version/flags header before its children.
            if (descend && type == "meta" && payloadStart + 4 < payloadEnd) {
                walkAtoms(bytes, payloadStart + 4, payloadEnd, depth + 1, visitor)
                descend = false
            }
            if (descend && type in CONTAINER_ATOMS) walkAtoms(bytes, payloadStart, payloadEnd, depth + 1, visitor)
            offset = (offset + size).toInt()
            if (offset <= start) return
        }
    }

    private fun beU32(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0L
        return ((bytes[offset].toLong() and 0xFF) shl 24) or ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun beU64(bytes: ByteArray, offset: Int): Long {
        val high = beU32(bytes, offset)
        val low = beU32(bytes, offset + 4)
        return (high shl 32) or low
    }

    private fun syncSafe32(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (index in 0 until 4) value = (value shl 7) or (bytes[offset + index].toInt() and 0x7F)
        return value
    }

    private fun ByteArray.startsWithAscii(text: String): Boolean =
        size >= text.length && String(this, 0, text.length, Charsets.US_ASCII) == text

    private fun ByteArray.containsAscii(text: String, offset: Int): Boolean =
        size >= offset + text.length && String(this, offset, text.length, Charsets.US_ASCII) == text

    /** ReplayGain values arrive as "-7.42 dB"; strip the unit before parsing. */
    private fun String.toGainDb(): Double? = trim()
        .removeSuffix("dB")
        .trim()
        .replace(',', '.')
        .toDoubleOrNull()
}
