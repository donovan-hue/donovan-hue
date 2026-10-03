package com.hifiplayer.domain.repository

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.library.Album
import com.hifiplayer.domain.model.library.Artist
import com.hifiplayer.domain.model.library.Genre
import com.hifiplayer.domain.model.library.LibraryFolder
import com.hifiplayer.domain.model.library.LibraryStats
import com.hifiplayer.domain.model.library.ScanProgress
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.library.TrackQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Read model of the local music library (requirement 16/17).
 *
 * The UI/ViewModels only ever see this interface: Room, MediaStore, SAF and file IO stay in the
 * data layer. Queries return [Flow] so screens stay reactive without polling, and every call is
 * suspend so no database work can happen on the main thread.
 */
interface MusicRepository {

    val scanProgress: StateFlow<ScanProgress>

    val stats: Flow<LibraryStats>

    fun tracks(query: TrackQuery = TrackQuery.All): Flow<List<Track>>

    fun albums(): Flow<List<Album>>

    fun artists(): Flow<List<Artist>>

    fun genres(): Flow<List<Genre>>

    fun folders(): Flow<List<LibraryFolder>>

    fun recentlyAdded(limit: Int = 20): Flow<List<Track>>

    fun recentlyPlayed(limit: Int = 20): Flow<List<Track>>

    fun tracksInAlbum(albumId: String): Flow<List<Track>>

    fun tracksByArtist(artistId: String): Flow<List<Track>>

    suspend fun trackById(trackId: String): Track?

    suspend fun tracksByIds(trackIds: List<String>): List<Track>

    /** Tracks that no longer exist on disk are reported so the UI can explain the gap. */
    suspend fun verifyTrackAvailability(trackId: String): Outcome<Boolean>

    // ---------------- scanning ----------------

    /** Full library refresh. Uses MediaStore + every granted SAF tree, per [LibrarySettings]. */
    suspend fun refreshLibrary(force: Boolean = false): Outcome<Unit>

    /** Adds a SAF folder (from the system picker) and indexes it. */
    suspend fun addMusicFolder(treeUri: String): Outcome<Unit>

    suspend fun removeMusicFolder(treeUri: String): Outcome<Unit>

    suspend fun cancelScan(): Outcome<Unit>

    /** Removes references to tracks that are gone; keeps playlists/favorites consistent. */
    suspend fun pruneMissingTracks(): Outcome<Int>

    suspend fun recordPlayback(trackId: String, completedFraction: Float): Outcome<Unit>

    suspend fun clearPlaybackHistory(): Outcome<Unit>
}
