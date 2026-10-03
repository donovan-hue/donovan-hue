package com.hifiplayer.domain.repository

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.playback.QueueItem
import com.hifiplayer.domain.model.playback.QueueOrigin

/**
 * Persistence of the queue so it survives app restarts (requirement 7/27: "cola persistente",
 * "save queue", "restore queue").
 */
interface QueueRepository {

    data class SavedQueue(
        val items: List<QueueItem>,
        val currentIndex: Int,
        val origin: QueueOrigin,
        val shuffleEnabled: Boolean,
        val savedAtEpochMs: Long,
    )

    suspend fun save(queue: SavedQueue): Outcome<Unit>

    suspend fun load(): SavedQueue?

    suspend fun clear(): Outcome<Unit>
}
