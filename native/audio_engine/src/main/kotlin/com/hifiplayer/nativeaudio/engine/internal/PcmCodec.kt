package com.hifiplayer.nativeaudio.engine.internal

import java.nio.ByteBuffer

/**
 * PCM <-> float conversion used by the DSP bridge.
 *
 * 16-bit integers map to +/-1.0 exactly (a 16-bit value is representable in float32 without loss),
 * and the inverse rounds to nearest, so a passthrough through float cannot alter the samples by
 * more than the representation itself allows.
 */
internal object PcmCodec {

    fun decode(buffer: ByteBuffer, destination: FloatArray, samples: Int, floatInput: Boolean) {
        if (floatInput) {
            val floats = buffer.asFloatBuffer()
            floats.get(destination, 0, minOf(samples, floats.remaining()))
            skipBytes(buffer, destination, samples, floats.remaining())
            return
        }
        val shorts = buffer.asShortBuffer()
        val available = minOf(samples, shorts.remaining())
        for (index in 0 until available) {
            destination[index] = shorts.get(index) / 32_768f
        }
        skipBytes(buffer, destination, samples, available)
    }

    fun encode(source: FloatArray, samples: Int, output: ByteBuffer, floatOutput: Boolean) {
        if (floatOutput) {
            val floats = output.asFloatBuffer()
            for (index in 0 until samples) {
                floats.put(index, source[index])
            }
            output.position(output.position() + samples * 4)
            return
        }
        val shorts = output.asShortBuffer()
        for (index in 0 until samples) {
            val clamped = source[index].coerceIn(-1f, 1f)
            // Round to nearest; 32767f keeps +1.0 from wrapping to -32768.
            val value = Math.round(clamped * 32_767f).toInt().coerceIn(-32_768, 32_767)
            shorts.put(index, value.toShort())
        }
        output.position(output.position() + samples * 2)
    }

    /** When the buffer is shorter than expected, the rest is silence: never reuse stale samples. */
    private fun skipBytes(buffer: ByteBuffer, destination: FloatArray, samples: Int, written: Int) {
        for (index in written until samples) destination[index] = 0f
        buffer.position(buffer.limit())
    }
}
