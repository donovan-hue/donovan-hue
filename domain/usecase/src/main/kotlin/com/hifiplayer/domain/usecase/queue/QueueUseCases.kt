package com.hifiplayer.domain.usecase.queue

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.playback.QueueInsertPosition
import com.hifiplayer.domain.model.playback.QueueItem
import com.hifiplayer.domain.repository.PlaybackRepository
import com.hifiplayer.domain.repository.QueueRepository

/**
 * QueueManager use cases (requirement 27). The authoritative queue lives in the engine; these
 * use cases are the only way the UI mutates it, so every mutation is validated and observable.
 */
class AddToQueueUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(track: Track): Outcome<Unit> =
        playback.addToQueue(track, QueueInsertPosition.END)
}

class PlayNextUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(track: Track): Outcome<Unit> =
        playback.addToQueue(track, QueueInsertPosition.NEXT)
}

class AddTracksToQueueUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(tracks: List<Track>, next: Boolean = false): Outcome<Unit> =
        playback.addTracksToQueue(tracks, if (next) QueueInsertPosition.NEXT else QueueInsertPosition.END)
}

class RemoveFromQueueUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(index: Int): Outcome<Unit> = playback.removeFromQueue(index)
}

class MoveQueueItemUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(fromIndex: Int, toIndex: Int): Outcome<Unit> =
        playback.moveInQueue(fromIndex, toIndex)
}

class ClearQueueUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(): Outcome<Unit> = playback.clearQueue()
}

class PlayQueueIndexUseCase(private val playback: PlaybackRepository) {
    suspend operator fun invoke(index: Int): Outcome<Unit> = playback.playQueueIndex(index)
}

/** Up Next list for the queue sheet, derived from the same state the player renders. */
class GetUpNextUseCase(private val playback: PlaybackRepository) {
    operator fun invoke(): List<QueueItem> = playback.state.value.upNext
}

/**
 * Reads the saved queue so the UI can offer "Restaurar" only when there is something to restore,
 * and can describe it ("12 pistas, guardada hace 4 min"). Returning the persisted snapshot —
 * instead of a boolean — keeps the screen from guessing what it is about to restore.
 */
class GetSavedQueueUseCase(private val queue: QueueRepository) {
    suspend operator fun invoke(): QueueRepository.SavedQueue? = queue.load()
}
