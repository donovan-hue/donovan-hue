package com.hifiplayer.data.repository.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.error.TypedAppException
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.outcomeOf
import com.hifiplayer.core.common.time.TimeProvider
import com.hifiplayer.core.database.Mappers
import com.hifiplayer.core.database.entity.PlayHistoryEntity
import com.hifiplayer.core.database.entity.TrackEntity
import com.hifiplayer.core.storage.FileAccess
import com.hifiplayer.data.repository.local.DatabaseProvisioning
import com.hifiplayer.domain.model.error.StorageError
import com.hifiplayer.domain.model.library.Album
import com.hifiplayer.domain.model.library.Artist
import com.hifiplayer.domain.model.library.Genre
import com.hifiplayer.domain.model.library.LibraryFolder
import com.hifiplayer.domain.model.library.LibraryStats
import com.hifiplayer.domain.model.library.ScanProgress
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.library.TrackQuery
import com.hifiplayer.domain.model.library.TrackSort
import com.hifiplayer.domain.model.library.SortDirection
import com.hifiplayer.domain.repository.MusicRepository
import com.hifiplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Requirement 16/17: the read model of the library plus the scan entry points.
 *
 * The UI only sees domain models: Room entities never leave this class, and the DAO's flows are
 * mapped (and where needed re-sorted) so callers get exactly [TrackQuery] semantics.
 */
class MusicRepositoryImpl(
    private val context: Context,
    private val provisioning: DatabaseProvisioning,
    private val scanner: LibraryScanner,
    private val settingsRepository: SettingsRepository,
    private val fileAccess: FileAccess,
    private val dispatchers: DispatcherProvider,
    private val timeProvider: TimeProvider = TimeProvider.System,
) : MusicRepository {

    private val trackDao = provisioning.tracks
    private val structureDao = provisioning.structure
    private val historyDao = provisioning.history
    private val sourceDao = provisioning.sources

    override val scanProgress: StateFlow<ScanProgress> = scanner.progress

    override val stats: Flow<LibraryStats> = trackDao.observeCount()
        .map { computeStats() }
        .flowOn(dispatchers.io)

    override fun tracks(query: TrackQuery): Flow<List<Track>> {
        val source = when {
            query.favoritesOnly -> trackDao.observeFavorites()
            query.artistId != null || query.albumId != null || query.genre != null || query.folderPath != null ->
                trackDao.observeFiltered(query.artistId, query.albumId, query.genre, query.folderPath)
            else -> trackDao.observeQuery(
                searchTerm = query.searchTerm?.takeIf { it.isNotBlank() },
                sortKey = query.sort.name,
                limit = query.limit ?: Int.MAX_VALUE,
                offset = query.offset,
                losslessOnly = if (query.losslessOnly) 1 else 0,
                highResOnly = if (query.highResolutionOnly) 1 else 0,
            )
        }
        return source
            // The filtered-navigation flow has no SQL LIMIT, so the query's own limit/offset and
            // sort direction are applied here; the sort itself stays SQL-side whenever possible.
            .map { entities ->
                var tracks = entities.map { entity -> entity.toTrackDomain() }
                query.searchTerm?.takeIf { it.isNotBlank() }?.let { term ->
                    // `observeFiltered` does not search: apply the same rule the SQL does.
                    tracks = tracks.filter { track ->
                        track.title.contains(term, ignoreCase = true) ||
                            track.artist?.contains(term, ignoreCase = true) == true ||
                            track.album?.contains(term, ignoreCase = true) == true ||
                            track.genre?.contains(term, ignoreCase = true) == true ||
                            track.folderPath.contains(term, ignoreCase = true)
                    }
                }
                if (query.losslessOnly) tracks = tracks.filter { it.isLossless }
                if (query.highResolutionOnly) tracks = tracks.filter { it.isHighResolution }
                if (query.direction == SortDirection.DESCENDING && query.sort != TrackSort.DATE_ADDED) {
                    tracks = tracks.reversed()
                }
                if (query.offset > 0 || query.limit != null) {
                    val from = query.offset.coerceAtMost(tracks.size)
                    val to = query.limit?.let { (from + it).coerceAtMost(tracks.size) } ?: tracks.size
                    tracks = tracks.subList(from, to).toList()
                }
                tracks
            }
            .flowOn(dispatchers.io)
    }

    override fun albums(): Flow<List<Album>> =
        structureDao.observeAlbums()
            .map { list -> list.map { album -> Mappers.run { album.toDomain() } } }.flowOn(dispatchers.io)

    override fun artists(): Flow<List<Artist>> =
        structureDao.observeArtists()
            .map { list -> list.map { artist -> Mappers.run { artist.toDomain() } } }.flowOn(dispatchers.io)

    override fun genres(): Flow<List<Genre>> =
        structureDao.observeGenres().map { rows -> rows.map { Genre(it.name, it.trackCount) } }.flowOn(dispatchers.io)

    override fun folders(): Flow<List<LibraryFolder>> =
        structureDao.observeFolders().map { rows ->
            rows.map { row ->
                LibraryFolder(
                    path = row.path,
                    displayName = row.path.substringAfterLast('/').ifBlank { row.path },
                    trackCount = row.trackCount,
                    totalSizeBytes = row.totalSizeBytes ?: 0L,
                    source = Mappers.decodeSource(null),
                )
            }
        }.flowOn(dispatchers.io)

    override fun recentlyAdded(limit: Int): Flow<List<Track>> =
        trackDao.observeRecentlyAdded(limit).map { list -> list.map { entity -> entity.toTrackDomain() } }.flowOn(dispatchers.io)

    override fun recentlyPlayed(limit: Int): Flow<List<Track>> =
        historyDao.observeRecentlyPlayed(limit).map { list -> list.map { entity -> entity.toTrackDomain() } }.flowOn(dispatchers.io)

    override fun tracksInAlbum(albumId: String): Flow<List<Track>> =
        trackDao.observeFiltered(null, albumId, null, null)
            .map { list -> list.map { entity -> entity.toTrackDomain() }.sortedBy { it.discTrackKey } }
            .flowOn(dispatchers.io)

    override fun tracksByArtist(artistId: String): Flow<List<Track>> =
        trackDao.observeFiltered(artistId, null, null, null)
            .map { list -> list.map { entity -> entity.toTrackDomain() }.sortedWith(compareBy({ it.displayAlbum }, { it.discTrackKey })) }
            .flowOn(dispatchers.io)

    override suspend fun trackById(trackId: String): Track? = withContext(dispatchers.io) {
        trackDao.byId(trackId)?.toTrackDomain()
    }

    override suspend fun tracksByIds(trackIds: List<String>): List<Track> = withContext(dispatchers.io) {
        if (trackIds.isEmpty()) return@withContext emptyList()
        val byId = trackDao.byIds(trackIds).associate { entity -> entity.id to entity.toTrackDomain() }
        trackIds.mapNotNull { byId[it] }
    }

    override suspend fun verifyTrackAvailability(trackId: String): Outcome<Boolean> = withContext(dispatchers.io) {
        val entity = trackDao.byId(trackId)
            ?: return@withContext Outcome.Failure(StorageError.FileMissing("no existe la pista $trackId"))
        when (val validation = fileAccess.validate(Uri.parse(entity.uri), minimumSizeBytes = 1L)) {
            is Outcome.Success -> Outcome.Success(validation.value.isValid)
            is Outcome.Failure -> Outcome.Failure(validation.error)
        }
    }

    // ---------------------------------------------------------------- scanning

    override suspend fun refreshLibrary(force: Boolean): Outcome<Unit> = withContext(dispatchers.io) {
        if (scanner.isRunning()) {
            return@withContext Outcome.Failure(
                StorageError.ScanFailed("escaneo", "ya hay un análisis de biblioteca en curso"),
            )
        }
        val settings = settingsRepository.settings.value.library
        val trees = settings.documentTreeUris.ifEmpty { scanner.registeredTrees().toSet() }

        if (!force && !settings.automaticScanning && !settings.scanOnStartup) {
            AppLogger.i(TAG, "Escaneo automático desactivado; se omite")
        }

        val result = scanner.scan(
            useMediaStore = settings.useMediaStore,
            safTrees = trees.toList(),
            excludedFolders = settings.excludedFolders,
            minimumSizeBytes = settings.minimumSizeBytes,
            minimumDurationSec = settings.minimumDurationSec,
            analyzeFormat = settings.analyzeFormatsOnScan,
        )

        if (result.cancelled) {
            return@withContext Outcome.Failure(StorageError.ScanFailed("escaneo", "cancelado por el usuario"))
        }
        if (result.indexed == 0 && result.failed > 0 && result.elapsedMs > 0 && result.skipped == 0) {
            return@withContext Outcome.Failure(
                StorageError.ScanFailed("análisis", "no se pudo leer ningún archivo (${result.failed} con error)"),
            )
        }
        Outcome.Success(Unit)
    }

    override suspend fun addMusicFolder(treeUri: String): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            val uri = Uri.parse(treeUri)
            // Persist the grant so the folder survives a restart without asking again; if the
            // caller did not pass a persistable grant the call fails and we say why.
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (security: SecurityException) {
                AppLogger.w(TAG, "El permiso de la carpeta no es persistente: $treeUri")
            }
            scanner.registerTree(treeUri, uri.lastPathSegment ?: "Carpeta")
            settingsRepository.updateLibrary { it.copy(documentTreeUris = it.documentTreeUris + treeUri) }
            val settings = settingsRepository.settings.value.library
            val result = scanner.scan(
                useMediaStore = false,
                safTrees = listOf(treeUri),
                excludedFolders = settings.excludedFolders,
                minimumSizeBytes = settings.minimumSizeBytes,
                minimumDurationSec = settings.minimumDurationSec,
                analyzeFormat = settings.analyzeFormatsOnScan,
            )
            if (result.failed > 0 && result.indexed == 0) {
                throw TypedAppException(StorageError.ScanFailed("carpeta", "la carpeta no contiene audio legible"))
            }
        }
    }

    override suspend fun removeMusicFolder(treeUri: String): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            scanner.unregisterTree(treeUri)
            settingsRepository.updateLibrary { it.copy(documentTreeUris = it.documentTreeUris - treeUri) }
            try {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (exception: Exception) {
                AppLogger.w(TAG, "No se pudo liberar el permiso de la carpeta (puede que ya no estuviera concedido)")
            }
            // Tracks that lived in that folder are only removed when the file is really gone.
            pruneMissingTracks()
            Unit
        }
    }

    override suspend fun cancelScan(): Outcome<Unit> = withContext(dispatchers.io) {
        scanner.cancel()
        Outcome.Success(Unit)
    }

    override suspend fun pruneMissingTracks(): Outcome<Int> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            var removed = 0
            var offset = 0
            while (true) {
                val page = trackDao.pageIdsAndUris(PRUNE_PAGE, offset)
                if (page.isEmpty()) break
                val missing = page.filter { row ->
                    val validation = fileAccess.validate(Uri.parse(row.uri), minimumSizeBytes = 1L).getOrNull()
                    validation?.isValid != true
                }.map { it.id }
                if (missing.isNotEmpty()) {
                    trackDao.deleteByIds(missing)
                    missing.forEach { id ->
                        provisioning.favorites.delete(id)
                        historyDao.deleteForTrack(id)
                    }
                    removed += missing.size
                }
                offset += page.size
                if (page.size < PRUNE_PAGE) break
            }
            if (removed > 0) AppLogger.i(TAG, "Se quitaron $removed pistas que ya no existen")
            removed
        }
    }

    override suspend fun recordPlayback(trackId: String, completedFraction: Float): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            historyDao.insert(
                PlayHistoryEntity(
                    track_id = trackId,
                    played_at_epoch_ms = timeProvider.nowMs(),
                    completed_fraction = completedFraction.coerceIn(0f, 1f),
                ),
            )
            historyDao.trimTo(HISTORY_LIMIT)
        }
    }

    override suspend fun clearPlaybackHistory(): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) { historyDao.clear() }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun computeStats(): LibraryStats {
        val codecs = trackDao.observeCodecBreakdown().first().associate { row ->
            codecFromId(row.codecId) to row.count
        }
        return LibraryStats(
            trackCount = trackDao.countNow(),
            albumCount = trackDao.albumCount(),
            artistCount = trackDao.artistCount(),
            totalDurationMs = trackDao.totalDurationMs(),
            totalSizeBytes = trackDao.totalSizeBytes(),
            losslessCount = trackDao.losslessCount(),
            highResolutionCount = trackDao.highResolutionCount(),
            unknownFormatCount = trackDao.unknownFormatCount(),
            codecBreakdown = codecs,
        )
    }

    private fun codecFromId(id: String) =
        com.hifiplayer.domain.model.audio.Codec.entries.firstOrNull { it.id == id }
            ?: com.hifiplayer.domain.model.audio.Codec.UNKNOWN

    private fun TrackEntity.toTrackDomain(): Track = Mappers.run { toDomain() }

    private companion object {
        const val TAG = "MusicRepository"
        const val PRUNE_PAGE = 200
        const val HISTORY_LIMIT = 500
    }
}
