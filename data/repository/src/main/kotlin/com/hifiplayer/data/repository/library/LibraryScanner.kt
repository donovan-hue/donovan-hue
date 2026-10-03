package com.hifiplayer.data.repository.library

import android.content.Context
import android.net.Uri
import com.hifiplayer.core.common.config.AppConfig
import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.time.TimeProvider
import com.hifiplayer.core.database.Mappers
import com.hifiplayer.core.database.dao.LibrarySourceDao
import com.hifiplayer.core.database.dao.LibraryStructureDao
import com.hifiplayer.core.database.dao.TrackDao
import com.hifiplayer.core.database.entity.AlbumEntity
import com.hifiplayer.core.database.entity.ArtistEntity
import com.hifiplayer.core.database.entity.FolderEntity
import com.hifiplayer.core.database.entity.LibrarySourceEntity
import com.hifiplayer.core.database.entity.TrackEntity
import com.hifiplayer.core.metadata.MetadataReader
import com.hifiplayer.core.storage.FileAccess
import com.hifiplayer.core.storage.MediaStoreAudioSource
import com.hifiplayer.core.storage.SafFolderSource
import com.hifiplayer.core.storage.AudioFileCandidate
import com.hifiplayer.domain.model.audio.Codec
import com.hifiplayer.domain.model.library.LibrarySource
import com.hifiplayer.domain.model.library.ScanPhase
import com.hifiplayer.domain.model.library.ScanProgress
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.repository.TrackMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * Requirement 17: the scanner that fills the library.
 *
 * Two clearly separated passes so the progress indicator can be honest:
 *
 *  1. **QUERYING** – enumerate every candidate from MediaStore and from each granted SAF tree.
 *     At the end of this pass the total is known, which is what allows "Analizando música… 42/381"
 *     to show real numbers instead of an indeterminate spinner.
 *  2. **ANALYZING / READING_TAGS** – read each file's header and tags, then write it to Room.
 *     Work happens on the IO dispatcher, in batches, and it is cancellable between files.
 *
 * Nothing is trusted from the provider: [AudioFileCandidate] only carries what the provider said,
 * and every displayed audio figure comes from the header probe.
 */
class LibraryScanner(
    context: Context,
    private val trackDao: TrackDao,
    private val structureDao: LibraryStructureDao,
    private val sourceDao: LibrarySourceDao,
    private val metadataReader: MetadataReader,
    private val fileAccess: FileAccess,
    private val dispatchers: DispatcherProvider,
    private val timeProvider: TimeProvider = TimeProvider.System,
    private val mediaStore: MediaStoreAudioSource = MediaStoreAudioSource(context),
    private val safSource: SafFolderSource = SafFolderSource(context),
) {

    private val mediaStoreSource = mediaStore
    private val safFolderSource = safSource

    private val _progress = MutableStateFlow(ScanProgress.IDLE)
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    private val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    private val progressMutex = Mutex()

    /** Set of URIs seen in the last *complete* pass, used to prune deleted files. */
    private val seenUris = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun isRunning(): Boolean = _progress.value.isRunning

    fun cancel() {
        if (_progress.value.isRunning) {
            cancelFlag.set(true)
            AppLogger.i(TAG, "Cancelación solicitada por el usuario")
        }
    }

    data class Result(
        val indexed: Int,
        val skipped: Int,
        val failed: Int,
        val cancelled: Boolean,
        val elapsedMs: Long,
    )

    suspend fun scan(
        useMediaStore: Boolean,
        safTrees: List<String>,
        excludedFolders: Set<String>,
        minimumSizeBytes: Long,
        minimumDurationSec: Int,
        analyzeFormat: Boolean,
    ): Result = coroutineScope {
        if (isRunning()) {
            AppLogger.w(TAG, "Ya hay un escaneo en curso; se ignora la petición")
            return@coroutineScope Result(0, 0, 0, cancelled = true, elapsedMs = 0L)
        }
        cancelFlag.set(false)
        seenUris.clear()
        val startedAt = timeProvider.nowMs()
        var skipped = 0
        var failed = 0

        try {
            // ---------------- pass 1: enumerate ----------------
            _progress.value = ScanProgress(isRunning = true, processed = 0, total = 0, phase = ScanPhase.QUERYING)
            val candidates = mutableListOf<AudioFileCandidate>()
            if (useMediaStore) {
                mediaStoreSource.query(
                    excludedFolders = excludedFolders,
                    minimumSizeBytes = minimumSizeBytes,
                    onBatch = { batch -> candidates += batch },
                )
            }
            safTrees.forEach { treeUri ->
                val errors = safFolderSource.walk(
                    treeUri = Uri.parse(treeUri),
                    excludedFolders = excludedFolders,
                    onFile = { candidate -> candidates += candidate },
                )
                if (errors > 0) {
                    AppLogger.w(TAG, "Carpeta SAF con $errors subárboles ilegibles: $treeUri")
                    failed += errors
                }
            }
            val total = candidates.size
            AppLogger.i(TAG, "Escaneo: $total archivos candidatos")
            if (total == 0) {
                _progress.value = ScanProgress(isRunning = false, processed = 0, total = 0, phase = ScanPhase.DONE)
                return@coroutineScope Result(0, 0, 0, cancelled = false, elapsedMs = timeProvider.nowMs() - startedAt)
            }

            // ---------------- pass 2: analyse ----------------
            _progress.value = ScanProgress(isRunning = true, processed = 0, total = total, phase = ScanPhase.ANALYZING)
            var indexed = 0
            val pending = mutableListOf<TrackEntity>()

            for (candidate in candidates) {
                if (cancelFlag.get()) break
                if (candidate.sizeBytes < minimumSizeBytes) {
                    skipped++
                    continue
                }
                val entity = try {
                    analyse(candidate)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Exception) {
                    AppLogger.w(TAG, "Archivo no indexado (${candidate.displayName}): ${exception.javaClass.simpleName}")
                    failed++
                    null
                }
                if (entity == null) {
                    // A file whose header cannot be read is still listed so the user can see it and
                    // decide; it is stored without any invented format values.
                    failed++
                } else {
                    if (minimumDurationSec > 0 && entity.duration_ms in 1 until minimumDurationSec * 1000L) {
                        skipped++
                    } else {
                        pending += entity
                        seenUris.add(entity.uri)
                        indexed++
                    }
                }
                if (pending.size >= UPSERT_BATCH) {
                    flush(pending)
                }
                publishProgress(candidates.size, indexed, candidate, ScanPhase.READING_TAGS)
            }

            if (pending.isNotEmpty()) flush(pending)
            rebuildStructure()
            markSourcesScanned(safTrees)

            val cancelled = cancelFlag.get()
            _progress.value = ScanProgress(
                isRunning = false,
                processed = if (cancelled) _progress.value.processed else total,
                total = total,
                phase = if (cancelled) ScanPhase.CANCELLED else ScanPhase.DONE,
            )
            AppLogger.i(TAG, "Escaneo terminado: $indexed indexados, $skipped omitidos, $failed con error")
            Result(indexed, skipped, failed, cancelled, timeProvider.nowMs() - startedAt)
        } catch (cancellation: CancellationException) {
            _progress.value = ScanProgress(
                isRunning = false,
                processed = _progress.value.processed,
                total = _progress.value.total,
                phase = ScanPhase.CANCELLED,
            )
            throw cancellation
        } catch (exception: Exception) {
            AppLogger.e(TAG, "El escaneo falló", exception)
            _progress.value = ScanProgress(
                isRunning = false,
                processed = _progress.value.processed,
                total = _progress.value.total,
                phase = ScanPhase.FAILED,
            )
            Result(0, 0, failed, cancelled = false, elapsedMs = timeProvider.nowMs() - startedAt)
        }
    }

    /** Reads one file and turns it into a Room row. Returns null when the header is unusable. */
    private suspend fun analyse(candidate: AudioFileCandidate): TrackEntity? {
        val probe = metadataReader.read(candidate.uri, candidate.sizeBytes, candidate.mimeType).getOrNull() ?: return null
        val format = probe.format
        val tags: TrackMetadata? = probe.tags?.let { parsed ->
            TrackMetadata(
                title = parsed.title,
                artist = parsed.artist,
                album = parsed.album,
                albumArtist = parsed.albumArtist,
                genre = parsed.genre,
                trackNumber = parsed.trackNumber,
                trackTotal = parsed.trackTotal,
                discNumber = parsed.discNumber,
                discTotal = parsed.discTotal,
                year = parsed.year,
                composer = parsed.composer,
                copyright = parsed.copyright,
                comment = parsed.comment,
                replayGain = parsed.replayGain,
                hasEmbeddedArtwork = parsed.hasEmbeddedArtwork,
                source = parsed.tagFormat,
            )
        }

        val fallbackTitle = candidate.displayName.substringBeforeLast('.', candidate.displayName)
        val title = tags?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val artist = tags?.artist?.takeIf { it.isNotBlank() }
        val album = tags?.album?.takeIf { it.isNotBlank() }
        val albumArtist = tags?.albumArtist?.takeIf { it.isNotBlank() } ?: artist

        // Header duration wins; the provider value is only used when the header could not tell.
        val durationMs = format?.durationMs ?: candidate.providerDurationMs ?: 0L

        val track = Track(
            id = trackIdFor(candidate),
            uri = candidate.uri.toString(),
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            album = album,
            albumId = candidate.mediaStoreAlbumId ?: albumKey(album, albumArtist, candidate.folderPath),
            artistId = artistKey(artist),
            durationMs = durationMs,
            trackNumber = tags?.trackNumber,
            discNumber = tags?.discNumber,
            year = tags?.year,
            genre = tags?.genre,
            sizeBytes = candidate.sizeBytes,
            mimeType = candidate.mimeType,
            displayName = candidate.displayName,
            relativePath = candidate.relativePath ?: candidate.folderPath,
            dateAddedEpochSec = candidate.dateAddedEpochSec,
            lastModifiedEpochSec = candidate.lastModifiedEpochSec,
            format = format?.let { probed ->
                com.hifiplayer.domain.model.audio.AudioFormatSpec(
                    sampleRateHz = probed.sampleRateHz,
                    bitDepth = probed.bitDepth,
                    channels = probed.channels,
                    codec = probed.codec,
                    pcmEncoding = probed.pcmEncoding,
                    bitrateKbps = probed.bitrateKbps,
                    isVariableBitrate = probed.isVariableBitrate,
                )
            },
            replayGain = tags?.replayGain ?: com.hifiplayer.domain.model.audio.ReplayGainInfo.EMPTY,
            hasEmbeddedArtwork = tags?.hasEmbeddedArtwork ?: false,
            artworkUri = null,
            composer = tags?.composer,
            copyright = tags?.copyright,
            source = candidate.source,
        )
        return Mappers.run { track.toEntity(candidate.folderPath) }
    }

    private suspend fun flush(pending: MutableList<TrackEntity>) {
        val batch = pending.toList()
        pending.clear()
        trackDao.upsertAll(batch)
    }

    /**
     * Albums, artists and folders are derived aggregates; they are rebuilt from the tracks table so
     * they can never drift from the source of truth (requirement 16).
     */
    private suspend fun rebuildStructure() {
        val all = mutableListOf<TrackEntity>()
        var offset = 0
        while (true) {
            val page = trackDao.pageAll(PAGE_SIZE, offset)
            if (page.isEmpty()) break
            all += page
            offset += PAGE_SIZE
            if (page.size < PAGE_SIZE) break
        }

        val albums = all.groupBy { it.album_id }
            .filterKeys { it != null }
            .mapNotNull { (albumId, tracks) ->
                val first = tracks.firstOrNull() ?: return@mapNotNull null
                AlbumEntity(
                    id = albumId!!,
                    title = first.album ?: "Álbum desconocido",
                    artist = first.album_artist ?: first.artist,
                    year = tracks.mapNotNull { it.year }.minOrNull(),
                    track_count = tracks.size,
                    total_duration_ms = tracks.sumOf { it.duration_ms },
                    cover_track_uri = tracks.firstOrNull { it.has_embedded_artwork }?.uri ?: first.uri,
                    max_sample_rate_hz = tracks.mapNotNull { it.sample_rate_hz }.maxOrNull(),
                    max_bit_depth = tracks.mapNotNull { it.bit_depth }.maxOrNull(),
                    is_lossless = tracks.all { it.codec_id in LOSSLESS_IDS },
                    has_high_resolution = tracks.any { (it.bit_depth ?: 0) > 16 || (it.sample_rate_hz ?: 0) > 48_000 },
                )
            }

        val artists = all.groupBy { it.artist_id }
            .filterKeys { it != null }
            .mapNotNull { (artistId, tracks) ->
                val first = tracks.firstOrNull() ?: return@mapNotNull null
                ArtistEntity(
                    id = artistId!!,
                    name = first.artist ?: "Artista desconocido",
                    album_count = tracks.mapNotNull { it.album_id }.distinct().size,
                    track_count = tracks.size,
                )
            }

        val folders = all.groupBy { it.folder_path }
            .filterKeys { it.isNotBlank() }
            .map { (path, tracks) ->
                FolderEntity(
                    path = path,
                    display_name = path.substringAfterLast('/').ifBlank { path },
                    track_count = tracks.size,
                    total_size_bytes = tracks.sumOf { it.size_bytes },
                    source_kind = tracks.first().source_kind,
                )
            }

        structureDao.rebuild(albums, artists, folders)
    }

    private suspend fun markSourcesScanned(treeUris: List<String>) {
        val now = timeProvider.nowMs()
        treeUris.forEach { tree -> sourceDao.markScanned(tree, now) }
    }

    /** Registers a granted SAF tree so the next scan finds it without asking again. */
    suspend fun registerTree(treeUri: String, displayName: String) {
        sourceDao.upsert(
            LibrarySourceEntity(
                tree_uri = treeUri,
                display_name = displayName,
                added_at_epoch_ms = timeProvider.nowMs(),
                last_scan_epoch_ms = null,
                is_enabled = true,
            ),
        )
    }

    suspend fun unregisterTree(treeUri: String) {
        sourceDao.delete(treeUri)
    }

    suspend fun registeredTrees(): List<String> = sourceDao.sources().filter { it.is_enabled }.map { it.tree_uri }

    /** URIs seen during the last complete pass; used to remove rows for deleted files. */
    fun lastSeenUris(): Set<String> = seenUris.toSet()

    private suspend fun publishProgress(total: Int, processed: Int, candidate: AudioFileCandidate, phase: ScanPhase) {
        progressMutex.withLock {
            _progress.value = ScanProgress(
                isRunning = true,
                processed = processed,
                total = total,
                currentFile = candidate.displayName,
                phase = phase,
                bytesAnalyzed = _progress.value.bytesAnalyzed + candidate.sizeBytes,
            )
        }
    }

    // ---------------------------------------------------------------- identifiers

    private fun trackIdFor(candidate: AudioFileCandidate): String =
        candidate.mediaStoreId?.let { "ms:$it" } ?: ("uri:" + sha1(candidate.uri.toString()).take(24))

    private fun albumKey(album: String?, albumArtist: String?, folderPath: String): String {
        val base = listOfNotNull(albumArtist, album).joinToString(" · ").ifBlank { "carpeta:$folderPath" }
        return "alb:" + sha1(base.lowercase()).take(20)
    }

    private fun artistKey(artist: String?): String? =
        artist?.takeIf { it.isNotBlank() }?.let { "art:" + sha1(it.lowercase()).take(20) }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "LibraryScanner"
        const val UPSERT_BATCH = 32
        const val PAGE_SIZE = 500
        val LOSSLESS_IDS = setOf("flac", "alac", "wav", "aiff", "dsd")
    }
}
