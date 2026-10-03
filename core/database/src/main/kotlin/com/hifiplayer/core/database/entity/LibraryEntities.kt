package com.hifiplayer.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema (requirement 4). One table per concept; the domain models are mapped in
 * `Mappers.kt` so the database layout can evolve without touching the rest of the app.
 *
 * Format columns are nullable on purpose: a track whose header has not been analysed yet stores
 * NULL and the UI shows "sin analizar" instead of a made-up sample rate.
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index("album_id"),
        Index("artist_id"),
        Index("folder_path"),
        Index("uri", unique = true),
        Index("title"),
        Index("date_added_epoch_sec"),
    ],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val title: String,
    val artist: String?,
    val album_artist: String?,
    val album: String?,
    val album_id: String?,
    val artist_id: String?,
    val duration_ms: Long,
    val track_number: Int?,
    val disc_number: Int?,
    val year: Int?,
    val genre: String?,
    val size_bytes: Long,
    val mime_type: String?,
    val display_name: String,
    val relative_path: String?,
    @ColumnInfo(name = "folder_path") val folder_path: String,
    val date_added_epoch_sec: Long,
    val last_modified_epoch_sec: Long,
    // ---- verified format data (null until analysed) ----
    val codec_id: String?,
    val sample_rate_hz: Int?,
    val bit_depth: Int?,
    val channels: Int?,
    val bitrate_kbps: Int?,
    val is_float_pcm: Boolean?,
    val is_variable_bitrate: Boolean?,
    // ---- ReplayGain ----
    val rg_track_gain_db: Double?,
    val rg_album_gain_db: Double?,
    val rg_track_peak: Double?,
    val rg_album_peak: Double?,
    val rg_source: String?,
    // ---- extras ----
    val composer: String?,
    val copyright: String?,
    val has_embedded_artwork: Boolean,
    val artwork_uri: String?,
    val source_kind: String,
    val format_analyzed: Boolean,
)

@Entity(tableName = "albums", indices = [Index("title")])
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String?,
    val year: Int?,
    val track_count: Int,
    val total_duration_ms: Long,
    val cover_track_uri: String?,
    val max_sample_rate_hz: Int?,
    val max_bit_depth: Int?,
    val is_lossless: Boolean,
    val has_high_resolution: Boolean,
)

@Entity(tableName = "artists", indices = [Index("name")])
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val album_count: Int,
    val track_count: Int,
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val path: String,
    val display_name: String,
    val track_count: Int,
    val total_size_bytes: Long,
    val source_kind: String,
)

/**
 * Storage locations the user granted through the Storage Access Framework (requirement 17).
 * Persisted so the library survives restarts without asking again.
 */
@Entity(tableName = "library_sources")
data class LibrarySourceEntity(
    @PrimaryKey val tree_uri: String,
    val display_name: String,
    val added_at_epoch_ms: Long,
    val last_scan_epoch_ms: Long?,
    val is_enabled: Boolean,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val created_at_epoch_ms: Long,
    val updated_at_epoch_ms: Long,
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlist_id", "position"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlist_id"), Index("track_id")],
)
data class PlaylistTrackEntity(
    val playlist_id: String,
    val track_id: String,
    val position: Int,
    val added_at_epoch_ms: Long,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val track_id: String,
    val added_at_epoch_ms: Long,
)

@Entity(tableName = "play_history", indices = [Index("played_at_epoch_ms")])
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val track_id: String,
    val played_at_epoch_ms: Long,
    val completed_fraction: Float,
)

/** Persisted queue so playback can resume after a restart (requirement 27). */
@Entity(tableName = "queue_items")
data class QueueItemEntity(
    @PrimaryKey val position: Int,
    val track_id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val duration_ms: Long,
    val codec_id: String,
    val sample_rate_hz: Int?,
    val bit_depth: Int?,
    val artwork_uri: String?,
)

@Entity(tableName = "queue_meta")
data class QueueMetaEntity(
    @PrimaryKey val id: Int = 0,
    val current_index: Int,
    val origin: String,
    val shuffle_enabled: Boolean,
    val saved_at_epoch_ms: Long,
)

/** User-created EQ presets (requirement 12). Built-in presets are code, not rows. */
@Entity(tableName = "eq_presets")
data class EqPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val bands_json: String,
    val updated_at_epoch_ms: Long,
)
