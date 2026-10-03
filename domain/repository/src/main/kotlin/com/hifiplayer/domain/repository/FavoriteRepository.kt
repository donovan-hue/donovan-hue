package com.hifiplayer.domain.repository

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.library.Track
import kotlinx.coroutines.flow.Flow

/** Requirement 26: favorites keyed by track id – no duplicates, ever. */
interface FavoriteRepository {

    fun favoriteTrackIds(): Flow<Set<String>>

    fun favoriteTracks(): Flow<List<Track>>

    fun isFavorite(trackId: String): Flow<Boolean>

    suspend fun add(trackId: String): Outcome<Unit>

    suspend fun remove(trackId: String): Outcome<Unit>

    suspend fun toggle(trackId: String): Outcome<Boolean>
}
