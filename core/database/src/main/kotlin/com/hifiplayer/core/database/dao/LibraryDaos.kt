package com.hifiplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.hifiplayer.core.database.entity.AlbumEntity
import com.hifiplayer.core.database.entity.ArtistEntity
import com.hifiplayer.core.database.entity.EqPresetEntity
import com.hifiplayer.core.database.entity.FavoriteEntity
import com.hifiplayer.core.database.entity.FolderEntity
import com.hifiplayer.core.database.entity.LibrarySourceEntity
import com.hifiplayer.core.database.entity.PlayHistoryEntity
import com.hifiplayer.core.database.entity.PlaylistEntity
import com.hifiplayer.core.database.entity.PlaylistTrackEntity
import com.hifiplayer.core.database.entity.QueueItemEntity
import com.hifiplayer.core.database.entity.QueueMetaEntity
import com.hifiplayer.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data-access objects. Only the data layer touches these; the UI reads Flows through repositories
 * (requirement 3) and every write happens off the main thread inside a coroutine.
 */
@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE uri = :uri LIMIT 1")
    suspend fun byUri(uri: String): TrackEntity?

    @Query("SELECT COUNT(*) FROM tracks")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("SELECT * FROM tracks WHERE format_analyzed = 0 LIMIT :limit")
    suspend fun pendingAnalysis(limit: Int): List<TrackEntity>

    /** Paged read used to rebuild the album/artist/folder aggregates after a scan. */
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC LIMIT :limit OFFSET :offset")
    suspend fun pageAll(limit: Int, offset: Int): List<TrackEntity>

    @Query("SELECT id, uri FROM tracks LIMIT :limit OFFSET :offset")
    suspend fun pageIdsAndUris(limit: Int, offset: Int): List<TrackIdUri>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun countNow(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Update
    suspend fun update(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT id FROM tracks")
    suspend fun allIds(): List<String>

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM tracks")
    suspend fun totalSizeBytes(): Long

    @Query("SELECT COALESCE(SUM(duration_ms), 0) FROM tracks")
    suspend fun totalDurationMs(): Long

    /** Full-text-ish query used by the library list and the search screen (requirement 16/24). */
    @Query(
        """
        SELECT * FROM tracks
        WHERE (:searchTerm IS NULL OR (
            title LIKE '%' || :searchTerm || '%' COLLATE NOCASE OR
            artist LIKE '%' || :searchTerm || '%' COLLATE NOCASE OR
            album LIKE '%' || :searchTerm || '%' COLLATE NOCASE OR
            genre LIKE '%' || :searchTerm || '%' COLLATE NOCASE OR
            folder_path LIKE '%' || :searchTerm || '%' COLLATE NOCASE))
          AND (:losslessOnly = 0 OR codec_id IN ('flac','alac','wav','aiff','dsd'))
          AND (:highResOnly = 0 OR (bit_depth > 16 OR sample_rate_hz > 48000))
        ORDER BY
            CASE WHEN :sortKey = 'TITLE' THEN title END COLLATE NOCASE ASC,
            CASE WHEN :sortKey = 'ARTIST' THEN artist END COLLATE NOCASE ASC,
            CASE WHEN :sortKey = 'ALBUM' THEN album END COLLATE NOCASE ASC,
            CASE WHEN :sortKey = 'YEAR' THEN year END ASC,
            CASE WHEN :sortKey = 'DATE_ADDED' THEN date_added_epoch_sec END DESC,
            CASE WHEN :sortKey = 'DURATION' THEN duration_ms END ASC,
            CASE WHEN :sortKey = 'FORMAT' THEN bit_depth END DESC,
            CASE WHEN :sortKey = 'FILENAME' THEN display_name END COLLATE NOCASE ASC,
            title COLLATE NOCASE ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeQuery(
        searchTerm: String?,
        sortKey: String,
        limit: Int,
        offset: Int,
        losslessOnly: Int = 0,
        highResOnly: Int = 0,
    ): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM tracks
        WHERE (:artistId IS NULL OR artist_id = :artistId)
          AND (:albumId IS NULL OR album_id = :albumId)
          AND (:genre IS NULL OR genre = :genre)
          AND (:folderPath IS NULL OR folder_path LIKE :folderPath || '%')
        ORDER BY disc_number ASC, track_number ASC, title COLLATE NOCASE ASC
        """,
    )
    fun observeFiltered(artistId: String?, albumId: String?, genre: String?, folderPath: String?): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY date_added_epoch_sec DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN favorites f ON f.track_id = t.id
        ORDER BY f.added_at_epoch_ms DESC
        """,
    )
    fun observeFavorites(): Flow<List<TrackEntity>>

    @Query("SELECT codec_id AS codecId, COUNT(*) AS count FROM tracks WHERE codec_id IS NOT NULL GROUP BY codec_id")
    fun observeCodecBreakdown(): Flow<List<CodecCount>>

    @Query("SELECT COUNT(*) FROM tracks WHERE codec_id IN ('flac','alac','wav','aiff','dsd')")
    suspend fun losslessCount(): Int

    @Query("SELECT COUNT(*) FROM tracks WHERE bit_depth > 16 OR sample_rate_hz > 48000")
    suspend fun highResolutionCount(): Int

    @Query("SELECT COUNT(*) FROM tracks WHERE codec_id IS NULL")
    suspend fun unknownFormatCount(): Int

    @Query("SELECT COUNT(DISTINCT album_id) FROM tracks WHERE album_id IS NOT NULL")
    suspend fun albumCount(): Int

    @Query("SELECT COUNT(DISTINCT artist_id) FROM tracks WHERE artist_id IS NOT NULL")
    suspend fun artistCount(): Int
}

data class CodecCount(val codecId: String, val count: Int)

data class TrackIdUri(val id: String, val uri: String)

@Dao
interface LibraryStructureDao {

    @Query("SELECT * FROM albums ORDER BY title COLLATE NOCASE ASC")
    fun observeAlbums(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE ASC")
    fun observeArtists(): Flow<List<ArtistEntity>>

    @Query("SELECT genre AS name, COUNT(*) AS trackCount FROM tracks WHERE genre IS NOT NULL AND genre != '' GROUP BY genre ORDER BY genre COLLATE NOCASE ASC")
    fun observeGenres(): Flow<List<GenreRow>>

    @Query("SELECT folder_path AS path, COUNT(*) AS trackCount, SUM(size_bytes) AS totalSizeBytes FROM tracks WHERE folder_path != '' GROUP BY folder_path ORDER BY folder_path ASC")
    fun observeFolders(): Flow<List<FolderRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbums(albums: List<AlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtists(artists: List<ArtistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolders(folders: List<FolderEntity>)

    @Query("DELETE FROM albums")
    suspend fun clearAlbums()

    @Query("DELETE FROM artists")
    suspend fun clearArtists()

    @Query("DELETE FROM folders")
    suspend fun clearFolders()

    /**
     * Rebuilds the derived aggregates in a single transaction: either the whole structure matches
     * the tracks table or nothing changes (requirement 16/35: no half-updated library).
     */
    @Transaction
    suspend fun rebuild(albums: List<AlbumEntity>, artists: List<ArtistEntity>, folders: List<FolderEntity>) {
        clearAlbums()
        clearArtists()
        clearFolders()
        upsertAlbums(albums)
        upsertArtists(artists)
        upsertFolders(folders)
    }
}

data class GenreRow(val name: String, val trackCount: Int)
data class FolderRow(val path: String, val trackCount: Int, val totalSizeBytes: Long)

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY updated_at_epoch_ms DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name, updated_at_epoch_ms = :nowMs WHERE id = :id")
    suspend fun rename(id: String, name: String, nowMs: Long)

    @Query("UPDATE playlists SET updated_at_epoch_ms = :nowMs WHERE id = :id")
    suspend fun touch(id: String, nowMs: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun trackCount(playlistId: String): Int

    @Query("SELECT COALESCE(SUM(t.duration_ms),0) FROM playlist_tracks pt JOIN tracks t ON t.id = pt.track_id WHERE pt.playlist_id = :playlistId")
    suspend fun totalDurationMs(playlistId: String): Long

    @Query("SELECT pt.track_id FROM playlist_tracks pt WHERE pt.playlist_id = :playlistId ORDER BY pt.position ASC")
    suspend fun trackIds(playlistId: String): List<String>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON pt.track_id = t.id
        WHERE pt.playlist_id = :playlistId
        ORDER BY pt.position ASC
        """,
    )
    fun observeTracks(playlistId: String): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun clearEntries(playlistId: String)

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId AND track_id = :trackId")
    suspend fun deleteEntry(playlistId: String, trackId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_tracks WHERE playlist_id = :playlistId AND track_id = :trackId)")
    suspend fun contains(playlistId: String, trackId: String): Boolean

    @Query("SELECT MAX(position) FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun maxPosition(playlistId: String): Int?

    /**
     * Replaces the ordering in one transaction: drag & drop sends the final list, which keeps
     * positions dense and free of gaps (requirement 25).
     */
    @Transaction
    suspend fun replaceOrder(playlistId: String, orderedTrackIds: List<String>, nowMs: Long) {
        clearEntries(playlistId)
        insertEntries(
            orderedTrackIds.mapIndexed { index, trackId ->
                PlaylistTrackEntity(playlist_id = playlistId, track_id = trackId, position = index, added_at_epoch_ms = nowMs)
            },
        )
        touch(playlistId, nowMs)
    }
}

@Dao
interface FavoriteDao {

    @Query("SELECT track_id FROM favorites")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE track_id = :trackId)")
    fun observeIsFavorite(trackId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE track_id = :trackId")
    suspend fun delete(trackId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE track_id = :trackId)")
    suspend fun contains(trackId: String): Boolean
}

@Dao
interface PlayHistoryDao {

    @Insert
    suspend fun insert(entry: PlayHistoryEntity)

    @Query("DELETE FROM play_history WHERE track_id = :trackId")
    suspend fun deleteForTrack(trackId: String)

    @Query("DELETE FROM play_history WHERE id NOT IN (SELECT id FROM play_history ORDER BY played_at_epoch_ms DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN (
            SELECT track_id, MAX(played_at_epoch_ms) AS last_played
            FROM play_history GROUP BY track_id
        ) h ON h.track_id = t.id
        ORDER BY h.last_played DESC
        LIMIT :limit
        """,
    )
    fun observeRecentlyPlayed(limit: Int): Flow<List<TrackEntity>>

    @Query("DELETE FROM play_history")
    suspend fun clear()
}

@Dao
interface QueueDao {

    @Query("SELECT * FROM queue_items ORDER BY position ASC")
    suspend fun items(): List<QueueItemEntity>

    @Query("SELECT * FROM queue_meta WHERE id = 0 LIMIT 1")
    suspend fun meta(): QueueMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<QueueItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeta(meta: QueueMetaEntity)

    @Query("DELETE FROM queue_items")
    suspend fun clearItems()

    @Query("DELETE FROM queue_meta")
    suspend fun clearMeta()

    @Transaction
    suspend fun replaceQueue(items: List<QueueItemEntity>, meta: QueueMetaEntity) {
        clearItems()
        insertItems(items)
        insertMeta(meta)
    }
}

@Dao
interface LibrarySourceDao {

    @Query("SELECT * FROM library_sources ORDER BY added_at_epoch_ms ASC")
    fun observeSources(): Flow<List<LibrarySourceEntity>>

    @Query("SELECT * FROM library_sources")
    suspend fun sources(): List<LibrarySourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: LibrarySourceEntity)

    @Query("DELETE FROM library_sources WHERE tree_uri = :treeUri")
    suspend fun delete(treeUri: String)

    @Query("UPDATE library_sources SET last_scan_epoch_ms = :epochMs WHERE tree_uri = :treeUri")
    suspend fun markScanned(treeUri: String, epochMs: Long)
}

@Dao
interface EqPresetDao {

    @Query("SELECT * FROM eq_presets ORDER BY name COLLATE NOCASE ASC")
    fun observePresets(): Flow<List<EqPresetEntity>>

    @Query("SELECT * FROM eq_presets")
    suspend fun presets(): List<EqPresetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: EqPresetEntity)

    @Query("DELETE FROM eq_presets WHERE id = :id")
    suspend fun delete(id: String)
}
