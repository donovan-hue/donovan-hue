package com.hifiplayer.dsp.crossfeed

import com.google.common.truth.Truth.assertThat
import com.hifiplayer.domain.model.audio.CrossfeedMode
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class CrossfeedProcessorTest {

    @Test
    fun `off mode is a hard bypass`() {
        val processor = CrossfeedProcessor()
        processor.configure(CrossfeedMode.OFF, sampleRateHz = 48_000, channels = 2)
        val buffer = floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f)
        val processed = processor.process(buffer, frames = 2, channels = 2)
        assertThat(processed).isFalse()
        assertThat(buffer[0]).isEqualTo(0.5f)
        assertThat(buffer[1]).isEqualTo(-0.5f)
        assertThat(buffer[2]).isEqualTo(0.5f)
        assertThat(buffer[3]).isEqualTo(-0.5f)
    }

    @Test
    fun `left-only signal leaks into the right channel when enabled`() {
        val processor = CrossfeedProcessor()
        processor.configure(CrossfeedMode.MEDIUM, sampleRateHz = 48_000, channels = 2)

        val frames = 4_800
        val buffer = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            buffer[frame * 2] = (0.5 * sin(2.0 * Math.PI * 500.0 * frame / 48_000.0)).toFloat()
            buffer[frame * 2 + 1] = 0f
        }
        processor.process(buffer, frames, channels = 2)

        var rightEnergy = 0.0
        var leftEnergy = 0.0
        for (frame in frames / 2 until frames) {
            leftEnergy += abs(buffer[frame * 2])
            rightEnergy += abs(buffer[frame * 2 + 1])
        }
        assertThat(rightEnergy).isGreaterThan(0.0)
        assertThat(rightEnergy).isLessThan(leftEnergy)
        assertThat(processor.isEnabled).isTrue()
        assertThat(processor.description).contains("Crossfeed MEDIUM")
    }
}
