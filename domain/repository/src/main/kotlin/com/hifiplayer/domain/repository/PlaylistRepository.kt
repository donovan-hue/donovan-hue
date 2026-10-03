package com.hifiplayer.domain.repository

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.library.Playlist
import com.hifiplayer.domain.model.library.Track
import kotlinx.coroutines.flow.Flow

/** Requirement 25: playlists persisted in Room, with ordering. */
interface PlaylistRepository {

    fun playlists(): Flow<List<Playlist>>

    suspend fun playlistById(playlistId: String): Playlist?

    fun tracksOf(playlistId: String): Flow<List<Track>>

    suspend fun create(name: String): Outcome<Playlist>

    suspend fun rename(playlistId: String, newName: String): Outcome<Unit>

    suspend fun delete(playlistId: String): Outcome<Unit>

    suspend fun addTrack(playlistId: String, trackId: String, position: Int? = null): Outcome<Unit>

    suspend fun addTracks(playlistId: String, trackIds: List<String>): Outcome<Unit>

    suspend fun removeTrack(playlistId: String, trackId: String): Outcome<Unit>

    /** Drag & drop reorder. */
    suspend fun moveTrack(playlistId: String, fromIndex: Int, toIndex: Int): Outcome<Unit>

    suspend fun hasTrack(playlistId: String, trackId: String): Boolean
}
