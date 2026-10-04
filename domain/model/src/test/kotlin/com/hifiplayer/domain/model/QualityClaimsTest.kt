package com.hifiplayer.domain.model

import com.google.common.truth.Truth.assertThat
import com.hifiplayer.domain.model.audio.AudioFormatSpec
import com.hifiplayer.domain.model.audio.Codec
import com.hifiplayer.domain.model.audio.CrossfeedMode
import com.hifiplayer.domain.model.audio.ReplayGainMode
import com.hifiplayer.domain.model.device.BitPerfectBlocker
import com.hifiplayer.domain.model.device.BitPerfectState
import com.hifiplayer.domain.model.device.BitPerfectSupport
import com.hifiplayer.domain.model.settings.EqPreset
import com.hifiplayer.domain.model.settings.EqSettings
import org.junit.Test

/**
 * What the app is allowed to say about quality (requirements 6, 12, 15 and 30).
 *
 * Every string and every flag here is shown to the user as a statement of fact, so the rules are
 * tested as statements: bit-perfect is only announced when it is really active, a format label is
 * built from measured values, and the clipping warning appears when there is a reason for it and not
 * before.
 */
class QualityClaimsTest {

    private val flac24_96 = AudioFormatSpec(sampleRateHz = 96_000, bitDepth = 24, channels = 2, codec = Codec.FLAC)
    private val mp3_44_16 = AudioFormatSpec(
        sampleRateHz = 44_100,
        bitDepth = 16,
        channels = 2,
        codec = Codec.MP3,
        bitrateKbps = 320,
    )

    @Test
    fun `bit perfect is only announced when it is really active`() {
        assertThat(BitPerfectState(requested = true, isActive = true).bannerTitle).isEqualTo("BIT-PERFECT")
        assertThat(BitPerfectState(requested = true, isActive = false).bannerTitle)
            .isEqualTo("BIT-PERFECT NO DISPONIBLE")
        assertThat(BitPerfectState(requested = false, isActive = false).bannerTitle)
            .isEqualTo("DSP DESACTIVADO (bit-perfect apagado)")
    }

    @Test
    fun `asking for bit perfect with effects on does not pretend to be bit perfect`() {
        val state = BitPerfectState(requested = true, isActive = false, dspActive = true)

        assertThat(state.bannerTitle).isEqualTo("DSP ACTIVE")
        assertThat(state.isActive).isFalse()
    }

    @Test
    fun `the banner details what was delivered, with the route that delivered it`() {
        val state = BitPerfectState(
            requested = true,
            isActive = true,
            deliveredFormat = flac24_96,
            routeName = "USB DAC",
        )

        assertThat(state.bannerDetail).contains("24-bit / 96 kHz")
        assertThat(state.bannerDetail).contains("FLAC")
        assertThat(state.bannerDetail).contains("USB DAC")
    }

    @Test
    fun `when bit perfect is blocked the reason is explained, not hidden`() {
        val state = BitPerfectState(
            requested = true,
            isActive = false,
            support = BitPerfectSupport.API_NOT_SUPPORTED,
            blockers = listOf(BitPerfectBlocker.ANDROID_TOO_OLD),
        )

        assertThat(state.blockers).isNotEmpty()
        assertThat(BitPerfectBlocker.ANDROID_TOO_OLD.explanation).isNotEmpty()
        assertThat(state.bannerDetail).isNotEmpty()
    }

    @Test
    fun `a format label is built from the measured values only`() {
        assertThat(flac24_96.label).isEqualTo("24-bit / 96 kHz")
        // A lossy file shows its bitrate; an unknown one shows nothing extra instead of a zero.
        assertThat(mp3_44_16.label).isEqualTo("16-bit / 44.1 kHz · 320 kbps")
        assertThat(flac24_96.label).doesNotContain("kbps")
        assertThat(flac24_96.channelLayoutName).isEqualTo("Estéreo")
        assertThat(flac24_96.isLossless).isTrue()
        assertThat(mp3_44_16.isLossless).isFalse()
    }

    @Test
    fun `hi-res means above CD quality and nothing else`() {
        assertThat(flac24_96.isHighResolution).isTrue()
        assertThat(mp3_44_16.isHighResolution).isFalse()
        assertThat(AudioFormatSpec(48_000, 16, 2, Codec.WAV).isHighResolution).isFalse()
        assertThat(AudioFormatSpec(44_100, 24, 2, Codec.FLAC).isHighResolution).isTrue()
    }

    @Test
    fun `the clipping warning appears exactly when the chain can clip`() {
        val flat = EqSettings.DEFAULT
        assertThat(flat.clippingRisk).isFalse()

        val boosted = flat.copy(bands = EqPreset.byId(EqPreset.BASS_BOOST_ID).bands)
        assertThat(boosted.hasAnyGain).isTrue()
        assertThat(boosted.clippingRisk).isTrue()

        // A preamp that pulls the whole chain down removes the risk.
        val compensated = boosted.copy(preampDb = -6.0)
        assertThat(compensated.clippingRisk).isFalse()
    }

    @Test
    fun `an equaliser that is on but flat is not advertised as doing something`() {
        val enabledButFlat = EqSettings.DEFAULT.copy(enabled = true)

        assertThat(enabledButFlat.isEffectivelyFlat).isTrue()
        assertThat(enabledButFlat.hasAnyGain).isFalse()
    }

    @Test
    fun `the equaliser has the ten bands the specification asks for`() {
        assertThat(EqSettings.DEFAULT.bands).hasSize(10)
        assertThat(EqSettings.DEFAULT.bands.map { it.frequencyHz }).isInOrder()
    }

    @Test
    fun `replay gain and crossfeed say when they are off`() {
        assertThat(ReplayGainMode.OFF.isEnabled).isFalse()
        assertThat(ReplayGainMode.TRACK.isEnabled).isTrue()
        assertThat(CrossfeedMode.OFF.mixAmount).isEqualTo(0.0)
        assertThat(CrossfeedMode.HIGH.mixAmount).isGreaterThan(CrossfeedMode.LOW.mixAmount)
        assertThat(CrossfeedMode.MEDIUM.explanation).isNotEmpty()
    }
}
