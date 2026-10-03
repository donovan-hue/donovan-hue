package com.hifiplayer.data.metadata

import android.net.Uri
import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.metadata.MetadataReader
import com.hifiplayer.core.metadata.ProbedFormat
import com.hifiplayer.core.storage.FileAccess
import com.hifiplayer.domain.model.audio.AudioFormatSpec
import com.hifiplayer.domain.model.audio.ReplayGainInfo
import com.hifiplayer.domain.model.error.MetadataError
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.repository.MetadataRepository
import com.hifiplayer.domain.repository.TrackMetadata
import com.hifiplayer.domain.repository.TrackMetadataChanges
import kotlinx.coroutines.withContext

/**
 * Tag/format reading on top of `core:metadata` (requirement 18).
 *
 * Every value returned here was read from the file itself. When the header cannot be parsed the
 * call fails with a typed [MetadataError] instead of returning invented numbers, and the scanner
 * keeps the track in the library flagged as "not analysed".
 */
class MetadataRepositoryImpl(
    private val fileAccess: FileAccess,
    private val reader: MetadataReader,
    private val dispatchers: DispatcherProvider,
) : MetadataRepository {

    override suspend fun readFormat(uri: String): Outcome<AudioFormatSpec> = withContext(dispatchers.io) {
        val parsedUri = Uri.parse(uri)
        val size = fileAccess.sizeOf(parsedUri)
        when (val result = reader.read(parsedUri, size, null)) {
            is Outcome.Success -> {
                val format = result.value.format
                if (format == null) {
                    Outcome.Failure(MetadataError.CorruptHeader(uri, "la cabecera no declara formato de audio"))
                } else {
                    Outcome.Success(format.toFormatSpec())
                }
            }
            is Outcome.Failure -> result
        }
    }

    override suspend fun readTags(uri: String): Outcome<TrackMetadata> = withContext(dispatchers.io) {
        val parsedUri = Uri.parse(uri)
        val size = fileAccess.sizeOf(parsedUri)
        when (val result = reader.read(parsedUri, size, null)) {
            is Outcome.Success -> {
                val tags = result.value.tags
                if (tags == null) {
                    Outcome.Failure(MetadataError.ParseFailed(uri, "el archivo no contiene etiquetas legibles"))
                } else {
                    Outcome.Success(
                        TrackMetadata(
                            title = tags.title,
                            artist = tags.artist,
                            album = tags.album,
                            albumArtist = tags.albumArtist,
                            genre = tags.genre,
                            trackNumber = tags.trackNumber,
                            trackTotal = tags.trackTotal,
                            discNumber = tags.discNumber,
                            discTotal = tags.discTotal,
                            year = tags.year,
                            composer = tags.composer,
                            copyright = tags.copyright,
                            comment = tags.comment,
                            replayGain = tags.replayGain,
                            hasEmbeddedArtwork = tags.hasEmbeddedArtwork,
                            source = tags.tagFormat,
                        ),
                    )
                }
            }
            is Outcome.Failure -> result
        }
    }

    override suspend fun readReplayGain(uri: String): Outcome<ReplayGainInfo> =
        when (val result = readTags(uri)) {
            is Outcome.Success -> Outcome.Success(result.value.replayGain)
            is Outcome.Failure -> result
        }

    /**
     * Completes a track that came from the provider with what the file actually says.
     *
     * Priority is fixed by the spec: embedded tags win over provider columns, and the header always
     * wins over the duration the provider reported.
     */
    override suspend fun enrich(track: Track): Outcome<Track> = withContext(dispatchers.io) {
        val parsedUri = Uri.parse(track.uri)
        val size = if (track.sizeBytes > 0L) track.sizeBytes else fileAccess.sizeOf(parsedUri)
        when (val result = reader.read(parsedUri, size, track.mimeType)) {
            is Outcome.Success -> {
                val probed = result.value
                val tags = probed.tags
                val format = probed.format?.toFormatSpec()
                val enriched = track.copy(
                    title = tags?.title?.takeIf { it.isNotBlank() } ?: track.title,
                    artist = tags?.artist?.takeIf { it.isNotBlank() } ?: track.artist,
                    album = tags?.album?.takeIf { it.isNotBlank() } ?: track.album,
                    albumArtist = tags?.albumArtist?.takeIf { it.isNotBlank() } ?: track.albumArtist,
                    genre = tags?.genre?.takeIf { it.isNotBlank() } ?: track.genre,
                    year = tags?.year ?: track.year,
                    trackNumber = tags?.trackNumber ?: track.trackNumber,
                    discNumber = tags?.discNumber ?: track.discNumber,
                    composer = tags?.composer ?: track.composer,
                    copyright = tags?.copyright ?: track.copyright,
                    durationMs = probed.format?.durationMs ?: track.durationMs,
                    format = format ?: track.format,
                    replayGain = tags?.replayGain ?: track.replayGain,
                    hasEmbeddedArtwork = tags?.hasEmbeddedArtwork ?: track.hasEmbeddedArtwork,
                )
                Outcome.Success(enriched)
            }
            is Outcome.Failure -> result
        }
    }

    /**
     * Editing tags is a spec feature ("editar metadata posteriormente") but is **not implemented**
     * yet, so it fails loudly instead of pretending to save (rule 46).
     */
    override suspend fun writeTags(uri: String, changes: TrackMetadataChanges): Outcome<Unit> {
        AppLogger.i(TAG, "writeTags solicitado para ${Uri.parse(uri).lastPathSegment}; función pendiente")
        return Outcome.Failure(
            MetadataError.Unknown(uri, "La edición de etiquetas todavía no está implementada en esta versión"),
        )
    }

    override suspend fun canWriteTags(uri: String): Boolean = false

    private companion object {
        const val TAG = "MetadataRepository"
    }
}

/** Converts the header probe result into the domain format description shown in the UI. */
fun ProbedFormat.toFormatSpec(): AudioFormatSpec = AudioFormatSpec(
    sampleRateHz = sampleRateHz,
    bitDepth = bitDepth,
    channels = channels,
    codec = codec,
    pcmEncoding = pcmEncoding,
    bitrateKbps = bitrateKbps,
    isVariableBitrate = isVariableBitrate,
)
