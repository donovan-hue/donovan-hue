package com.hifiplayer.domain.repository

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.library.ArtworkSource

/**
 * Requirement 19: artwork with a strict priority order (embedded → cover.jpg → folder.jpg →
 * artwork.jpg), cached, and never decoded at full size for a list row.
 */
interface ArtworkRepository {

    /** Resolves the best artwork available for a track, or null when there is none. */
    suspend fun resolveArtwork(trackId: String, uri: String, folderPath: String?): Outcome<ArtworkSource?>

    /** Cache key for a given source, so the UI can request a specific size. */
    fun cacheKeyFor(source: ArtworkSource): String

    /**
     * Returns the artwork already scaled for [targetPx] (longest side), so the UI never decodes a
     * multi-megabyte original into memory (requirement 20). Results are cached on disk.
     */
    suspend fun bytesFor(source: ArtworkSource, targetPx: Int): Outcome<ByteArray>

    /** Downscaled size actually available for a source, or null when it was never loaded. */
    suspend fun cachedBytes(source: ArtworkSource): ByteArray?

    suspend fun clearArtworkCache(): Outcome<Long>

    suspend fun cacheSizeBytes(): Long
}
