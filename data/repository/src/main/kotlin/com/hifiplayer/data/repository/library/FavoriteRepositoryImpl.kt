package com.hifiplayer.data.repository.library

import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.outcomeOf
import com.hifiplayer.core.common.time.TimeProvider
import com.hifiplayer.core.database.Mappers
import com.hifiplayer.core.database.entity.FavoriteEntity
import com.hifiplayer.data.repository.local.DatabaseProvisioning
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.repository.FavoriteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Requirement 26: favorites stored in Room keyed by track id, so a track can never be duplicated.
 * `INSERT OR IGNORE` in the DAO makes "add twice" a no-op instead of an error.
 */
class FavoriteRepositoryImpl(
    private val provisioning: DatabaseProvisioning,
    private val dispatchers: DispatcherProvider,
    private val timeProvider: TimeProvider = TimeProvider.System,
) : FavoriteRepository {

    private val favoriteDao = provisioning.favorites
    private val trackDao = provisioning.tracks

    override fun favoriteTrackIds(): Flow<Set<String>> =
        favoriteDao.observeIds().map { it.toSet() }.flowOn(dispatchers.io)

    override fun favoriteTracks(): Flow<List<Track>> = trackDao.observeFavorites()
        .map { entities -> entities.map { Mappers.run { it.toDomain() } } }
        .flowOn(dispatchers.io)

    override fun isFavorite(trackId: String): Flow<Boolean> = favoriteDao.observeIsFavorite(trackId).flowOn(dispatchers.io)

    override suspend fun add(trackId: String): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            favoriteDao.insert(FavoriteEntity(track_id = trackId, added_at_epoch_ms = timeProvider.nowMs()))
        }
    }

    override suspend fun remove(trackId: String): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) { favoriteDao.delete(trackId) }
    }

    override suspend fun toggle(trackId: String): Outcome<Boolean> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            if (favoriteDao.contains(trackId)) {
                favoriteDao.delete(trackId)
                false
            } else {
                favoriteDao.insert(FavoriteEntity(track_id = trackId, added_at_epoch_ms = timeProvider.nowMs()))
                true
            }
        }
    }

    private companion object {
        const val TAG = "FavoriteRepository"
    }
}
