package com.hifiplayer.domain.usecase.favorites

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.repository.FavoriteRepository
import kotlinx.coroutines.flow.Flow

/** Requirement 26. */
class ToggleFavoriteUseCase(private val favorites: FavoriteRepository) {
    suspend operator fun invoke(trackId: String): Outcome<Boolean> = favorites.toggle(trackId)
}

class GetFavoriteTracksUseCase(private val favorites: FavoriteRepository) {
    operator fun invoke(): Flow<List<Track>> = favorites.favoriteTracks()
}

class ObserveIsFavoriteUseCase(private val favorites: FavoriteRepository) {
    operator fun invoke(trackId: String): Flow<Boolean> = favorites.isFavorite(trackId)
}

class ObserveFavoriteIdsUseCase(private val favorites: FavoriteRepository) {
    operator fun invoke(): Flow<Set<String>> = favorites.favoriteTrackIds()
}
