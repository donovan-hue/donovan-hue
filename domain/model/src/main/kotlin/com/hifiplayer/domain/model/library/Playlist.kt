package com.hifiplayer.domain.model.library

/** User playlist. Persisted in Room; never stored as a file. */
data class Playlist(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val trackCount: Int = 0,
    val totalDurationMs: Long = 0L,
    val artworkTrackUri: String? = null,
) {
    val displayName: String get() = name.ifBlank { "Playlist sin nombre" }
}

/** Membership row: the same track can appear twice in a playlist at different positions. */
data class PlaylistTrack(
    val playlistId: String,
    val trackId: String,
    val position: Int,
    val addedAtEpochMs: Long,
)

/** Favorites are a set of track ids (requirement 26: never duplicate tracks). */
data class FavoriteEntry(
    val trackId: String,
    val addedAtEpochMs: Long,
)

/** How playback history rows are recorded (feeds "Reproducido recientemente"). */
data class PlayHistoryEntry(
    val trackId: String,
    val playedAtEpochMs: Long,
    val completedFraction: Float = 0f,
)

/** A single search hit, tagged with what matched so results can be grouped. */
data class SearchHit(
    val type: SearchHitType,
    val id: String,
    val title: String,
    val subtitle: String?,
    val artworkUri: String?,
    val trackUri: String? = null,
)

enum class SearchHitType(val displayName: String) {
    TRACK("Pistas"),
    ALBUM("Álbumes"),
    ARTIST("Artistas"),
    GENRE("Géneros"),
    FOLDER("Carpetas"),
    PLAYLIST("Playlists"),
}

data class SearchResults(
    val query: String,
    val hits: List<SearchHit>,
    val tookMs: Long,
) {
    val isEmpty: Boolean get() = hits.isEmpty()

    fun byType(type: SearchHitType): List<SearchHit> = hits.filter { it.type == type }
}
