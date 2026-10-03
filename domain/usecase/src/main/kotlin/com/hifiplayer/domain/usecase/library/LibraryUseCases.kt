package com.hifiplayer.domain.usecase.library

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.library.Album
import com.hifiplayer.domain.model.library.Artist
import com.hifiplayer.domain.model.library.Genre
import com.hifiplayer.domain.model.library.LibraryFolder
import com.hifiplayer.domain.model.library.LibraryStats
import com.hifiplayer.domain.model.library.SearchResults
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.library.TrackQuery
import com.hifiplayer.domain.repository.MetadataRepository
import com.hifiplayer.domain.repository.MusicRepository
import com.hifiplayer.domain.repository.SearchRepository
import kotlinx.coroutines.flow.Flow

/**
 * Library read use cases (phase 2). Thin by design: orchestration belongs here, data access in
 * the repository, presentation in the ViewModels.
 */
class GetTracksUseCase(private val music: MusicRepository) {
    operator fun invoke(query: TrackQuery = TrackQuery.All): Flow<List<Track>> = music.tracks(query)
}

class GetAlbumsUseCase(private val music: MusicRepository) {
    operator fun invoke(): Flow<List<Album>> = music.albums()
}

class GetArtistsUseCase(private val music: MusicRepository) {
    operator fun invoke(): Flow<List<Artist>> = music.artists()
}

class GetGenresUseCase(private val music: MusicRepository) {
    operator fun invoke(): Flow<List<Genre>> = music.genres()
}

class GetFoldersUseCase(private val music: MusicRepository) {
    operator fun invoke(): Flow<List<LibraryFolder>> = music.folders()
}

class GetLibraryStatsUseCase(private val music: MusicRepository) {
    operator fun invoke(): Flow<LibraryStats> = music.stats
}

class GetRecentlyAddedUseCase(private val music: MusicRepository) {
    operator fun invoke(limit: Int = 20): Flow<List<Track>> = music.recentlyAdded(limit)
}

class GetRecentlyPlayedUseCase(private val music: MusicRepository) {
    operator fun invoke(limit: Int = 20): Flow<List<Track>> = music.recentlyPlayed(limit)
}

class GetAlbumTracksUseCase(private val music: MusicRepository) {
    operator fun invoke(albumId: String): Flow<List<Track>> = music.tracksInAlbum(albumId)
}

class GetArtistTracksUseCase(private val music: MusicRepository) {
    operator fun invoke(artistId: String): Flow<List<Track>> = music.tracksByArtist(artistId)
}

/** Full detail of one track, enriched on demand (format probe + tags) without blocking a list. */
class GetTrackDetailUseCase(
    private val music: MusicRepository,
    private val metadata: MetadataRepository,
) {
    suspend operator fun invoke(trackId: String, enrich: Boolean = true): Outcome<Track?> {
        val track = music.trackById(trackId) ?: return Outcome.Success(null)
        if (!enrich || track.format != null) return Outcome.Success(track)
        return metadata.enrich(track).map { it as Track? }
    }
}

class SearchLibraryUseCase(
    private val search: SearchRepository,
) {
    suspend operator fun invoke(query: String, limitPerType: Int = 10): Outcome<SearchResults> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return Outcome.Success(SearchResults(trimmed, emptyList(), 0L))
        return search.search(trimmed, limitPerType)
    }

} // SearchLibraryUseCase

/** Instant track results while the user types: SQL-backed, no format probing, no artwork IO. */
class SearchTracksQuickUseCase(private val search: SearchRepository) {
    suspend operator fun invoke(term: String, limit: Int = 50): Outcome<List<Track>> =
        search.searchTracks(term.trim(), limit)
}

class RefreshLibraryUseCase(private val music: MusicRepository) {
    suspend operator fun invoke(force: Boolean = false): Outcome<Unit> = music.refreshLibrary(force)
}

class AddMusicFolderUseCase(private val music: MusicRepository) {
    suspend operator fun invoke(treeUri: String): Outcome<Unit> = music.addMusicFolder(treeUri)
}

class RemoveMusicFolderUseCase(private val music: MusicRepository) {
    suspend operator fun invoke(treeUri: String): Outcome<Unit> = music.removeMusicFolder(treeUri)
}

class CancelScanUseCase(private val music: MusicRepository) {
    suspend operator fun invoke(): Outcome<Unit> = music.cancelScan()
}

class PruneMissingTracksUseCase(private val music: MusicRepository) {
    suspend operator fun invoke(): Outcome<Int> = music.pruneMissingTracks()
}
