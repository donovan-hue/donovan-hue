package com.hifiplayer.presentation.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.ext.formatDuration
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.logging.Logger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.onFailure
import com.hifiplayer.core.common.time.TimeProvider
import com.hifiplayer.domain.model.playback.PlaybackState
import com.hifiplayer.domain.repository.QueueRepository
import com.hifiplayer.domain.usecase.playback.ObservePlaybackStateUseCase
import com.hifiplayer.domain.usecase.playback.RestoreQueueUseCase
import com.hifiplayer.domain.usecase.playback.SaveQueueUseCase
import com.hifiplayer.domain.usecase.queue.ClearQueueUseCase
import com.hifiplayer.domain.usecase.queue.GetSavedQueueUseCase
import com.hifiplayer.domain.usecase.queue.MoveQueueItemUseCase
import com.hifiplayer.domain.usecase.queue.PlayQueueIndexUseCase
import com.hifiplayer.domain.usecase.queue.RemoveFromQueueUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Queue / Up Next (requirement 27; Rule 47 step 5).
 *
 * Everything here is a thin translation of real operations: play an index, remove one, move one,
 * clear, save and restore. Reordering is applied to the engine immediately on drop, so the UI can
 * never show one order while the player uses another.
 */
class QueueViewModel(
    private val observePlaybackState: ObservePlaybackStateUseCase,
    private val playQueueIndex: PlayQueueIndexUseCase,
    private val removeFromQueue: RemoveFromQueueUseCase,
    private val moveQueueItem: MoveQueueItemUseCase,
    private val clearQueue: ClearQueueUseCase,
    private val saveQueue: SaveQueueUseCase,
    private val restoreQueue: RestoreQueueUseCase,
    private val getSavedQueue: GetSavedQueueUseCase,
    private val timeProvider: TimeProvider,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger = AppLogger.logger(),
) : ViewModel() {

    private val savedQueue = MutableStateFlow<QueueRepository.SavedQueue?>(null)

    private val clearingArmed = MutableStateFlow(false)

    private val message = MutableStateFlow<Message?>(null)

    private val busy = MutableStateFlow(false)

    val state: StateFlow<QueueUiState> = combine(
        observePlaybackState(),
        savedQueue,
        combine(clearingArmed, message, busy) { armed, msg, isBusy -> Triple(armed, msg, isBusy) },
    ) { playback, saved, (armed, msg, isBusy) ->
        buildState(playback, saved, armed, msg, isBusy)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = QueueUiState(),
    )

    init {
        refreshSavedQueue()
    }

    // ------------------------------------------------------------------ actions

    /** Plays the queue item at [index] (absolute index in the engine queue). */
    fun onPlayIndex(index: Int) = runAction { playQueueIndex(index) }

    fun onRemove(index: Int) = runAction {
        clearingArmed.value = false
        removeFromQueue(index)
    }

    /**
     * Applies a drag-and-drop reorder.
     *
     * The move is sent to the engine with the absolute indices the screen reported; nothing is kept
     * locally, so what the user sees and what will play cannot drift apart.
     */
    fun onMove(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        runAction { moveQueueItem(fromIndex, toIndex) }
    }

    fun onClearRequested() {
        clearingArmed.value = true
    }

    fun onClearCancelled() {
        clearingArmed.value = false
    }

    fun onClearConfirmed() = runAction {
        clearingArmed.value = false
        clearQueue()
    }

    fun onSave() = runAction {
        busy.value = true
        val outcome = saveQueue()
        busy.value = false
        if (outcome.isSuccess) message.value = Message("Cola guardada.", isError = false)
        // The outcome is returned so the shared handler still reports a failure if there was one.
        outcome
    }

    fun onRestore() = runAction {
        busy.value = true
        val outcome = restoreQueue()
        busy.value = false
        val restored = outcome.getOrNull() == true
        if (outcome.isSuccess) {
            message.value = if (restored) {
                Message("Cola restaurada.", isError = false)
            } else {
                Message("No había una cola guardada que se pudiera restaurar.", isError = true)
            }
        }
        outcome
    }

    fun onDismissMessage() {
        message.value = null
    }

    // ------------------------------------------------------------------ internals

    private fun runAction(block: suspend () -> Outcome<*>) {
        viewModelScope.launch {
            block().onFailure { error ->
                logger.w(TAG, "Operación de cola fallida: ${error.code}")
                message.value = Message(error.userMessage, isError = true)
            }
            refreshSavedQueue()
        }
    }

    /** After every mutation the persisted snapshot is re-read, so "Restaurar" never lies. */
    private fun refreshSavedQueue() {
        viewModelScope.launch {
            savedQueue.value = getSavedQueue()
        }
    }

    private fun buildState(
        playback: PlaybackState,
        saved: QueueRepository.SavedQueue?,
        armed: Boolean,
        currentMessage: Message?,
        isBusy: Boolean,
    ): QueueUiState {
        val rows = playback.queue.mapIndexed { index, item ->
            QueueRow(
                index = index,
                item = item,
                isCurrent = index == playback.currentIndex,
                isPast = playback.currentIndex >= 0 && index < playback.currentIndex,
            )
        }
        val totalDuration = playback.queue.sumOf { it.durationMs }
        return QueueUiState(
            rows = rows,
            currentIndex = playback.currentIndex,
            currentRow = rows.getOrNull(playback.currentIndex),
            upNextRows = rows.filter { !it.isCurrent && !it.isPast },
            pastRows = rows.filter { it.isPast },
            isEmpty = rows.isEmpty(),
            originLabel = playback.queueOrigin.displayName,
            shuffleEnabled = playback.shuffleEnabled,
            repeatLabel = when (playback.repeatMode) {
                com.hifiplayer.domain.model.playback.RepeatMode.OFF -> "Repetir: apagado"
                com.hifiplayer.domain.model.playback.RepeatMode.ALL -> "Repetir: toda la cola"
                com.hifiplayer.domain.model.playback.RepeatMode.ONE -> "Repetir: esta pista"
            },
            totalDurationLabel = if (rows.isEmpty()) null else formatDuration(totalDuration),
            savedQueueAvailable = saved != null && saved.items.isNotEmpty(),
            savedQueueSummary = saved?.let { describeSaved(it) },
            clearingArmed = armed,
            busy = isBusy,
            message = currentMessage?.text,
            messageIsError = currentMessage?.isError == true,
        )
    }

    private fun describeSaved(saved: QueueRepository.SavedQueue): String {
        val ageMs = (timeProvider.nowMs() - saved.savedAtEpochMs).coerceAtLeast(0L)
        val age = when {
            ageMs < MINUTE_MS -> "hace unos segundos"
            ageMs < HOUR_MS -> "hace ${ageMs / MINUTE_MS} min"
            ageMs < DAY_MS -> "hace ${ageMs / HOUR_MS} h"
            else -> "hace ${ageMs / DAY_MS} días"
        }
        val tracks = if (saved.items.size == 1) "1 pista" else "${saved.items.size} pistas"
        val shuffle = if (saved.shuffleEnabled) " · aleatorio" else ""
        return "Guardada $age · $tracks · origen: ${saved.origin.displayName}$shuffle"
    }

    private data class Message(val text: String, val isError: Boolean)

    private companion object {
        const val TAG = "Queue"
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
    }
}
