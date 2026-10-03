package com.hifiplayer.domain.repository

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.library.SearchResults

/**
 * Requirement 24: global search over songs, artists, albums, genres and folders.
 * Callers debounce (~300 ms, see AppConfig.SEARCH_DEBOUNCE_MS); this interface stays pure.
 */
interface SearchRepository {

    suspend fun search(query: String, limitPerType: Int = 10): Outcome<SearchResults>

    suspend fun searchTracks(query: String, limit: Int = 50): Outcome<List<com.hifiplayer.domain.model.library.Track>>
}
