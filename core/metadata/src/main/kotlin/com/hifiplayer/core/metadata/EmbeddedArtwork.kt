package com.hifiplayer.core.metadata

import android.util.Base64
import com.hifiplayer.domain.model.audio.Codec

/**
 * Extracts cover art embedded inside the file, straight from the header bytes (requirement 19:
 * "artwork incrustada primero").
 *
 * The tag parser only records *that* artwork exists and how big it is; this class is the one that
 * returns the actual image bytes, and it understands where each format keeps them:
 *
 *  - FLAC  → `PICTURE` metadata block (type 6).
 *  - Ogg/Vorbis → `METADATA_BLOCK_PICTURE` inside the Vorbis comment (base64 of the same structure).
 *  - MP3   → ID3v2 `APIC` frame (v2.2 calls it `PIC`).
 *  - MP4/M4A → `covr` atom inside `ilst`.
 *
 * Returns null when there is no artwork. Never throws: a malformed picture must not break scanning.
 */
object EmbeddedArtwork {

    data class ExtractedArtwork(
        val bytes: ByteArray,
        val mimeType: String?,
        val declaredWidth: Int?,
        val declaredHeight: Int?,
        val origin: String,
    ) {
        val sizeBytes: Long get() = bytes.size.toLong()

        // data class with a ByteArray: equals/hashCode would compare references, so they are
        // overridden explicitly to keep the class predictable.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ExtractedArtwork) return false
            return bytes.contentEquals(other.bytes) && origin == other.origin
        }

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + origin.hashCode()
    }

    fun extract(head: ByteArray, codec: Codec): ExtractedArtwork? {
        if (head.size < 16) return null
        return when (codec) {
            Codec.FLAC -> extractFlacPicture(head)
            Codec.MP3 -> extractId3Picture(head)
            Codec.AAC, Codec.ALAC -> extractMp4Cover(head)
            Codec.VORBIS, Codec.OPUS -> extractVorbisPictureBlock(head)
            else -> extractId3Picture(head) ?: extractMp4Cover(head) ?: extractFlacPicture(head)
        }
    }

    // ---------------------------------------------------------------- FLAC

    private fun extractFlacPicture(head: ByteArray): ExtractedArtwork? {
        if (head.size < 8) return null
        if (head[0] != 'f'.code.toByte() || head[1] != 'L'.code.toByte() || head[2] != 'a'.code.toByte() || head[3] != 'C'.code.toByte()) return null
        var offset = 4
        var picture: ExtractedArtwork? = null
        // Walk metadata blocks looking for type 6; stop at the last one (bit 7 of the header).
        while (offset + 4 <= head.size) {
            val header = head[offset].toInt() and 0xFF
            val type = header and 0x7F
            val isLast = (header and 0x80) != 0
            val length = ((head[offset + 1].toInt() and 0xFF) shl 16) or
                ((head[offset + 2].toInt() and 0xFF) shl 8) or
                (head[offset + 3].toInt() and 0xFF)
            val bodyStart = offset + 4
            if (bodyStart + length > head.size) break
            if (type == 6) {
                val decoded = parsePictureBlock(head, bodyStart, bodyStart + length, "FLAC PICTURE")
                // Preferred cover (type 3) wins; otherwise keep the first one found.
                if (picture == null || decoded?.pictureType == PICTURE_TYPE_FRONT_COVER) picture = decoded?.artwork ?: picture
            }
            if (isLast) break
            offset = bodyStart + length
        }
        return picture
    }

    // ---------------------------------------------------------------- Vorbis (Ogg)

    private fun extractVorbisPictureBlock(head: ByteArray): ExtractedArtwork? {
        // The comment block of Ogg files is not byte-aligned in the head buffer, so it is located
        // by searching for the field name; if found, the base64 payload is decoded and parsed with
        // the same structure as a FLAC PICTURE block.
        val needle = "METADATA_BLOCK_PICTURE=".toByteArray(Charsets.US_ASCII)
        val index = indexOf(head, needle)
        if (index < 0) return null
        val start = index + needle.size
        var end = start
        while (end < head.size && head[end] != 0.toByte() && head[end] != '\n'.code.toByte()) end++
        if (end <= start) return null
        val encoded = String(head, start, end - start, Charsets.US_ASCII).trim()
        val decoded = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (exception: IllegalArgumentException) {
            return null
        }
        val parsed = parsePictureBlock(decoded, 0, decoded.size, "Vorbis METADATA_BLOCK_PICTURE")
        return parsed?.artwork
    }

    // ---------------------------------------------------------------- ID3v2 (MP3)

    private fun extractId3Picture(head: ByteArray): ExtractedArtwork? {
        if (head.size < 20) return null
        if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return null
        val major = head[3].toInt() and 0xFF
        val tagSize = syncSafeSize(head, 6) ?: return null
        val tagEnd = minOf(head.size, 10 + tagSize)
        var offset = 10
        var best: ExtractedArtwork? = null

        if (major == 2) {
            while (offset + 6 <= tagEnd) {
                val frameId = ascii(head, offset, 3) ?: break
                if (frameId[0] == '\u0000') break
                val size = ((head[offset + 3].toInt() and 0xFF) shl 16) or
                    ((head[offset + 4].toInt() and 0xFF) shl 8) or (head[offset + 5].toInt() and 0xFF)
                val dataStart = offset + 6
                if (size <= 0 || dataStart + size > tagEnd) break
                if (frameId == "PIC") {
                    val parsed = parseId3Picture(head, dataStart, dataStart + size, v22 = true)
                    if (best == null || parsed?.pictureType == PICTURE_TYPE_FRONT_COVER) best = parsed?.artwork ?: best
                }
                offset = dataStart + size
            }
            return best
        }

        val longFrames = major >= 4
        while (offset + 10 <= tagEnd) {
            val frameId = ascii(head, offset, 4) ?: break
            if (frameId[0] == '\u0000') break
            val size = if (longFrames) {
                syncSafeSize(head, offset + 4) ?: break
            } else {
                ((head[offset + 4].toInt() and 0xFF) shl 24) or ((head[offset + 5].toInt() and 0xFF) shl 16) or
                    ((head[offset + 6].toInt() and 0xFF) shl 8) or (head[offset + 7].toInt() and 0xFF)
            }
            val dataStart = offset + 10
            if (size <= 0 || dataStart + size > tagEnd) break
            if (frameId == "APIC") {
                val parsed = parseId3Picture(head, dataStart, dataStart + size, v22 = false)
                if (best == null || parsed?.pictureType == PICTURE_TYPE_FRONT_COVER) best = parsed?.artwork ?: best
            }
            offset = dataStart + size
        }
        return best
    }

    private data class TaggedArtwork(val artwork: ExtractedArtwork, val pictureType: Int)

    private fun parseId3Picture(bytes: ByteArray, start: Int, end: Int, v22: Boolean): TaggedArtwork? {
        if (start + 4 >= end) return null
        val encoding = bytes[start].toInt() and 0xFF
        var cursor = start + 1
        val mime: String?
        if (v22) {
            val imageFormat = String(bytes, cursor, 3, Charsets.ISO_8859_1)
            mime = when (imageFormat.uppercase()) {
                "JPG" -> "image/jpeg"
                "PNG" -> "image/png"
                else -> null
            }
            cursor += 3
        } else {
            val mimeEnd = indexOfZero(bytes, cursor, end)
            if (mimeEnd < 0) return null
            mime = String(bytes, cursor, mimeEnd - cursor, Charsets.ISO_8859_1).trim().ifBlank { null }
            cursor = mimeEnd + 1
        }
        if (cursor >= end) return null
        val pictureType = bytes[cursor].toInt() and 0xFF
        cursor += 1
        // Description: terminated per text encoding (2 bytes for UTF-16).
        val wide = encoding == 1 || encoding == 2
        cursor = skipTerminated(bytes, cursor, end, wide)
        if (cursor >= end) return null
        val imageBytes = bytes.copyOfRange(cursor, end)
        if (imageBytes.size < 64) return null
        return TaggedArtwork(
            artwork = ExtractedArtwork(
                bytes = imageBytes,
                mimeType = mime ?: sniffMime(imageBytes),
                declaredWidth = null,
                declaredHeight = null,
                origin = if (v22) "ID3v2.2 PIC" else "ID3v2 APIC",
            ),
            pictureType = pictureType,
        )
    }

    // ---------------------------------------------------------------- MP4 / M4A

    private fun extractMp4Cover(head: ByteArray): ExtractedArtwork? {
        val covr = findAtom(head, "covr", 0, head.size) ?: return null
        // Inside covr: one or more "data" atoms with 8 bytes of type/flags before the payload.
        val data = findAtom(head, "data", covr.first, covr.second) ?: return null
        val payloadStart = data.first + 8
        if (payloadStart >= data.second) return null
        val imageBytes = head.copyOfRange(payloadStart, data.second)
        if (imageBytes.size < 64) return null
        return ExtractedArtwork(
            bytes = imageBytes,
            mimeType = sniffMime(imageBytes),
            declaredWidth = null,
            declaredHeight = null,
            origin = "MP4 covr",
        )
    }

    private fun findAtom(bytes: ByteArray, type: String, from: Int, to: Int): Pair<Int, Int>? {
        var offset = from
        while (offset + 8 <= to) {
            var size: Long = readU32(bytes, offset)
            val atomType = String(bytes, offset + 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8
            if (size == 1L) {
                // 64-bit size: the real length lives in the next 8 bytes.
                if (offset + 16 > to) return null
                size = readU64(bytes, offset + 8)
                headerSize = 16
            } else if (size == 0L) {
                // "Until the end of the file": in a head buffer that means until the buffer end.
                size = (to - offset).toLong()
            }
            val atomEnd = offset.toLong() + size
            if (size < headerSize.toLong() || atomEnd > to.toLong()) return null
            val payloadStart = offset + headerSize
            val payloadEnd = atomEnd.toInt()
            if (atomType == type) return payloadStart to payloadEnd
            // Containers are walked recursively so nested ilst/moov boxes are found.
            if (atomType in CONTAINER_ATOMS) {
                val nested = findAtom(bytes, type, payloadStart, payloadEnd)
                if (nested != null) return nested
            }
            offset = payloadEnd
        }
        return null
    }

    // ---------------------------------------------------------------- shared FLAC picture structure

    private fun parsePictureBlock(bytes: ByteArray, start: Int, end: Int, origin: String): TaggedArtwork? {
        var cursor = start
        fun u32(): Long? {
            if (cursor + 4 > end) return null
            val value = ((bytes[cursor].toLong() and 0xFF) shl 24) or ((bytes[cursor + 1].toLong() and 0xFF) shl 16) or
                ((bytes[cursor + 2].toLong() and 0xFF) shl 8) or (bytes[cursor + 3].toLong() and 0xFF)
            cursor += 4
            return value
        }

        val pictureType = u32()?.toInt() ?: return null
        val mimeLength = u32()?.toInt() ?: return null
        if (cursor + mimeLength > end) return null
        val mime = String(bytes, cursor, mimeLength, Charsets.ISO_8859_1).trim().ifBlank { null }
        cursor += mimeLength
        val descriptionLength = u32()?.toInt() ?: return null
        if (cursor + descriptionLength > end) return null
        cursor += descriptionLength
        val width = u32()?.toInt() ?: return null
        val height = u32()?.toInt() ?: return null
        u32() // colour depth: not needed for display, kept for completeness of the structure
        u32() // number of colours
        val dataLength = u32()?.toInt() ?: return null
        if (dataLength <= 0 || cursor + dataLength > end) return null
        val imageBytes = bytes.copyOfRange(cursor, cursor + dataLength)
        if (imageBytes.size < 64) return null
        return TaggedArtwork(
            artwork = ExtractedArtwork(
                bytes = imageBytes,
                mimeType = mime ?: sniffMime(imageBytes),
                declaredWidth = width.takeIf { it > 0 },
                declaredHeight = height.takeIf { it > 0 },
                origin = origin,
            ),
            pictureType = pictureType,
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun sniffMime(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" -> "image/webp"
        else -> null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (index in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[index + offset] != needle[offset]) continue@outer
            }
            return index
        }
        return -1
    }

    private fun indexOfZero(bytes: ByteArray, from: Int, to: Int): Int {
        for (index in from until to) if (bytes[index] == 0.toByte()) return index
        return -1
    }

    private fun skipTerminated(bytes: ByteArray, from: Int, to: Int, wide: Boolean): Int {
        var index = from
        if (wide) {
            while (index + 1 < to) {
                if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) return index + 2
                index += 2
            }
            return to
        }
        while (index < to) {
            if (bytes[index] == 0.toByte()) return index + 1
            index++
        }
        return to
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String? =
        if (offset + length <= bytes.size) String(bytes, offset, length, Charsets.ISO_8859_1) else null

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or (bytes[offset + 3].toLong() and 0xFF)

    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        return value
    }

    /** ID3 sizes are "sync safe": 7 bits per byte. Returns null when a byte has the high bit set. */
    private fun syncSafeSize(bytes: ByteArray, offset: Int): Int? {
        if (offset + 4 > bytes.size) return null
        var value = 0
        for (index in 0 until 4) {
            val current = bytes[offset + index].toInt() and 0xFF
            if (current and 0x80 != 0) return null
            value = (value shl 7) or current
        }
        return value
    }

    private const val PICTURE_TYPE_FRONT_COVER = 3

    private val CONTAINER_ATOMS = setOf("moov", "udta", "meta", "ilst", "trak", "mdia")
}
