package com.hifiplayer.dsp.pipeline

import com.google.common.truth.Truth.assertThat
import com.hifiplayer.domain.model.audio.CrossfeedMode
import com.hifiplayer.domain.model.audio.ReplayGainInfo
import com.hifiplayer.domain.model.audio.ReplayGainMode
import com.hifiplayer.domain.model.audio.ReplayGainSource
import com.hifiplayer.domain.model.settings.AppSettings
import com.hifiplayer.domain.model.settings.EqPreset
import com.hifiplayer.domain.model.settings.EqSettings
import org.junit.Test

class AudioProcessingPipelineTest {

    private fun dspSettings(eq: EqSettings = EqSettings.DEFAULT, crossfeed: CrossfeedMode = CrossfeedMode.OFF) =
        AppSettings(eq = eq, dsp = com.hifiplayer.domain.model.settings.DspSettings(crossfeed = crossfeed))

    @Test
    fun `bit-perfect bypasses every stage and leaves samples untouched`() {
        val pipeline = AudioProcessingPipeline()
        val boostedEq = EqSettings(
            enabled = true,
            bands = EqPreset.FLAT_BANDS.map { it.copy(gainDb = 6.0) },
        )
        pipeline.configure(
            settings = dspSettings(boostedEq, CrossfeedMode.HIGH).copy(
                replayGain = com.hifiplayer.domain.model.settings.ReplayGainSettings(mode = ReplayGainMode.TRACK),
            ),
            replayGainInfo = ReplayGainInfo(trackGainDb = 5.0, source = ReplayGainSource.VORBIS_COMMENT),
            sampleRateHz = 48_000,
            channels = 2,
            bitPerfectRequested = true,
        )

        val original = FloatArray(400) { 0.3f }
        val buffer = original.copyOf()
        val result = pipeline.process(buffer, frames = 200, channels = 2)

        assertThat(result.processed).isFalse()
        assertThat(buffer.size).isEqualTo(original.size)
        buffer.indices.forEach { index -> assertThat(buffer[index]).isEqualTo(original[index]) }
        assertThat(pipeline.snapshot.bitPerfect).isTrue()
        assertThat(pipeline.snapshot.chainDescription).isEqualTo("Source → Decode → Output")
        assertThat(pipeline.anyStageActive).isFalse()
    }

    @Test
    fun `dsp active reports the real chain with each enabled stage`() {
        val pipeline = AudioProcessingPipeline()
        val eq = EqSettings(enabled = true, bands = EqPreset.FLAT_BANDS.map { it.copy(gainDb = 3.0) })
        pipeline.configure(
            settings = dspSettings(eq, CrossfeedMode.LOW).copy(
                replayGain = com.hifiplayer.domain.model.settings.ReplayGainSettings(mode = ReplayGainMode.ALBUM),
            ),
            replayGainInfo = ReplayGainInfo(albumGainDb = -2.0, source = ReplayGainSource.VORBIS_COMMENT),
            sampleRateHz = 96_000,
            channels = 2,
            bitPerfectRequested = false,
        )

        val chain = pipeline.snapshot.chainDescription

        assertThat(chain).contains("ReplayGain")
        assertThat(chain).contains("EQ(10b)")
        assertThat(chain).contains("Crossfeed")
        assertThat(chain).endsWith("Output")
        assertThat(pipeline.anyStageActive).isTrue()
    }

    @Test
    fun `meters report peaks and clipping prevention`() {
        val pipeline = AudioProcessingPipeline()
        pipeline.configure(
            settings = AppSettings().copy(
                dsp = com.hifiplayer.domain.model.settings.DspSettings(appGainDb = 9.0, clippingProtectionEnabled = true),
            ),
            replayGainInfo = ReplayGainInfo.EMPTY,
            sampleRateHz = 48_000,
            channels = 2,
            bitPerfectRequested = false,
        )
        // One second of audio: long enough for the 40 ms gain ramp to settle.
        val frames = 48_000
        pipeline.process(FloatArray(frames * 2) { 0.8f }, frames = frames, channels = 2)
        val meters = pipeline.meters()

        assertThat(meters.peak.toDouble()).isGreaterThan(0.7)
        assertThat(meters.clippingPrevented).isTrue()
        assertThat(meters.peakDb).isLessThan(2.0)
        // Every sample above full scale was hard limited instead of being sent out clipped.
        assertThat(meters.clippedSamples).isGreaterThan(0L)
    }

    @Test
    fun `eq response is exposed from the running filters`() {
        val pipeline = AudioProcessingPipeline()
        val eq = EqSettings(
            enabled = true,
            bands = EqPreset.FLAT_BANDS.map { if (it.frequencyHz == 1_000.0) it.copy(gainDb = 6.0) else it },
        )
        pipeline.configure(
            settings = dspSettings(eq),
            replayGainInfo = ReplayGainInfo.EMPTY,
            sampleRateHz = 48_000,
            channels = 2,
            bitPerfectRequested = false,
        )

        val response = pipeline.eqResponseDb(listOf(60.0, 1_000.0, 12_000.0))
        assertThat(response[1]).isWithin(0.6).of(6.0)
        assertThat(kotlin.math.abs(response[0])).isLessThan(0.6)
    }
}
