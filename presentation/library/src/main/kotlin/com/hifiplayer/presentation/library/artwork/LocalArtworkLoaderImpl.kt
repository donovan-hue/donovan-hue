package com.hifiplayer.presentation.library.artwork

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.logging.Logger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.usecase.library.LoadArtworkBytesUseCase
import com.hifiplayer.domain.usecase.library.ResolveTrackArtworkUseCase
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The real [ArtworkLoader].
 *
 * Requirements 19 and 37, in code: covers follow the fixed priority order (embedded, then cover.jpg,
 * folder.jpg, artwork.jpg) because that order lives in the repository; they are always requested
 * already scaled to the size the UI needs, so a 4000x4000 scan is never decoded into memory; and the
 * decoded bitmaps are cached by (source, size) so scrolling back up does not decode anything twice.
 *
 * The cache is a bounded linked map rather than an unbounded one: artwork for a library of thousands
 * of tracks must not grow without limit on a phone.
 */
class LocalArtworkLoaderImpl(
    private val resolveArtwork: ResolveTrackArtworkUseCase,
    private val loadArtworkBytes: LoadArtworkBytesUseCase,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val logger: Logger = AppLogger.logger(),
) : ArtworkLoader {

    /**
     * The budget is in bytes, and the size of an entry is what it really occupies: width × height × 4.
     * A list cover (44 px) costs ~7 KB and the Now Playing artwork (320 px) ~410 KB, so counting
     * entries would have been off by two orders of magnitude (requirement 36).
     */
    private val cache = ArtworkCache<ImageBitmap>(maxBytes = maxBytes) { bitmap ->
        bitmap.width.toLong() * bitmap.height.toLong() * BYTES_PER_PIXEL
    }

    private val cacheMutex = Mutex()

    /** One decode per key at a time: a fast scroll must not decode the same cover twice in parallel. */
    private val inFlight = ConcurrentHashMap<String, Mutex>()

    override suspend fun load(track: Track, targetPx: Int): ImageBitmap? {
        val source = resolveArtwork(track).getOrNull() ?: return null
        val key = "${source.key}@$targetPx"
        cached(key)?.let { return it }
        val bytes = when (val outcome = loadArtworkBytes(source, targetPx)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> {
                logger.w(TAG, "Sin portada para ${track.id}: ${outcome.error.code}")
                return null
            }
        }
        return decodeAndCache(key, bytes)
    }

    /**
     * Cover for something that is not a single track (an album shelf entry).
     *
     * [cacheKey] identifies the album, and the URI is the representative file: the repository reads
     * the embedded picture from that file, or a sidecar next to it.
     */
    override suspend fun loadUri(uri: String, cacheKey: String, targetPx: Int): ImageBitmap? {
        val key = "$cacheKey@$targetPx"
        cached(key)?.let { return it }
        val track = representativeTrack(uri, cacheKey)
        val source = resolveArtwork(track).getOrNull() ?: return null
        val bytes = when (val outcome = loadArtworkBytes(source, targetPx)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> {
                logger.w(TAG, "Sin portada para $cacheKey: ${outcome.error.code}")
                return null
            }
        }
        return decodeAndCache(key, bytes)
    }

    /**
     * A track-shaped value used only to ask the repository for artwork.
     *
     * It carries the URI because that is what the resolver reads; every other field is irrelevant to
     * artwork resolution and is left empty on purpose instead of invented.
     */
    private fun representativeTrack(uri: String, cacheKey: String): Track = Track(
        id = cacheKey,
        uri = uri,
        title = "",
        artist = null,
        albumArtist = null,
        album = null,
        albumId = null,
        artistId = null,
        durationMs = 0L,
        trackNumber = null,
        discNumber = null,
        year = null,
        genre = null,
        sizeBytes = 0L,
        mimeType = null,
        displayName = "",
        relativePath = null,
        dateAddedEpochSec = 0L,
        lastModifiedEpochSec = 0L,
        format = null,
    )

    private suspend fun cached(key: String): ImageBitmap? = cacheMutex.withLock { cache.get(key) }

    private suspend fun decodeAndCache(key: String, bytes: ByteArray): ImageBitmap? {
        val lock = inFlight.computeIfAbsent(key) { Mutex() }
        return lock.withLock {
            cacheMutex.withLock { cache.get(key) }?.let { return@withLock it }
            val decoded = withContext(Dispatchers.Default) {
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                    .onFailure { logger.w(TAG, "Portada ilegible para $key: ${it.javaClass.simpleName}") }
                    .getOrNull()
            }
            if (decoded != null) {
                cacheMutex.withLock { cache.put(key, decoded) }
            }
            decoded
        }
    }

    /** Frees the decoded covers. Called when the app needs the memory back. */
    suspend fun clear() {
        cacheMutex.withLock { cache.clear() }
    }

    /** Decoded cover bytes held right now; the settings screen shows this figure. */
    suspend fun sizeBytes(): Long = cacheMutex.withLock { cache.sizeBytes }

    private companion object {
        const val TAG = "Artwork"

        /** ARGB_8888, which is what a decoded cover occupies. */
        const val BYTES_PER_PIXEL = 4L

        /** 16 MB of decoded covers: several screens of list thumbnails plus the current artwork. */
        const val DEFAULT_MAX_BYTES = 16L * 1024L * 1024L
    }
}
