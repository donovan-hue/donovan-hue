package com.hifiplayer.presentation.playback

import com.google.common.truth.Truth.assertThat
import com.hifiplayer.domain.model.audio.Codec
import com.hifiplayer.domain.model.audio.AudioFormatSpec
import com.hifiplayer.domain.model.audio.CrossfeedMode
import com.hifiplayer.domain.model.audio.ReplayGainMode
import com.hifiplayer.domain.model.device.BitPerfectState
import com.hifiplayer.domain.model.playback.AudioOutputInfo
import com.hifiplayer.domain.model.settings.AppSettings
import org.junit.Test

/**
 * The signal path is the one thing the app must never embellish (requirements 6, 12 and 30): every
 * string here is built from what the engine measured plus which stages are really on. These tests
 * pin that down, because a wrong label is indistinguishable from a lie to the user.
 */
class AudioPathPresenterTest {

    private val flac24_96 = AudioFormatSpec(
        sampleRateHz = 96_000,
        bitDepth = 24,
        channels = 2,
        codec = Codec.FLAC,
    )

    private val defaultSettings = AppSettings.DEFAULT

    @Test
    fun `bit perfect active says the short path and not a single extra stage`() {
        val info = AudioOutputInfo(
            sourceFormat = flac24_96,
            decodedFormat = flac24_96,
            outputFormat = flac24_96,
            bitPerfect = BitPerfectState(requested = true, isActive = true),
        )

        // Even with every effect switched on in the settings, an active bit-perfect path is drawn
        // short, because that is what the engine is doing.
        val settingsWithEverythingOn = AppSettings.DEFAULT.copy(
            replayGain = AppSettings.DEFAULT.replayGain.copy(mode = ReplayGainMode.ALBUM),
            eq = AppSettings.DEFAULT.eq.copy(enabled = true),
            dsp = AppSettings.DEFAULT.dsp.copy(crossfeed = CrossfeedMode.HIGH),
        )
        val path = buildAudioPath(info, settingsWithEverythingOn)

        assertThat(path.dspChainDescription).isEqualTo("Source → Decode → Output (sin procesamiento)")
        assertThat(path.bitPerfect.isActive).isTrue()
        assertThat(path.sourceFormatLabel).isEqualTo("24-bit / 96 kHz")
        assertThat(path.sourceIsLossless).isTrue()
    }

    @Test
    fun `effects appear in the chain only when they are really enabled`() {
        val info = AudioOutputInfo(
            sourceFormat = flac24_96,
            decodedFormat = flac24_96,
            outputFormat = flac24_96,
            eqActive = true,
        )

        assertThat(describeChain(info, defaultSettings)).isEqualTo("Source → Decode → Output")

        val withEq = defaultSettings.copy(
            eq = defaultSettings.eq.copy(enabled = true, bands = defaultSettings.eq.bands.map { it.copy(gainDb = 3.0) }),
        )
        assertThat(describeChain(info, withEq)).isEqualTo("Source → Decode → EQ → Output")

        val withReplayGainAndCrossfeed = withEq.copy(
            replayGain = withEq.replayGain.copy(mode = ReplayGainMode.TRACK),
            dsp = withEq.dsp.copy(crossfeed = CrossfeedMode.MEDIUM),
        )
        assertThat(describeChain(info, withReplayGainAndCrossfeed))
            .isEqualTo("Source → Decode → ReplayGain → EQ → Crossfeed → Output")
    }

    @Test
    fun `a flat equaliser enabled by the user is not announced as an active stage`() {
        // Enabled switch on but every band at 0 dB: nothing is being applied, so the chain must not
        // claim otherwise.
        val settings = defaultSettings.copy(eq = defaultSettings.eq.copy(enabled = true))
        val info = AudioOutputInfo(sourceFormat = flac24_96, outputFormat = flac24_96, eqActive = false)

        assertThat(describeChain(info, settings)).isEqualTo("Source → Decode → Output")
    }

    @Test
    fun `app gain and balance show up as the gain stage`() {
        val settings = defaultSettings.copy(
            dsp = defaultSettings.dsp.copy(appGainDb = 2.0, preampDb = -3.0, balance = 0.2),
        )
        val info = AudioOutputInfo(sourceFormat = flac24_96, outputFormat = flac24_96)

        assertThat(describeChain(info, settings)).isEqualTo("Source → Decode → Gain → Output")
    }

    @Test
    fun `replay gain label carries both the mode and the measured decibels`() {
        val info = AudioOutputInfo(replayGainMode = "TRACK", replayGainDb = -6.19)

        assertThat(replayGainLabel(info)).isEqualTo("TRACK · -6.19 dB")
    }

    @Test
    fun `replay gain with no mode says nothing instead of zero decibels`() {
        // The engine reports 0.0 dB when the stage is off; printing "+0.00 dB" would suggest a gain
        // that was never read from the file.
        assertThat(replayGainLabel(AudioOutputInfo(replayGainMode = null, replayGainDb = 0.0))).isNull()
    }

    @Test
    fun `a track with no format yet does not invent one`() {
        val path = buildAudioPath(AudioOutputInfo.UNKNOWN, defaultSettings)

        assertThat(path.sourceFormatLabel).isNull()
        assertThat(path.outputFormatLabel).isNull()
        assertThat(path.appliedGainLabel).isNull()
        assertThat(path.replayGainLabel).isNull()
        assertThat(path.sourceIsLossless).isNull()
    }

    @Test
    fun `only a real gain is printed`() {
        val info = AudioOutputInfo(appliedGainDb = -1.5)
        val path = buildAudioPath(info, defaultSettings)

        assertThat(path.appliedGainLabel).isEqualTo("-1.50 dB")
        assertThat(buildAudioPath(AudioOutputInfo(appliedGainDb = 0.0), defaultSettings).appliedGainLabel)
            .isNull()
    }

    @Test
    fun `converted output is reported as converted`() {
        val info = AudioOutputInfo(
            sourceFormat = flac24_96,
            outputFormat = AudioFormatSpec(48_000, 16, 2, Codec.WAV),
            resampled = true,
        )

        assertThat(buildAudioPath(info, defaultSettings).outputStatusLabel).isEqualTo("Converted")
    }
}
