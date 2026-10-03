package com.hifiplayer.dsp.filter

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class BiquadTest {

    private val sampleRate = 48_000

    @Test
    fun `peaking filter has the requested gain at its centre frequency`() {
        val coefficients = BiquadDesigner.peaking(frequencyHz = 1_000.0, gainDb = 6.0, q = 1.0, sampleRateHz = sampleRate)

        val atCentre = coefficients.magnitudeDb(1_000.0, sampleRate)
        val farBelow = coefficients.magnitudeDb(50.0, sampleRate)
        val farAbove = coefficients.magnitudeDb(15_000.0, sampleRate)

        assertThat(atCentre).isWithin(0.15).of(6.0)
        assertThat(abs(farBelow)).isLessThan(0.5)
        assertThat(abs(farAbove)).isLessThan(0.5)
    }

    @Test
    fun `zero gain produces a bypass coefficient set`() {
        val coefficients = BiquadDesigner.peaking(1_000.0, 0.0, 1.0, sampleRate)
        assertThat(coefficients.isBypass).isTrue()
    }

    @Test
    fun `low shelf boosts low frequencies and leaves high frequencies untouched`() {
        val coefficients = BiquadDesigner.lowShelf(100.0, 4.0, 0.707, sampleRate)
        assertThat(coefficients.magnitudeDb(40.0, sampleRate)).isWithin(0.6).of(4.0)
        assertThat(abs(coefficients.magnitudeDb(10_000.0, sampleRate))).isLessThan(0.6)
    }

    @Test
    fun `high shelf boosts high frequencies and leaves low frequencies untouched`() {
        val coefficients = BiquadDesigner.highShelf(8_000.0, 3.0, 0.707, sampleRate)
        assertThat(coefficients.magnitudeDb(16_000.0, sampleRate)).isWithin(0.6).of(3.0)
        assertThat(abs(coefficients.magnitudeDb(100.0, sampleRate))).isLessThan(0.6)
    }

    @Test
    fun `low pass attenuates above its corner`() {
        val coefficients = BiquadDesigner.lowPass(1_000.0, 0.707, sampleRate)
        assertThat(coefficients.magnitudeDb(100.0, sampleRate)).isWithin(0.5).of(0.0)
        assertThat(coefficients.magnitudeDb(8_000.0, sampleRate)).isLessThan(-20.0)
    }

    @Test
    fun `filter output of a steady sine gains the expected level`() {
        val coefficients = BiquadDesigner.peaking(1_000.0, 6.0, 1.0, sampleRate)
        val filter = Biquad(coefficients)
        val amplitude = 0.5
        var peak = 0.0
        val totalSamples = sampleRate // 1 second
        for (index in 0 until totalSamples) {
            val input = amplitude * kotlin.math.sin(2.0 * Math.PI * 1_000.0 * index / sampleRate)
            val output = filter.process(input)
            if (index > sampleRate / 4) peak = maxOf(peak, abs(output))
        }
        // +6 dB ≈ x1.995, measured with tolerance for the settling time.
        assertThat(peak).isWithin(0.05).of(amplitude * 1.995)
    }
}
