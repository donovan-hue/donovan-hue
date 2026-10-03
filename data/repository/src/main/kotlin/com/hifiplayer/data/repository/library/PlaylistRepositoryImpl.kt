package com.hifiplayer.data.repository.library

import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.error.TypedAppException
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.outcomeOf
import com.hifiplayer.core.common.time.TimeProvider
import com.hifiplayer.core.database.Mappers
import com.hifiplayer.core.database.entity.PlaylistEntity
import com.hifiplayer.core.database.entity.PlaylistTrackEntity
import com.hifiplayer.data.repository.local.DatabaseProvisioning
import com.hifiplayer.domain.model.error.StorageError
import com.hifiplayer.domain.model.library.Playlist
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/** Requirement 25: playlists in Room, with ordering, CRUD and drag & drop reordering. */
class PlaylistRepositoryImpl(
    private val provisioning: DatabaseProvisioning,
    private val dispatchers: DispatcherProvider,
    private val timeProvider: TimeProvider = TimeProvider.System,
) : PlaylistRepository {

    private val playlistDao = provisioning.playlists
    private val trackDao = provisioning.tracks

    override fun playlists(): Flow<List<Playlist>> = playlistDao.observePlaylists()
        .map { entities -> entities.map { it.toDomainWithStats() } }
        .flowOn(dispatchers.io)

    override suspend fun playlistById(playlistId: String): Playlist? = withContext(dispatchers.io) {
        playlistDao.byId(playlistId)?.toDomainWithStats()
    }

    override fun tracksOf(playlistId: String): Flow<List<Track>> = playlistDao.observeTracks(playlistId)
        .map { entities -> entities.map { Mappers.run { it.toDomain() } } }
        .flowOn(dispatchers.io)

    override suspend fun create(name: String): Outcome<Playlist> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            val trimmed = name.trim().ifBlank { throw TypedAppException(StorageError.Unknown("El nombre de la lista no puede estar vacío")) }
            val now = timeProvider.nowMs()
            val entity = PlaylistEntity(
                id = "pl:" + UUID.randomUUID().toString().take(12),
                name = trimmed,
                created_at_epoch_ms = now,
                updated_at_epoch_ms = now,
            )
            playlistDao.upsert(entity)
            entity.toDomainWithStats()
        }
    }

    override suspend fun rename(playlistId: String, newName: String): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            val trimmed = newName.trim()
            if (trimmed.isEmpty()) throw TypedAppException(StorageError.Unknown("El nombre de la lista no puede estar vacío"))
            if (playlistDao.byId(playlistId) == null) throw TypedAppException(StorageError.FileMissing("no existe la lista $playlistId"))
            playlistDao.rename(playlistId, trimmed, timeProvider.nowMs())
        }
    }

    override suspend fun delete(playlistId: String): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) { playlistDao.delete(playlistId) }
    }

    override suspend fun addTrack(playlistId: String, trackId: String, position: Int?): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            ensurePlaylistExists(playlistId)
            if (trackDao.byId(trackId) == null) throw TypedAppException(StorageError.FileMissing("no existe la pista $trackId"))
            val target = position ?: (((playlistDao.maxPosition(playlistId) ?: -1) + 1))
            val entries = orderedTrackIds(playlistId).toMutableList()
            val insertAt = target.coerceIn(0, entries.size)
            entries.add(insertAt, trackId)
            playlistDao.replaceOrder(playlistId, entries, timeProvider.nowMs())
        }
    }

    override suspend fun addTracks(playlistId: String, trackIds: List<String>): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            ensurePlaylistExists(playlistId)
            if (trackIds.isEmpty()) return@outcomeOf
            val known = trackDao.byIds(trackIds).map { it.id }.toSet()
            val missing = trackIds.filterNot { it in known }
            if (missing.isNotEmpty()) AppLogger.w(TAG, "Se ignoran ${missing.size} pistas inexistentes al añadir a la lista")
            val entries = orderedTrackIds(playlistId).toMutableList()
            entries += trackIds.filter { it in known }
            playlistDao.replaceOrder(playlistId, entries, timeProvider.nowMs())
        }
    }

    override suspend fun removeTrack(playlistId: String, trackId: String): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            ensurePlaylistExists(playlistId)
            val entries = orderedTrackIds(playlistId).toMutableList()
            val index = entries.indexOf(trackId)
            if (index < 0) throw TypedAppException(StorageError.Unknown("la pista no está en la lista"))
            entries.removeAt(index)
            playlistDao.replaceOrder(playlistId, entries, timeProvider.nowMs())
        }
    }

    override suspend fun moveTrack(playlistId: String, fromIndex: Int, toIndex: Int): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            ensurePlaylistExists(playlistId)
            val entries = orderedTrackIds(playlistId).toMutableList()
            if (fromIndex !in entries.indices) {
                throw TypedAppException(StorageError.Unknown("posición de origen fuera de rango ($fromIndex de ${entries.size}))"))
            }
            val destination = toIndex.coerceIn(0, entries.size - 1)
            val moved = entries.removeAt(fromIndex)
            entries.add(destination, moved)
            playlistDao.replaceOrder(playlistId, entries, timeProvider.nowMs())
        }
    }

    override suspend fun hasTrack(playlistId: String, trackId: String): Boolean = withContext(dispatchers.io) {
        playlistDao.contains(playlistId, trackId)
    }

    private suspend fun orderedTrackIds(playlistId: String): List<String> = playlistDao.trackIds(playlistId)

    private suspend fun ensurePlaylistExists(playlistId: String) {
        if (playlistDao.byId(playlistId) == null) throw TypedAppException(StorageError.FileMissing("no existe la lista $playlistId"))
    }

    private suspend fun PlaylistEntity.toDomainWithStats(): Playlist = Mappers.run {
        toDomain(
            trackCount = playlistDao.trackCount(id),
            totalDurationMs = playlistDao.totalDurationMs(id),
            artworkUri = null,
        )
    }

    private companion object {
        const val TAG = "PlaylistRepository"
    }
}
