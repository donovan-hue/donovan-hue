package com.hifiplayer.presentation.navigation

import android.net.Uri
import androidx.navigation.NavBackStackEntry

/**
 * What kind of collection the detail screen is showing.
 *
 * The four kinds share one screen because they share the same problem — a title, a list of tracks and
 * the same actions — and the differences are data, not behaviour.
 */
enum class CollectionKind(val key: String) {
    ALBUM("album"),
    ARTIST("artist"),
    GENRE("genre"),
    FOLDER("folder");

    companion object {
        fun fromKey(key: String): CollectionKind = entries.firstOrNull { it.key == key } ?: ALBUM
    }
}

/**
 * Arguments of the collection screen.
 *
 * The title and the subtitle travel in the route because they are already known where the user tapped
 * (the album row, the genre row) — resolving them again would mean a query and a flash of empty text.
 */
data class CollectionArgs(
    val kind: CollectionKind,
    val id: String,
    val title: String,
    val subtitle: String? = null,
)

object CollectionRoute {

    /**
     * Builds the concrete route for [args], encoding the text so a title with '/' or '?' is safe.
     *
     * The encoder is a parameter with the platform's implementation as the default: that is the seam
     * that lets a plain JVM test check the structure of the route without pulling Robolectric into
     * this module just for one string.
     */
    fun route(args: CollectionArgs, encode: (String) -> String = { Uri.encode(it) }): String = buildString {
        append("collection/").append(args.kind.key).append('/').append(encode(args.id))
        append("?title=").append(encode(args.title))
        append("&subtitle=").append(encode(args.subtitle.orEmpty()))
    }

    fun parse(entry: NavBackStackEntry): CollectionArgs = CollectionArgs(
        kind = CollectionKind.fromKey(entry.arguments?.getString(HiFiDestination.Collection.KIND).orEmpty()),
        id = entry.arguments?.getString(HiFiDestination.Collection.ID).orEmpty(),
        title = entry.arguments?.getString(HiFiDestination.Collection.TITLE).orEmpty(),
        subtitle = entry.arguments?.getString(HiFiDestination.Collection.SUBTITLE)?.takeIf { it.isNotBlank() },
    )
}
