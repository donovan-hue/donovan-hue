package com.hifiplayer.dsp.gain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GainStageTest {

    @Test
    fun `applies requested gain within a tolerance`() {
        val stage = GainStage()
        stage.configure(gainDb = 6.0, balance = 0.0, sampleRateHz = 48_000, clippingProtection = true)
        stage.snapToTarget()

        val buffer = FloatArray(64) { 0.1f }
        stage.process(buffer, frames = 32, channels = 2)

        assertThat(buffer[0].toDouble()).isWithin(0.01).of(0.1 * 1.995)
    }

    @Test
    fun `clipping protection hard limits samples above full scale`() {
        val stage = GainStage()
        stage.configure(gainDb = 12.0, balance = 0.0, sampleRateHz = 48_000, clippingProtection = true)
        stage.snapToTarget()

        val buffer = FloatArray(32) { 0.9f }
        val clipped = stage.process(buffer, frames = 16, channels = 2)

        assertThat(clipped).isTrue()
        assertThat(stage.clippingPrevented).isTrue()
        assertThat(buffer.max().toDouble()).isAtMost(1.0)
        assertThat(stage.clippedSampleCount).isAtLeast(1L)
    }

    @Test
    fun `headroom cap keeps the chain below full scale`() {
        val stage = GainStage()
        stage.configure(gainDb = 12.0, balance = 0.0, sampleRateHz = 48_000, clippingProtection = true, gainLinearHeadroom = 0.5)
        stage.snapToTarget()

        val buffer = FloatArray(8) { 0.5f }
        stage.process(buffer, frames = 4, channels = 2)

        assertThat(buffer.max().toDouble()).isAtMost(0.26)
    }

    @Test
    fun `negative balance moves the image to the left channel`() {
        val stage = GainStage()
        stage.configure(gainDb = 0.0, balance = -1.0, sampleRateHz = 48_000, clippingProtection = true)
        stage.snapToTarget()

        val buffer = floatArrayOf(1f, 1f, 1f, 1f)
        stage.process(buffer, frames = 2, channels = 2)

        // balance = -1 means "todo a la izquierda": the right channel is attenuated.
        assertThat(buffer[0].toDouble()).isWithin(0.001).of(1.0)
        assertThat(buffer[1].toDouble()).isWithin(0.001).of(0.0)
    }

    @Test
    fun `positive balance moves the image to the right channel`() {
        val stage = GainStage()
        stage.configure(gainDb = 0.0, balance = 1.0, sampleRateHz = 48_000, clippingProtection = true)
        stage.snapToTarget()

        val buffer = floatArrayOf(1f, 1f, 1f, 1f)
        stage.process(buffer, frames = 2, channels = 2)

        assertThat(buffer[0].toDouble()).isWithin(0.001).of(0.0)
        assertThat(buffer[1].toDouble()).isWithin(0.001).of(1.0)
    }
}
