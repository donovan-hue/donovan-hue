package com.hifiplayer.domain.model.library

/** Sort direction so the UI can offer "Título ↓" without duplicating queries. */
enum class SortDirection { ASCENDING, DESCENDING }

/**
 * Declarative description of a library query. The repository translates it to SQL; the UI
 * never builds SQL nor knows about Room.
 */
data class TrackQuery(
    val sort: TrackSort = TrackSort.TITLE,
    val direction: SortDirection = SortDirection.ASCENDING,
    val searchTerm: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val genre: String? = null,
    val folderPath: String? = null,
    val favoritesOnly: Boolean = false,
    val losslessOnly: Boolean = false,
    val highResolutionOnly: Boolean = false,
    val limit: Int? = null,
    val offset: Int = 0,
) {
    companion object {
        val All: TrackQuery = TrackQuery()
    }
}
