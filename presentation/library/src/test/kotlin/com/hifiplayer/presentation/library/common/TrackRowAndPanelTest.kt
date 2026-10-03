package com.hifiplayer.presentation.library.common

import com.google.common.truth.Truth.assertThat
import com.hifiplayer.domain.model.audio.AudioFormatSpec
import com.hifiplayer.domain.model.audio.Codec
import com.hifiplayer.domain.model.library.Track
import org.junit.Test

/**
 * The two things every track list must get right: the wording of a row, and the panel/picker
 * transition. Both are product rules, not cosmetics: a row may never show a quality figure that was
 * not detected (requirement 12), and "add to a playlist" may never lead nowhere (requirement 46).
 */
class TrackRowAndPanelTest {

    private val flac = AudioFormatSpec(
        sampleRateHz = 96_000,
        bitDepth = 24,
        channels = 2,
        codec = Codec.FLAC,
        bitrateKbps = 1411,
    )

    private fun track(format: AudioFormatSpec? = flac): Track = Track(
        id = "t1",
        uri = "content://music/1.flac",
        title = "Reflektor",
        artist = "Arcade Fire",
        albumArtist = "Arcade Fire",
        album = "Reflektor",
        albumId = "a1",
        artistId = "ar1",
        durationMs = 245_000L,
        trackNumber = 1,
        discNumber = 1,
        year = 2013,
        genre = "Indie",
        sizeBytes = 43_000_000L,
        mimeType = "audio/flac",
        displayName = "01 Reflektor.flac",
        relativePath = "Reflektor/01 Reflektor.flac",
        dateAddedEpochSec = 1_700_000_000L,
        lastModifiedEpochSec = 1_700_000_000L,
        format = format,
    )

    @Test
    fun `subtitle shows artist, album and the detected format`() {
        val subtitle = buildSubtitle(track(), showTechnicalInfo = true)
        assertThat(subtitle).isEqualTo("Arcade Fire · Reflektor · FLAC · 24-bit / 96 kHz · 1411 kbps")
    }

    @Test
    fun `a lossy row prints its bitrate exactly once`() {
        val mp3 = AudioFormatSpec(
            sampleRateHz = 44_100,
            bitDepth = 16,
            channels = 2,
            codec = Codec.MP3,
            bitrateKbps = 320,
        )
        val subtitle = buildSubtitle(track(format = mp3), showTechnicalInfo = true)
        assertThat(subtitle).isEqualTo("Arcade Fire · Reflektor · MP3 · 16-bit / 44.1 kHz · 320 kbps")
        assertThat(subtitle.split("kbps").size).isEqualTo(2)
    }

    @Test
    fun `subtitle never invents a format when nothing was detected`() {
        val subtitle = buildSubtitle(track(format = null), showTechnicalInfo = true)
        assertThat(subtitle).contains("formato no detectado")
        assertThat(subtitle).doesNotContain("kbps")
    }

    @Test
    fun `technical info can be hidden without losing the musical information`() {
        val subtitle = buildSubtitle(track(), showTechnicalInfo = false)
        assertThat(subtitle).isEqualTo("Arcade Fire · Reflektor")
    }

    @Test
    fun `the action panel opens the playlist picker and closes itself`() {
        val state = TrackInteractionState()
        val subject = track()

        state.show(subject)
        assertThat(state.actions.track).isEqualTo(subject)

        state.openPickerFor(subject)
        assertThat(state.actions.track).isNull()
        assertThat(state.pickerTrack).isEqualTo(subject)

        state.dismissPicker()
        assertThat(state.pickerTrack).isNull()
    }

    @Test
    fun `dismissing the panel leaves no leftover track`() {
        val state = TrackInteractionState()
        state.show(track())
        state.actions.dismiss()
        assertThat(state.actions.track).isNull()
        assertThat(state.pickerTrack).isNull()
    }
}
