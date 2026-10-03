package com.hifiplayer.core.metadata

/** Big-endian reader over a byte array, with bounds checks that never throw mid-parse. */
internal class BeReader(private val bytes: ByteArray, private var position: Int = 0) {

    val offset: Int get() = position
    val remaining: Int get() = bytes.size - position

    fun has(count: Int): Boolean = remaining >= count

    fun seek(to: Int) { position = to.coerceIn(0, bytes.size) }

    fun skip(count: Int) { position = (position + count).coerceIn(0, bytes.size) }

    fun u8(): Int = if (has(1)) bytes[position++].toInt() and 0xFF else throw ParseTruncatedException()

    fun u16(): Int = (u8() shl 8) or u8()

    fun u24(): Int = (u8() shl 16) or (u8() shl 8) or u8()

    fun u32(): Long = (u8().toLong() shl 24) or (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong()

    fun u64(): Long = (u32() shl 32) or u32()

    fun ascii(length: Int): String {
        if (!has(length)) throw ParseTruncatedException()
        val text = String(bytes, position, length, Charsets.US_ASCII)
        position += length
        return text
    }

    fun bytes(length: Int): ByteArray {
        if (!has(length)) throw ParseTruncatedException()
        val copy = bytes.copyOfRange(position, position + length)
        position += length
        return copy
    }
}

/** Little-endian reader (RIFF/WAV and Ogg fields are little-endian). */
internal class LeReader(private val bytes: ByteArray, private var position: Int = 0) {

    val offset: Int get() = position
    val remaining: Int get() = bytes.size - position

    fun has(count: Int): Boolean = remaining >= count
    fun seek(to: Int) { position = to.coerceIn(0, bytes.size) }
    fun skip(count: Int) { position = (position + count).coerceIn(0, bytes.size) }
    fun u8(): Int = if (has(1)) bytes[position++].toInt() and 0xFF else throw ParseTruncatedException()
    fun u16(): Int = u8() or (u8() shl 8)
    fun u24(): Int = u8() or (u8() shl 8) or (u8() shl 16)
    fun u32(): Long = u8().toLong() or (u8().toLong() shl 8) or (u8().toLong() shl 16) or (u8().toLong() shl 24)
    fun u64(): Long = u32() or (u32() shl 32)

    fun ascii(length: Int): String {
        if (!has(length)) throw ParseTruncatedException()
        val text = String(bytes, position, length, Charsets.US_ASCII)
        position += length
        return text
    }

    fun bytes(length: Int): ByteArray {
        if (!has(length)) throw ParseTruncatedException()
        val copy = bytes.copyOfRange(position, position + length)
        position += length
        return copy
    }
}

/** MSB-first bit reader used by FLAC's STREAMINFO block. */
internal class BitReader(private val bytes: ByteArray, private var bitPosition: Int = 0) {
    fun readBits(count: Int): Long {
        var value = 0L
        repeat(count) {
            val byteIndex = bitPosition ushr 3
            if (byteIndex >= bytes.size) throw ParseTruncatedException()
            val bitIndex = 7 - (bitPosition and 7)
            val bit = (bytes[byteIndex].toInt() ushr bitIndex) and 1
            value = (value shl 1) or bit.toLong()
            bitPosition++
        }
        return value
    }
}

internal class ParseTruncatedException : RuntimeException("cabecera incompleta")

internal inline fun <T> parseSafely(fallback: T, block: () -> T): T = try {
    block()
} catch (truncated: ParseTruncatedException) {
    fallback
} catch (index: IndexOutOfBoundsException) {
    fallback
}
