package com.hifiplayer.data.audio.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The ids that leave the app and come back (requirement 27).
 *
 * A media browser in a car sends back whatever it was given, and a hostile or outdated controller
 * can send back anything at all. These tests fix two behaviours: every id the app issues is parsed
 * back to the same thing, and an id the app never issued is reported as unknown instead of silently
 * becoming an empty list.
 */
class BrowseNodeTest {

    @Test
    fun `the root and every section survive the round trip`() {
        assertThat(BrowseNode.parse(null)).isEqualTo(BrowseNode.Parsed.Root)
        assertThat(BrowseNode.parse(BrowseNode.ROOT_ID)).isEqualTo(BrowseNode.Parsed.Root)

        BrowseNode.SECTIONS.forEach { section ->
            assertThat(BrowseNode.parse(section.mediaId)).isEqualTo(BrowseNode.Parsed.Section(section.mediaId))
        }
    }

    @Test
    fun `collections keep their kind and their id`() {
        assertThat(BrowseNode.parse("album:alb_42"))
            .isEqualTo(BrowseNode.Parsed.Collection(BrowseNode.CollectionKind.ALBUM, "alb_42"))
        assertThat(BrowseNode.parse("artist:art_1"))
            .isEqualTo(BrowseNode.Parsed.Collection(BrowseNode.CollectionKind.ARTIST, "art_1"))
        assertThat(BrowseNode.parse("genre:Jazz"))
            .isEqualTo(BrowseNode.Parsed.Collection(BrowseNode.CollectionKind.GENRE, "Jazz"))
        assertThat(BrowseNode.parse("playlist:pl_7"))
            .isEqualTo(BrowseNode.Parsed.Collection(BrowseNode.CollectionKind.PLAYLIST, "pl_7"))
    }

    @Test
    fun `a song id gives back the track id, colons and all`() {
        assertThat(BrowseNode.parse(BrowseNode.songId("track:1"))).isEqualTo(BrowseNode.Parsed.Song("track:1"))
    }

    @Test
    fun `an id the app never issued is unknown, not an empty album`() {
        assertThat(BrowseNode.parse("album:"))
            .isEqualTo(BrowseNode.Parsed.Collection(BrowseNode.CollectionKind.ALBUM, ""))
        assertThat(BrowseNode.parse("wat:42")).isEqualTo(BrowseNode.Parsed.Unknown("wat:42"))
        assertThat(BrowseNode.parse("")).isEqualTo(BrowseNode.Parsed.Unknown(""))
    }

    @Test
    fun `an item describes itself with its id, its title and whether it can be opened`() {
        val album = BrowseNode.Collection(BrowseNode.CollectionKind.ALBUM, "alb_42", "Kind of Blue", "Miles Davis")
        val song = BrowseNode.Song("t1", "So What")

        assertThat(album.mediaId).isEqualTo("album:alb_42")
        assertThat(album.isBrowsable).isTrue()
        assertThat(song.mediaId).isEqualTo("song:t1")
        assertThat(song.isBrowsable).isFalse()
    }

    @Test
    fun `paging never invents items and never loses the tail`() {
        val items = (1..25).toList()

        assertThat(page(items, page = 0, pageSize = 10)).containsExactlyElementsIn(1..10).inOrder()
        assertThat(page(items, page = 2, pageSize = 10)).containsExactlyElementsIn(21..25).inOrder()
        // A page past the end is empty, not a crash.
        assertThat(page(items, page = 9, pageSize = 10)).isEmpty()
        // A controller that asks for everything gets everything.
        assertThat(page(items, page = 0, pageSize = Int.MAX_VALUE)).hasSize(25)
    }
}
