package com.hifiplayer.domain.repository

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.audio.AudioFormatSpec
import com.hifiplayer.domain.model.audio.ReplayGainInfo
import com.hifiplayer.domain.model.library.Track

/**
 * Reading (and later editing) tags and format headers (requirement 18).
 *
 * The golden rule: whatever this returns was *read from the file*, never guessed. Fields that
 * could not be read stay null, and the UI shows "no detectado" instead of inventing values.
 */
interface MetadataRepository {

    /** Parses codec, sample rate, bit depth, channels, bitrate and duration from the header. */
    suspend fun readFormat(uri: String): Outcome<AudioFormatSpec>

    /** Embedded tags, including album artist, composer, copyright and ReplayGain values. */
    suspend fun readTags(uri: String): Outcome<TrackMetadata>

    suspend fun readReplayGain(uri: String): Outcome<ReplayGainInfo>

    /** Enriches a partially-known track (e.g. from MediaStore) with probed data. */
    suspend fun enrich(track: Track): Outcome<Track>

    /** Applies user edits back to the file when the container supports writing. */
    suspend fun writeTags(uri: String, changes: TrackMetadataChanges): Outcome<Unit>

    /** Whether the container of this file can be safely rewritten. */
    suspend fun canWriteTags(uri: String): Boolean
}

/** Raw tag payload as stored in the file. Null means "not present", not "unknown". */
data class TrackMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val year: Int? = null,
    val composer: String? = null,
    val copyright: String? = null,
    val comment: String? = null,
    val replayGain: ReplayGainInfo = ReplayGainInfo.EMPTY,
    val hasEmbeddedArtwork: Boolean = false,
    val source: String = "desconocido",
)

/** Fields the user can edit (requirement 18: "permitir editar metadata posteriormente"). */
data class TrackMetadataChanges(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val composer: String? = null,
)
