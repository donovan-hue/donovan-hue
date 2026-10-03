package com.hifiplayer.presentation.navigation

import com.google.common.truth.Truth.assertThat
import java.net.URLDecoder
import java.net.URLEncoder
import org.junit.Test

/**
 * The collection route carries the title and the subtitle of what the user tapped.
 *
 * Real album and folder names contain slashes, question marks and accents. A route that silently
 * breaks on those would send the user to an empty screen with the wrong title, so the encoding is
 * verified here instead of being trusted.
 */
class CollectionRouteTest {

    /** The platform encoder and the standard one agree on everything except the space character. */
    private val encode: (String) -> String = { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    @Test
    fun `an album route encodes kind, id, title and subtitle`() {
        val route = CollectionRoute.route(
            encode = encode,
            args = CollectionArgs(
                kind = CollectionKind.ALBUM,
                id = "album/2024",
                title = "¿Quién dijo miedo?",
                subtitle = "Artista · 2024 · 12 pistas",
            ),
        )

        assertThat(route).startsWith("collection/album/")
        val (path, query) = route.substringAfter("collection/album/").split("?", limit = 2)
        assertThat(decode(path)).isEqualTo("album/2024")

        val params = query.split("&").associate { it.substringBefore("=") to decode(it.substringAfter("=")) }
        assertThat(params["title"]).isEqualTo("¿Quién dijo miedo?")
        assertThat(params["subtitle"]).isEqualTo("Artista · 2024 · 12 pistas")
    }

    @Test
    fun `every kind has its own key and is recognised back`() {
        CollectionKind.entries.forEach { kind ->
            assertThat(CollectionKind.fromKey(kind.key)).isEqualTo(kind)
        }
    }

    @Test
    fun `an unknown kind falls back to album instead of crashing`() {
        assertThat(CollectionKind.fromKey("no-existe")).isEqualTo(CollectionKind.ALBUM)
    }

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
}
