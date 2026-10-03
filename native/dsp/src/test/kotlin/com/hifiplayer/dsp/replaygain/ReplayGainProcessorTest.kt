package com.hifiplayer.dsp.replaygain

import com.google.common.truth.Truth.assertThat
import com.hifiplayer.domain.model.audio.ReplayGainInfo
import com.hifiplayer.domain.model.audio.ReplayGainMode
import com.hifiplayer.domain.model.audio.ReplayGainSource
import org.junit.Test

class ReplayGainProcessorTest {

    private val processor = ReplayGainProcessor()

    private val info = ReplayGainInfo(
        trackGainDb = -3.5,
        albumGainDb = -2.0,
        trackPeak = 0.9,
        albumPeak = 1.0,
        source = ReplayGainSource.VORBIS_COMMENT,
    )

    @Test
    fun `disabled mode applies no gain and says so`() {
        val decision = processor.decide(info, ReplayGainMode.OFF, preampDb = 3.0, preventClipping = true, fallbackGainDb = 0.0)
        assertThat(decision.appliedDb).isEqualTo(0.0)
        assertThat(decision.explanation).contains("tal como está")
    }

    @Test
    fun `track mode applies the track gain plus preamp`() {
        val decision = processor.decide(info, ReplayGainMode.TRACK, preampDb = 1.5, preventClipping = false, fallbackGainDb = 0.0)
        assertThat(decision.appliedDb).isWithin(0.001).of(-2.0)
        assertThat(decision.wasReduced).isFalse()
    }

    @Test
    fun `album mode applies the album gain`() {
        val decision = processor.decide(info, ReplayGainMode.ALBUM, preampDb = 0.0, preventClipping = false, fallbackGainDb = 0.0)
        assertThat(decision.appliedDb).isWithin(0.001).of(-2.0)
    }

    @Test
    fun `clipping prevention reduces a boost that would exceed full scale`() {
        val loudInfo = ReplayGainInfo(trackGainDb = 6.0, trackPeak = 0.95, source = ReplayGainSource.ID3V2_TXXX)
        val decision = processor.decide(loudInfo, ReplayGainMode.TRACK, preampDb = 0.0, preventClipping = true, fallbackGainDb = 0.0)

        assertThat(decision.wasReduced).isTrue()
        assertThat(decision.appliedDb).isLessThan(decision.requestedDb)
        // 0.95 peak allows about +0.44 dB before hitting full scale minus headroom.
        assertThat(decision.appliedDb).isAtMost(0.5)
        assertThat(decision.explanation).contains("evitar clipping")
    }

    @Test
    fun `missing tags fall back to the configured default gain`() {
        val decision = processor.decide(
            ReplayGainInfo.EMPTY,
            ReplayGainMode.TRACK,
            preampDb = 0.0,
            preventClipping = true,
            fallbackGainDb = -6.0,
        )
        assertThat(decision.usedFallback).isTrue()
        assertThat(decision.appliedDb).isWithin(0.001).of(-6.0)
    }

    @Test
    fun `gain is clamped to sane limits`() {
        val extreme = ReplayGainInfo(trackGainDb = 40.0, source = ReplayGainSource.VORBIS_COMMENT)
        val decision = processor.decide(extreme, ReplayGainMode.TRACK, preampDb = 0.0, preventClipping = false, fallbackGainDb = 0.0)
        assertThat(decision.appliedDb).isEqualTo(ReplayGainProcessor.MAX_BOOST_DB)
    }
}
