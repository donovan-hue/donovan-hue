package com.hifiplayer.data.repository.library

import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.outcomeOf
import com.hifiplayer.core.common.time.TimeProvider
import com.hifiplayer.core.database.entity.QueueItemEntity
import com.hifiplayer.core.database.entity.QueueMetaEntity
import com.hifiplayer.data.repository.local.DatabaseProvisioning
import com.hifiplayer.domain.model.playback.QueueItem
import com.hifiplayer.domain.model.playback.QueueOrigin
import com.hifiplayer.domain.repository.QueueRepository
import kotlinx.coroutines.withContext

/**
 * Requirement 27: the queue survives app restarts.
 *
 * The snapshot keeps a *copy* of the display fields (title, artist, format) next to the track id,
 * so the queue can be shown immediately after a cold start, before Room has answered anything else.
 */
class QueueRepositoryImpl(
    private val provisioning: DatabaseProvisioning,
    private val dispatchers: DispatcherProvider,
    private val timeProvider: TimeProvider = TimeProvider.System,
) : QueueRepository {

    private val queueDao = provisioning.queue

    override suspend fun save(queue: QueueRepository.SavedQueue): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            val items = queue.items.mapIndexed { index, item ->
                QueueItemEntity(
                    position = index,
                    track_id = item.trackId,
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    duration_ms = item.durationMs,
                    codec_id = item.codecId,
                    sample_rate_hz = item.sampleRateHz,
                    bit_depth = item.bitDepth,
                    artwork_uri = item.artworkUri,
                )
            }
            val meta = QueueMetaEntity(
                id = 0,
                current_index = queue.currentIndex,
                origin = queue.origin.name,
                shuffle_enabled = queue.shuffleEnabled,
                saved_at_epoch_ms = if (queue.savedAtEpochMs > 0L) queue.savedAtEpochMs else timeProvider.nowMs(),
            )
            queueDao.replaceQueue(items, meta)
        }
    }

    override suspend fun load(): QueueRepository.SavedQueue? = withContext(dispatchers.io) {
        val meta = queueDao.meta() ?: return@withContext null
        val items = queueDao.items()
        if (items.isEmpty()) return@withContext null
        QueueRepository.SavedQueue(
            items = items.sortedBy { it.position }.map { it.toDomain() },
            currentIndex = meta.current_index,
            origin = runCatching { QueueOrigin.valueOf(meta.origin) }.getOrDefault(QueueOrigin.RESTORED),
            shuffleEnabled = meta.shuffle_enabled,
            savedAtEpochMs = meta.saved_at_epoch_ms,
        )
    }

    override suspend fun clear(): Outcome<Unit> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            queueDao.clearItems()
            queueDao.clearMeta()
        }
    }

    private fun QueueItemEntity.toDomain() = QueueItem(
        trackId = track_id,
        title = title,
        artist = artist,
        album = album,
        durationMs = duration_ms,
        codecId = codec_id,
        sampleRateHz = sample_rate_hz,
        bitDepth = bit_depth,
        artworkUri = artwork_uri,
    )

    private companion object {
        const val TAG = "QueueRepository"
    }
}
