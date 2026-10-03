package com.hifiplayer.presentation.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.designsystem.component.HiFiBadge
import com.hifiplayer.core.designsystem.component.HiFiEmptyState
import com.hifiplayer.core.designsystem.component.HiFiInfoRow
import com.hifiplayer.core.designsystem.component.HiFiSectionHeader
import com.hifiplayer.core.designsystem.component.HiFiTopBar
import com.hifiplayer.core.designsystem.component.HiFiTransportButton
import com.hifiplayer.core.designsystem.theme.LocalHiFiDimens
import com.hifiplayer.core.designsystem.theme.TechLabelStyle

/**
 * Queue / Up Next (requirement 27; Rule 47 step 5).
 *
 * The screen shows three real sections and mutates the queue exclusively through callbacks that
 * end in engine operations: playing an index, removing one, reordering by dragging, clearing,
 * saving and restoring. Nothing is reordered only on screen.
 *
 * Reordering is offered inside "A continuación" because that is the region the user is really
 * curating: dragging an item into the already-played part (or above the current track) would mean
 * changing what is playing now, which is not what a drag in this list should ever do.
 */
@Composable
fun QueueScreen(
    state: QueueUiState,
    onBack: () -> Unit,
    onPlayIndex: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClearRequested: () -> Unit,
    onClearCancelled: () -> Unit,
    onClearConfirmed: () -> Unit,
    onSave: () -> Unit,
    onRestore: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalHiFiDimens.current
    val density = LocalDensity.current
    val rowHeight = dimens.queueRowHeight
    val rowHeightPx = with(density) { rowHeight.toPx() }

    // Local mirror of Up Next, used *only* while a drag is in progress so the list follows the
    // finger at 60 fps. On drop the new order is sent to the engine, and the mirror is discarded.
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var draggingPosition by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val localOrder = remember { mutableStateOf<SnapshotStateList<QueueRow>?>(null) }

    LaunchedEffect(state.upNextRows) {
        if (draggingIndex == null) localOrder.value = state.upNextRows.toMutableStateList()
    }

    Column(modifier = modifier.fillMaxSize()) {
        HiFiTopBar(
            title = "Cola de reproducción",
            subtitle = listOfNotNull(
                state.originLabel.takeIf { it.isNotBlank() },
                state.rows.size.takeIf { it > 0 }?.let { if (it == 1) "1 pista" else "$it pistas" },
                state.totalDurationLabel,
            ).joinToString(" · ").takeIf { it.isNotBlank() },
            onBack = onBack,
            actions = {
                HiFiTransportButton(
                    icon = Icons.Filled.SaveAlt,
                    contentDescription = "Guardar la cola",
                    onClick = onSave,
                    enabled = !state.isEmpty && !state.busy,
                )
                HiFiTransportButton(
                    icon = Icons.Filled.Restore,
                    contentDescription = "Restaurar la cola guardada",
                    onClick = onRestore,
                    enabled = state.savedQueueAvailable && !state.busy,
                )
            },
        )

        if (state.isEmpty) {
            HiFiEmptyState(
                icon = Icons.Filled.PlaylistPlay,
                title = "La cola está vacía",
                message = "Cuando reproduzcas algo —una carpeta, un álbum o una lista— la cola aparecerá " +
                    "aquí y podrás reordenarla arrastrando.",
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            state.currentRow?.let { current ->
                item(key = "current") {
                    CurrentItemCard(row = current, onOpen = { onPlayIndex(current.index) })
                }
            }

            if (state.pastRows.isNotEmpty()) {
                item(key = "past-header") {
                    // No action here: the past is shown for context, and dragging inside it would
                    // be a way to change what plays next without saying so.
                    HiFiSectionHeader(title = "Ya reproducidas (${state.pastRows.size})")
                }
                items(state.pastRows, key = { "past-${it.index}" }) { row ->
                    QueueItemRow(
                        row = row,
                        onPlay = { onPlayIndex(row.index) },
                        onRemove = { onRemove(row.index) },
                        draggable = false,
                        onDragStart = {},
                        onDrag = {},
                        onDragEnd = {},
                        onDragCancel = {},
                    )
                }
            }

            item(key = "upnext-header") {
                HiFiSectionHeader(
                    title = "A continuación (${state.upNextRows.size})",
                    trailingLabel = if (state.clearingArmed) null else "Vaciar cola",
                    onTrailingClick = if (state.clearingArmed) null else onClearRequested,
                )
            }

            if (state.clearingArmed) {
                item(key = "clear-confirm") {
                    ClearConfirmation(
                        count = state.rows.size,
                        onCancel = onClearCancelled,
                        onConfirm = onClearConfirmed,
                    )
                }
            }

            if (state.upNextRows.isEmpty()) {
                item(key = "upnext-empty") {
                    Text(
                        text = "No queda nada después de esta pista. La reproducción se detendrá al terminar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 8.dp),
                    )
                }
            } else {
                val ordered = localOrder.value ?: state.upNextRows
                items(ordered, key = { "next-${it.index}" }) { row ->
                    val isDragging = draggingIndex == row.index
                    QueueItemRow(
                        row = row,
                        onPlay = { onPlayIndex(row.index) },
                        onRemove = { onRemove(row.index) },
                        draggable = true,
                        modifier = Modifier
                            .height(rowHeight)
                            .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                            .background(
                                if (isDragging) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    Color.Transparent
                                },
                            ),
                        onDragStart = {
                            val list = localOrder.value ?: state.upNextRows.toMutableStateList().also {
                                localOrder.value = it
                            }
                            draggingIndex = row.index
                            draggingPosition = list.indexOfFirst { it.index == row.index }
                            dragOffset = 0f
                        },
                        onDrag = { deltaY ->
                            val list = localOrder.value ?: return@QueueItemRow
                            val position = draggingPosition
                            if (position < 0) return@QueueItemRow
                            dragOffset += deltaY
                            // The finger offset is converted into a number of rows; when the dragged
                            // item actually changes position, the offset is rebased so the item stays
                            // under the finger instead of jumping.
                            val shift = (dragOffset / rowHeightPx).roundToInt()
                            val target = (position + shift).coerceIn(0, list.lastIndex)
                            if (target != position) {
                                val moved = list.removeAt(position)
                                list.add(target, moved)
                                dragOffset -= (target - position) * rowHeightPx
                                draggingPosition = target
                            }
                        },
                        onDragEnd = {
                            val list = localOrder.value
                            val draggedId = draggingIndex
                            if (list != null && draggedId != null) {
                                // Both indices are translated from "position inside Up Next" to
                                // "absolute queue index" before they reach the engine.
                                val fromPosition = state.upNextRows.indexOfFirst { it.index == draggedId }
                                val toPosition = list.indexOfFirst { it.index == draggedId }
                                if (fromPosition >= 0 && toPosition >= 0 && fromPosition != toPosition) {
                                    onMove(state.upNextRows[fromPosition].index, state.upNextRows[toPosition].index)
                                }
                            }
                            draggingIndex = null
                            draggingPosition = -1
                            dragOffset = 0f
                            localOrder.value = null
                        },
                        onDragCancel = {
                            draggingIndex = null
                            draggingPosition = -1
                            dragOffset = 0f
                            localOrder.value = null
                        },
                    )
                }
            }

            if (state.savedQueueSummary != null) {
                item(key = "saved") {
                    Text(
                        text = state.savedQueueSummary,
                        style = TechLabelStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 10.dp),
                    )
                }
            }

            if (state.message != null) {
                item(key = "message") {
                    QueueMessage(
                        text = state.message,
                        isError = state.messageIsError,
                        onDismiss = onDismissMessage,
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentItemCard(row: QueueRow, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = LocalHiFiDimens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HiFiBadge(text = "Ahora")
            Text(
                text = row.durationLabel,
                style = TechLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            )
            TextButton(onClick = onOpen) { Text("Reiniciar") }
        }
        Text(
            text = row.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (row.subtitle.isNotBlank()) {
            Text(
                text = row.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A queue row.
 *
 * The drag gesture is attached to the row (long press, then move) and only exists where dragging
 * means something: [draggable] is false for the already-played section, where the handle is not
 * drawn either — no decoration that lies about what it does.
 */
@Composable
private fun QueueItemRow(
    row: QueueRow,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    draggable: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalHiFiDimens.current
    val dragModifier = if (draggable) {
        Modifier.pointerInput(row.index) {
            detectDragGesturesAfterLongPress(
                onDragStart = { onDragStart() },
                onDrag = { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.y)
                },
                onDragEnd = { onDragEnd() },
                onDragCancel = { onDragCancel() },
            )
        }
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(dimens.queueRowHeight)
            .then(dragModifier)
            .padding(horizontal = dimens.screenPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (draggable) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "Reordenar ${row.title}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Box(modifier = Modifier.size(20.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (row.isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(row.subtitle, row.durationLabel).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onPlay, modifier = Modifier.size(dimens.touchTarget)) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Reproducir ${row.title}",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(dimens.touchTarget)) {
            Icon(
                imageVector = Icons.Filled.DeleteOutline,
                contentDescription = "Quitar ${row.title} de la cola",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Inline confirmation: emptying the queue is destructive and this app uses no dialogs. */
@Composable
private fun ClearConfirmation(
    count: Int,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LocalHiFiDimens.current.screenPadding, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "¿Vaciar la cola ($count pistas)? Se detendrá la reproducción.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text("Cancelar") }
        TextButton(onClick = onConfirm) { Text("Vaciar") }
    }
}

@Composable
private fun QueueMessage(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LocalHiFiDimens.current.screenPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("Entendido") }
    }
}
