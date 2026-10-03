package com.hifiplayer.presentation.playback

import com.hifiplayer.core.common.ext.formatDuration
import com.hifiplayer.domain.model.playback.QueueItem

/**
 * One row of the queue.
 *
 * [index] is the real position in the engine queue, not a position inside a section: the screen
 * never has to translate between "row 3 of Up Next" and "item 7 of the queue" before it can ask
 * for a move or a removal.
 */
data class QueueRow(
    val index: Int,
    val item: QueueItem,
    val isCurrent: Boolean,
    val isPast: Boolean,
) {
    val title: String get() = item.title

    /**
     * Artist · album · format, skipping whatever the file did not provide. The technical part only
     * appears when the scanner really measured it (never a placeholder like "— kHz").
     */
    val subtitle: String
        get() = listOfNotNull(
            item.artist?.takeIf { it.isNotBlank() },
            item.album?.takeIf { it.isNotBlank() },
            item.formatLabel.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

    val durationLabel: String get() = formatDuration(item.durationMs)
}

data class QueueUiState(
    val rows: List<QueueRow> = emptyList(),
    val currentIndex: Int = -1,
    val currentRow: QueueRow? = null,
    val upNextRows: List<QueueRow> = emptyList(),
    val pastRows: List<QueueRow> = emptyList(),
    val isEmpty: Boolean = true,
    val originLabel: String = "",
    val shuffleEnabled: Boolean = false,
    val repeatLabel: String = "",
    val totalDurationLabel: String? = null,
    /** True only when a persisted queue really exists in Room. */
    val savedQueueAvailable: Boolean = false,
    val savedQueueSummary: String? = null,
    /** Two-step confirmation for "vaciar": no dialogs in this app, and no silent destruction. */
    val clearingArmed: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)
