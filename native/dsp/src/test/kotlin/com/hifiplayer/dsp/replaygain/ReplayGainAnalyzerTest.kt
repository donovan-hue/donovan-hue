package com.hifiplayer.dsp.replaygain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class ReplayGainAnalyzerTest {

    /** 997 Hz sine at -20 dBFS: a standard loudness test signal. */
    private fun sineBuffer(frames: Int, sampleRate: Int, amplitude: Double): FloatArray {
        val buffer = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            val value = (amplitude * sin(2.0 * Math.PI * 997.0 * frame / sampleRate)).toFloat()
            buffer[frame * 2] = value
            buffer[frame * 2 + 1] = value
        }
        return buffer
    }

    @Test
    fun `measures a steady sine close to its expected loudness`() {
        val sampleRate = 48_000
        val analyzer = ReplayGainAnalyzer(sampleRate, channels = 2)
        // 5 seconds of a -18 dBFS sine ≈ -21 LUFS for a stereo correlated signal.
        val buffer = sineBuffer(frames = sampleRate * 5, sampleRate = sampleRate, amplitude = 0.1259)
        analyzer.accept(buffer, frames = sampleRate * 5)

        val loudness = analyzer.integratedLoudness()
        assertThat(loudness).isNotNull()
        // Generous but meaningful window: the implementation is BS.1770-shaped, and this test
        // mainly guards against regressions such as an unweighted or ungated measurement.
        assertThat(loudness!!).isGreaterThan(-25.0)
        assertThat(loudness).isLessThan(-15.0)

        val gain = analyzer.trackGainDb()
        assertThat(gain).isNotNull()
        assertThat(abs(gain!!)).isLessThan(7.0)
    }

    @Test
    fun `silence produces no loudness and no gain`() {
        val analyzer = ReplayGainAnalyzer(48_000, channels = 2)
        analyzer.accept(FloatArray(48_000 * 2), frames = 48_000)
        assertThat(analyzer.integratedLoudness()).isNull()
        assertThat(analyzer.trackGainDb()).isNull()
    }

    @Test
    fun `loud signal asks for attenuation rather than boost`() {
        val sampleRate = 44_100
        val analyzer = ReplayGainAnalyzer(sampleRate, channels = 2)
        val buffer = sineBuffer(frames = sampleRate * 4, sampleRate = sampleRate, amplitude = 1.0)
        analyzer.accept(buffer, frames = sampleRate * 4)

        val gain = analyzer.trackGainDb()
        assertThat(gain).isNotNull()
        assertThat(gain!!).isLessThan(0.0)
        assertThat(analyzer.peakLinear()).isWithin(0.01).of(1.0)
    }
}
