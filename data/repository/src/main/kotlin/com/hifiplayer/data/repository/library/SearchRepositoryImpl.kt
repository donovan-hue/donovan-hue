package com.hifiplayer.data.repository.library

import com.hifiplayer.core.common.config.AppConfig
import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.outcomeOf
import com.hifiplayer.core.common.time.TimeProvider
import com.hifiplayer.core.database.Mappers
import com.hifiplayer.data.repository.local.DatabaseProvisioning
import com.hifiplayer.domain.model.library.SearchHit
import com.hifiplayer.domain.model.library.SearchHitType
import com.hifiplayer.domain.model.library.SearchResults
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.repository.SearchRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Requirement 24: global search across songs, albums, artists, genres, folders and playlists.
 *
 * The caller debounces input ([AppConfig.SEARCH_DEBOUNCE_MS]); this class stays pure and does one
 * pass per type, capped by [limitPerType] so a short query cannot drag the whole library into
 * memory.
 */
class SearchRepositoryImpl(
    private val provisioning: DatabaseProvisioning,
    private val dispatchers: DispatcherProvider,
    private val timeProvider: TimeProvider = TimeProvider.System,
) : SearchRepository {

    private val trackDao = provisioning.tracks
    private val structureDao = provisioning.structure
    private val playlistDao = provisioning.playlists

    override suspend fun search(query: String, limitPerType: Int): Outcome<SearchResults> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            val startedAt = timeProvider.nowMs()
            val term = query.trim()
            if (term.length < MIN_QUERY_LENGTH) {
                return@outcomeOf SearchResults(query = term, hits = emptyList(), tookMs = 0L)
            }

            val hits = mutableListOf<SearchHit>()

            // tracks
            trackDao.observeQuery(searchTerm = term, sortKey = "TITLE", limit = limitPerType, offset = 0).first()
                .forEach { entity ->
                    val track = Mappers.run { entity.toDomain() }
                    hits += SearchHit(
                        type = SearchHitType.TRACK,
                        id = track.id,
                        title = track.displayTitle,
                        subtitle = track.displayArtist,
                        artworkUri = track.artworkUri,
                        trackUri = track.uri,
                    )
                }

            // albums
            structureDao.observeAlbums().first()
                .filter { it.title.contains(term, ignoreCase = true) || it.artist?.contains(term, ignoreCase = true) == true }
                .take(limitPerType)
                .forEach { album ->
                    hits += SearchHit(
                        type = SearchHitType.ALBUM,
                        id = album.id,
                        title = album.title,
                        subtitle = album.artist,
                        artworkUri = album.cover_track_uri,
                    )
                }

            // artists
            structureDao.observeArtists().first()
                .filter { it.name.contains(term, ignoreCase = true) }
                .take(limitPerType)
                .forEach { artist ->
                    hits += SearchHit(
                        type = SearchHitType.ARTIST,
                        id = artist.id,
                        title = artist.name,
                        subtitle = "${artist.track_count} pistas · ${artist.album_count} álbumes",
                        artworkUri = null,
                    )
                }

            // genres
            structureDao.observeGenres().first()
                .filter { it.name.contains(term, ignoreCase = true) }
                .take(limitPerType)
                .forEach { genre ->
                    hits += SearchHit(
                        type = SearchHitType.GENRE,
                        id = genre.name,
                        title = genre.name,
                        subtitle = "${genre.trackCount} pistas",
                        artworkUri = null,
                    )
                }

            // folders
            structureDao.observeFolders().first()
                .filter { it.path.contains(term, ignoreCase = true) }
                .take(limitPerType)
                .forEach { folder ->
                    hits += SearchHit(
                        type = SearchHitType.FOLDER,
                        id = folder.path,
                        title = folder.path.substringAfterLast('/').ifBlank { folder.path },
                        subtitle = "${folder.trackCount} pistas",
                        artworkUri = null,
                    )
                }

            // playlists
            playlistDao.observePlaylists().first()
                .filter { it.name.contains(term, ignoreCase = true) }
                .take(limitPerType)
                .forEach { playlist ->
                    hits += SearchHit(
                        type = SearchHitType.PLAYLIST,
                        id = playlist.id,
                        title = playlist.name,
                        subtitle = "Lista de reproducción",
                        artworkUri = null,
                    )
                }

            SearchResults(query = term, hits = hits, tookMs = timeProvider.nowMs() - startedAt)
        }
    }

    override suspend fun searchTracks(query: String, limit: Int): Outcome<List<Track>> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            val term = query.trim()
            if (term.length < MIN_QUERY_LENGTH) return@outcomeOf emptyList()
            trackDao.observeQuery(searchTerm = term, sortKey = "TITLE", limit = limit, offset = 0).first()
                .map { Mappers.run { it.toDomain() } }
        }
    }

    private companion object {
        const val TAG = "SearchRepository"
        const val MIN_QUERY_LENGTH = 2
    }
}
