package com.hifiplayer.data.audio.service

/**
 * The library tree that Android Auto, Android Automotive and any other media browser may read
 * (requirement 27).
 *
 * It is a *pure* description of the tree — ids in, queries out — deliberately separate from Media3:
 * the ids a controller sends back are strings that arrive from outside the app, so they are parsed
 * here, where they can be tested without a device, and never trusted blindly.
 *
 * The tree mirrors the library the user actually has: songs, albums, artists, genres, playlists,
 * recently added and favourites. Every node maps to a query the repository already answers for the
 * app's own screens, so the car sees exactly the same music as the phone.
 */
sealed interface BrowseNode {

    /** Stable id, sent to the controller and back. */
    val mediaId: String

    val title: String

    val isBrowsable: Boolean

    /** The root: the seven sections, nothing else. */
    data object Root : BrowseNode {
        override val mediaId: String get() = ROOT_ID
        override val title: String get() = "HiFi Player"
        override val isBrowsable: Boolean get() = true
    }

    /** One of the sections of the root (songs, albums, artists…). */
    data class Section(override val mediaId: String, override val title: String) : BrowseNode {
        override val isBrowsable: Boolean get() = true
    }

    /** A browsable collection: one album, one artist, one genre or one playlist. */
    data class Collection(
        val kind: CollectionKind,
        val id: String,
        override val title: String,
        val subtitle: String? = null,
    ) : BrowseNode {
        override val mediaId: String get() = kind.prefix + id
        override val isBrowsable: Boolean get() = true
    }

    /** A single song, playable. */
    data class Song(
        val trackId: String,
        override val title: String,
        val subtitle: String? = null,
    ) : BrowseNode {
        override val mediaId: String get() = PREFIX + trackId
        override val isBrowsable: Boolean get() = false
    }

    /** What a controller asked for. */
    sealed interface Parsed {
        /** The root itself. */
        data object Root : Parsed

        /** One of the sections, addressed by its own id. */
        data class Section(val sectionId: String) : Parsed

        /** An album, artist, genre or playlist. */
        data class Collection(val kind: CollectionKind, val id: String) : Parsed

        /** A song. */
        data class Song(val trackId: String) : Parsed

        /**
         * An id the app never issued (or one from an older version). It is a real case: the honest
         * answer to the controller is "not found", not an empty list that looks like an empty album.
         */
        data class Unknown(val mediaId: String) : Parsed
    }

    enum class CollectionKind(val prefix: String) {
        ALBUM(ALBUM_PREFIX),
        ARTIST(ARTIST_PREFIX),
        GENRE(GENRE_PREFIX),
        PLAYLIST(PLAYLIST_PREFIX),
    }

    companion object {
        const val ROOT_ID: String = "root"
        const val SONG_ID: String = "songs"
        const val ALBUM_ID: String = "albums"
        const val ARTIST_ID: String = "artists"
        const val GENRE_ID: String = "genres"
        const val PLAYLIST_ID: String = "playlists"
        const val RECENT_ID: String = "recent"
        const val FAVOURITES_ID: String = "favourites"

        const val ALBUM_PREFIX: String = "album:"
        const val ARTIST_PREFIX: String = "artist:"
        const val GENRE_PREFIX: String = "genre:"
        const val PLAYLIST_PREFIX: String = "playlist:"
        const val PREFIX: String = "song:"

        /** The sections of the root, in the order the user sees them in the car. */
        val SECTIONS: List<Section> = listOf(
            Section(RECENT_ID, "Reproducido recientemente"),
            Section(ALBUM_ID, "Álbumes"),
            Section(ARTIST_ID, "Artistas"),
            Section(SONG_ID, "Canciones"),
            Section(FAVOURITES_ID, "Favoritos"),
            Section(PLAYLIST_ID, "Listas"),
            Section(GENRE_ID, "Géneros"),
        )

        fun songId(trackId: String): String = PREFIX + trackId

        fun parse(mediaId: String?): Parsed = when {
            mediaId == null || mediaId == ROOT_ID -> Parsed.Root
            SECTIONS.any { it.mediaId == mediaId } -> Parsed.Section(mediaId)
            mediaId.startsWith(PREFIX) -> Parsed.Song(mediaId.removePrefix(PREFIX))
            else -> CollectionKind.entries
                .firstOrNull { mediaId.startsWith(it.prefix) }
                ?.let { Parsed.Collection(it, mediaId.removePrefix(it.prefix)) }
                ?: Parsed.Unknown(mediaId)
        }
    }
}
