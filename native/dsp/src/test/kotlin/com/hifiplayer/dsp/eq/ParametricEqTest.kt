package com.hifiplayer.dsp.eq

import com.google.common.truth.Truth.assertThat
import com.hifiplayer.domain.model.settings.EqBand
import com.hifiplayer.domain.model.settings.EqBandType
import com.hifiplayer.domain.model.settings.EqPreset
import com.hifiplayer.domain.model.settings.EqSettings
import org.junit.Test
import kotlin.math.abs

class ParametricEqTest {

    @Test
    fun `flat preset ships with at least ten bands covering the audible range`() {
        val bands = EqPreset.FLAT_BANDS
        assertThat(bands.size).isAtLeast(10)
        assertThat(bands.first().frequencyHz).isAtMost(60.0)
        assertThat(bands.last().frequencyHz).isAtLeast(10_000.0)
        // Frequency-ordered, so the UI can render a monotonic curve.
        assertThat(bands.map { it.frequencyHz }).isInStrictOrder()
    }

    @Test
    fun `bypassed eq leaves the buffer untouched`() {
        val eq = ParametricEq()
        eq.configure(EqSettings.DEFAULT, sampleRateHz = 48_000, channels = 2)
        val buffer = FloatArray(256) { if (it % 2 == 0) 0.25f else -0.25f }

        val touched = eq.process(buffer.copyOf().also { }, frames = 128, channels = 2)

        assertThat(touched).isFalse()
    }

    @Test
    fun `enabled eq with a boost increases energy at the boosted frequency`() {
        val eq = ParametricEq()
        val bands = EqPreset.FLAT_BANDS.map { band ->
            if (band.frequencyHz == 1_000.0) band.copy(gainDb = 6.0) else band
        }
        eq.configure(EqSettings(enabled = true, bands = bands), sampleRateHz = 48_000, channels = 2)

        val frames = 48_000
        val buffer = FloatArray(frames * 2) { index ->
            val sample = 0.4 * kotlin.math.sin(2.0 * Math.PI * 1_000.0 * (index / 2) / 48_000.0)
            sample.toFloat()
        }
        val processed = eq.process(buffer, frames, channels = 2)

        assertThat(processed).isTrue()
        var peak = 0f
        for (index in frames / 2 until frames) {
            peak = maxOf(peak, abs(buffer[index * 2]))
        }
        // +6 dB ≈ x1.995 over the 0.4 input amplitude.
        assertThat(peak.toDouble()).isWithin(0.06).of(0.4 * 1.995)
    }

    @Test
    fun `magnitude response matches the configured band gains`() {
        val eq = ParametricEq()
        val bands = EqPreset.FLAT_BANDS.map { band ->
            when (band.frequencyHz) {
                120.0 -> band.copy(gainDb = 4.0)
                else -> band
            }
        }
        eq.configure(EqSettings(enabled = true, bands = bands), sampleRateHz = 48_000, channels = 2)

        val response = eq.magnitudeDb(listOf(120.0, 1_000.0, 10_000.0))

        assertThat(response[0]).isWithin(0.6).of(4.0)
        assertThat(abs(response[1])).isLessThan(0.6)
        assertThat(abs(response[2])).isLessThan(0.6)
    }

    @Test
    fun `high pass band type is supported and cuts below its frequency`() {
        val eq = ParametricEq()
        val bands = listOf(
            EqBand("hp", EqBandType.HIGH_PASS, 80.0, 0.0, 0.707),
        ) + EqPreset.FLAT_BANDS.drop(1)
        eq.configure(EqSettings(enabled = true, bands = bands), sampleRateHz = 48_000, channels = 2)

        val response = eq.magnitudeDb(listOf(20.0, 1_000.0))

        assertThat(response[0]).isLessThan(-6.0)
        assertThat(abs(response[1])).isLessThan(1.0)
    }
}
