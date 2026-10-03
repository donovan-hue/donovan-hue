package com.hifiplayer.domain.usecase.library

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.library.ArtworkSource
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.repository.ArtworkRepository

/**
 * Artwork has a strict priority order (requirement 19): embedded → cover.jpg → folder.jpg →
 * artwork.jpg. The use cases exist so the UI never has to know that order, nor touch storage.
 */
class ResolveTrackArtworkUseCase(private val artwork: ArtworkRepository) {
    suspend operator fun invoke(track: Track): Outcome<ArtworkSource?> = artwork.resolveArtwork(
        trackId = track.id,
        uri = track.uri,
        folderPath = track.relativePath,
    )
}

/** Bytes already scaled to [targetPx] on the longest side, so large originals are never decoded. */
class LoadArtworkBytesUseCase(private val artwork: ArtworkRepository) {
    suspend operator fun invoke(source: ArtworkSource, targetPx: Int): Outcome<ByteArray> =
        artwork.bytesFor(source, targetPx)
}

class ClearArtworkCacheUseCase(private val artwork: ArtworkRepository) {
    suspend operator fun invoke(): Outcome<Long> = artwork.clearArtworkCache()
}

class GetArtworkCacheSizeUseCase(private val artwork: ArtworkRepository) {
    suspend operator fun invoke(): Long = artwork.cacheSizeBytes()
}
