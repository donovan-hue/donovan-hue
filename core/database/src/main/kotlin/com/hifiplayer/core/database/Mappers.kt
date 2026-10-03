
package com.hifiplayer.core.database

import com.hifiplayer.core.database.entity.AlbumEntity
import com.hifiplayer.core.database.entity.ArtistEntity
import com.hifiplayer.core.database.entity.FolderEntity
import com.hifiplayer.core.database.entity.PlaylistEntity
import com.hifiplayer.core.database.entity.TrackEntity
import com.hifiplayer.domain.model.audio.AudioFormatSpec
import com.hifiplayer.domain.model.audio.Codec
import com.hifiplayer.domain.model.audio.PcmEncoding
import com.hifiplayer.domain.model.audio.ReplayGainInfo
import com.hifiplayer.domain.model.audio.ReplayGainSource
import com.hifiplayer.domain.model.library.Album
import com.hifiplayer.domain.model.library.Artist
import com.hifiplayer.domain.model.library.LibraryFolder
import com.hifiplayer.domain.model.library.LibrarySource
import com.hifiplayer.domain.model.library.Playlist
import com.hifiplayer.domain.model.library.Track

/**
 * Entity ↔ domain mapping. Keeping this in one place means the schema (an implementation detail)
 * never leaks into the domain or the UI.
 */
object Mappers {

    fun TrackEntity.toDomain(): Track = Track(
        id = id,
        uri = uri,
        title = title,
        artist = artist,
        albumArtist = album_artist,
        album = album,
        albumId = album_id,
        artistId = artist_id,
        durationMs = duration_ms,
        trackNumber = track_number,
        discNumber = disc_number,
        year = year,
        genre = genre,
        sizeBytes = size_bytes,
        mimeType = mime_type,
        displayName = display_name,
        relativePath = relative_path,
        dateAddedEpochSec = date_added_epoch_sec,
        lastModifiedEpochSec = last_modified_epoch_sec,
        format = toFormatSpec(),
        replayGain = ReplayGainInfo(
            trackGainDb = rg_track_gain_db,
            albumGainDb = rg_album_gain_db,
            trackPeak = rg_track_peak,
            albumPeak = rg_album_peak,
            source = rg_source?.let { runCatching { ReplayGainSource.valueOf(it) }.getOrNull() }
                ?: ReplayGainSource.NONE,
        ),
        hasEmbeddedArtwork = has_embedded_artwork,
        artworkUri = artwork_uri,
        composer = composer,
        copyright = copyright,
        source = decodeSource(source_kind),
    )

    private fun TrackEntity.toFormatSpec(): AudioFormatSpec? {
        val codec = codec_id?.let { runCatching { Codec.valueOf(it) }.getOrNull() } ?: return null
        val rate = sample_rate_hz ?: return null
        val depth = bit_depth ?: return null
        return AudioFormatSpec(
            sampleRateHz = rate,
            bitDepth = depth,
            channels = channels ?: 2,
            codec = codec,
            pcmEncoding = PcmEncoding.of(depth, is_float_pcm == true),
            bitrateKbps = bitrate_kbps,
            isVariableBitrate = is_variable_bitrate == true,
        )
    }

    fun Track.toEntity(folderPath: String): TrackEntity = TrackEntity(
        id = id,
        uri = uri,
        title = title,
        artist = artist,
        album_artist = albumArtist,
        album = album,
        album_id = albumId,
        artist_id = artistId,
        duration_ms = durationMs,
        track_number = trackNumber,
        disc_number = discNumber,
        year = year,
        genre = genre,
        size_bytes = sizeBytes,
        mime_type = mimeType,
        display_name = displayName,
        relative_path = relativePath,
        folder_path = folderPath,
        date_added_epoch_sec = dateAddedEpochSec,
        last_modified_epoch_sec = lastModifiedEpochSec,
        codec_id = format?.codec?.name,
        sample_rate_hz = format?.sampleRateHz,
        bit_depth = format?.bitDepth,
        channels = format?.channels,
        bitrate_kbps = format?.bitrateKbps,
        is_float_pcm = format?.isFloat,
        is_variable_bitrate = format?.isVariableBitrate,
        rg_track_gain_db = replayGain.trackGainDb,
        rg_album_gain_db = replayGain.albumGainDb,
        rg_track_peak = replayGain.trackPeak,
        rg_album_peak = replayGain.albumPeak,
        rg_source = replayGain.source.takeIf { !it.isEmpty }?.name,
        composer = composer,
        copyright = copyright,
        has_embedded_artwork = hasEmbeddedArtwork,
        artwork_uri = artworkUri,
        source_kind = encodeSource(source),
        format_analyzed = format != null,
    )

    fun AlbumEntity.toDomain(): Album = Album(
        id = id,
        title = title,
        artist = artist,
        year = year,
        trackCount = track_count,
        totalDurationMs = total_duration_ms,
        coverTrackUri = cover_track_uri,
        maxSampleRateHz = max_sample_rate_hz,
        maxBitDepth = max_bit_depth,
        isLossless = is_lossless,
        hasHighResolution = has_high_resolution,
    )

    fun Album.toEntity(): AlbumEntity = AlbumEntity(
        id = id,
        title = title,
        artist = artist,
        year = year,
        track_count = trackCount,
        total_duration_ms = totalDurationMs,
        cover_track_uri = coverTrackUri,
        max_sample_rate_hz = maxSampleRateHz,
        max_bit_depth = maxBitDepth,
        is_lossless = isLossless,
        has_high_resolution = hasHighResolution,
    )

    fun ArtistEntity.toDomain(): Artist = Artist(id = id, name = name, albumCount = album_count, trackCount = track_count)

    fun Artist.toEntity(): ArtistEntity = ArtistEntity(id = id, name = name, album_count = albumCount, track_count = trackCount)

    fun FolderEntity.toDomain(): LibraryFolder = LibraryFolder(
        path = path,
        displayName = display_name,
        trackCount = track_count,
        totalSizeBytes = total_size_bytes,
        source = decodeSource(source_kind),
    )

    fun PlaylistEntity.toDomain(trackCount: Int, totalDurationMs: Long, artworkUri: String?): Playlist = Playlist(
        id = id,
        name = name,
        createdAtEpochMs = created_at_epoch_ms,
        updatedAtEpochMs = updated_at_epoch_ms,
        trackCount = trackCount,
        totalDurationMs = totalDurationMs,
        artworkTrackUri = artworkUri,
    )

    fun encodeSource(source: LibrarySource): String = when (source) {
        is LibrarySource.MediaStore -> "MEDIASTORE"
        is LibrarySource.DocumentTree -> "SAF:${source.treeUri}"
        is LibrarySource.SingleFile -> "FILE:${source.uri}"
    }

    fun decodeSource(encoded: String?): LibrarySource = when {
        encoded == null -> LibrarySource.MediaStore
        encoded == "MEDIASTORE" -> LibrarySource.MediaStore
        encoded.startsWith("SAF:") -> LibrarySource.DocumentTree(encoded.removePrefix("SAF:"))
        encoded.startsWith("FILE:") -> LibrarySource.SingleFile(encoded.removePrefix("FILE:"))
        else -> LibrarySource.MediaStore
    }
}
