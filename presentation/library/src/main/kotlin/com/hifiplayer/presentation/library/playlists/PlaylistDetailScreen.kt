package com.hifiplayer.presentation.library.playlists

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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.designsystem.component.HiFiChip
import com.hifiplayer.core.designsystem.component.HiFiEmptyState
import com.hifiplayer.core.designsystem.component.HiFiTopBar
import com.hifiplayer.core.designsystem.theme.LocalHiFiDimens
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.presentation.library.common.PlaylistPickerActions
import com.hifiplayer.presentation.library.common.ScreenBanner
import com.hifiplayer.presentation.library.common.TrackInteractionHost
import com.hifiplayer.presentation.library.common.TrackInteractionState
import com.hifiplayer.presentation.library.common.buildSubtitle
import com.hifiplayer.presentation.library.artwork.TrackArtwork
import kotlin.math.roundToInt

/**
 * Playlist detail (phase 6).
 *
 * Reordering uses the same gesture as the playback queue: long press, then drag, with a fixed row
 * height so the finger offset can be converted into a number of rows exactly. The new order is
 * written to the database on drop — the list is the source of truth, not this composable.
 */
@Composable
fun PlaylistDetailScreen(
    state: PlaylistDetailUiState,
    interaction: TrackInteractionState,
    banner: ScreenBanner = ScreenBanner(),
    onBack: () -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onPlayAll: (List<Track>) -> Unit,
    onShuffleAll: (List<Track>) -> Unit,
    onAddAllToQueue: (List<Track>) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    pickerActions: PlaylistPickerActions,
    onToggleFavorite: (Track) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemoveRequested: (String) -> Unit,
    onRemoveCancelled: () -> Unit,
    onRemoveConfirmed: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    showTechnicalInfo: Boolean = true,
) {
    val dimens = LocalHiFiDimens.current
    val density = LocalDensity.current
    val rowHeight = dimens.queueRowHeight
    val rowHeightPx = with(density) { rowHeight.toPx() }

    var draggingPosition by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val localOrder = remember { mutableStateOf<SnapshotStateList<Track>?>(null) }

    LaunchedEffect(state.tracks) {
        if (draggingPosition < 0) localOrder.value = state.tracks.toMutableStateList()
    }

    Column(modifier = modifier.fillMaxSize()) {
        HiFiTopBar(
            title = state.title,
            subtitle = if (state.isEmpty) null else {
                val count = if (state.tracks.size == 1) "1 pista" else "${state.tracks.size} pistas"
                val duration = com.hifiplayer.core.common.ext.formatDuration(state.tracks.sumOf { it.durationMs })
                "$count · $duration · mantén pulsado para reordenar"
            },
            onBack = onBack,
        )

        if (!state.isEmpty) {
            Row(
                modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HiFiChip(label = "Reproducir", selected = true, onClick = { onPlayAll(state.tracks) })
                HiFiChip(label = "Aleatorio", selected = false, onClick = { onShuffleAll(state.tracks) })
                HiFiChip(label = "A la cola", selected = false, onClick = { onAddAllToQueue(state.tracks) })
            }
        }

        if (state.isEmpty) {
            HiFiEmptyState(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                title = "Esta lista está vacía",
                message = "Añade pistas desde cualquier vista de la biblioteca: en cada fila, el botón de " +
                    "acciones ofrece \"Añadir a una lista\".",
            )
            return@Column
        }

        val ordered = localOrder.value ?: state.tracks
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(ordered, key = { it.id }) { track ->
                val isDragging = draggingPosition >= 0 &&
                    localOrder.value?.getOrNull(draggingPosition)?.id == track.id
                PlaylistTrackRow(
                    track = track,
                    isFavorite = track.id in state.favoriteIds,
                    showTechnicalInfo = showTechnicalInfo,
                    isDragging = isDragging,
                    modifier = Modifier
                        .height(rowHeight)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                        .background(
                            if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                        ),
                    onPlay = { onPlayTrack(track, ordered) },
                    onShowActions = { interaction.show(track) },
                    onToggleFavorite = { onToggleFavorite(track) },
                    onRemove = { onRemoveRequested(track.id) },
                    dragModifier = Modifier.pointerInput(track.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    val list = localOrder.value ?: state.tracks.toMutableStateList().also {
                                        localOrder.value = it
                                    }
                                    draggingPosition = list.indexOfFirst { it.id == track.id }
                                    dragOffset = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val list = localOrder.value ?: return@detectDragGesturesAfterLongPress
                                    val position = draggingPosition
                                    if (position < 0) return@detectDragGesturesAfterLongPress
                                    dragOffset += dragAmount.y
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
                                    val from = draggingPosition
                                    if (list != null && from >= 0) {
                                        val trackId = state.tracks.getOrNull(from)?.id
                                        val to = if (trackId == null) -1 else list.indexOfFirst { it.id == trackId }
                                        if (to >= 0 && to != from) onMove(from, to)
                                    }
                                    draggingPosition = -1
                                    dragOffset = 0f
                                    localOrder.value = null
                                },
                                onDragCancel = {
                                    draggingPosition = -1
                                    dragOffset = 0f
                                    localOrder.value = null
                                },
                            )
                    },
                )

                if (state.pendingRemoveTrackId == track.id) {
                    RemoveConfirmation(
                        title = track.displayTitle,
                        onCancel = onRemoveCancelled,
                        onConfirm = onRemoveConfirmed,
                    )
                }
            }

            val overlayMessage = banner.message ?: state.message
            if (overlayMessage != null) {
                item(key = "message") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = dimens.screenPadding, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = overlayMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (if (banner.visible) banner.isError else state.messageIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { if (banner.visible) banner.onDismiss() else onDismissMessage() }) { Text("Entendido") }
                    }
                }
            }

            item(key = "bottom") { Box(modifier = Modifier.height(dimens.sectionSpacing)) }
        }
    }

    TrackInteractionHost(
        state = interaction,
        isFavorite = { it.id in state.favoriteIds },
        onToggleFavorite = onToggleFavorite,
        onPlay = { track -> onPlayTrack(track, state.tracks) },
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        pickerActions = pickerActions,
    )
}

@Composable
private fun PlaylistTrackRow(
    track: Track,
    isFavorite: Boolean,
    showTechnicalInfo: Boolean,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onShowActions: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRemove: () -> Unit,
    dragModifier: Modifier,
) {
    val dimens = LocalHiFiDimens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(dragModifier)
            .padding(horizontal = dimens.screenPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.DragHandle,
            contentDescription = "Reordenar ${track.displayTitle}",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        TrackArtwork(track = track, size = dimens.artworkMini)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        ) {
            Text(
                text = track.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildSubtitle(track, showTechnicalInfo),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(dimens.touchTarget)) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFavorite) "Quitar de favoritos" else "Añadir a favoritos",
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(dimens.touchTarget)) {
            Icon(
                imageVector = Icons.Filled.DeleteOutline,
                contentDescription = "Quitar ${track.displayTitle} de la lista",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RemoveConfirmation(title: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalHiFiDimens.current.screenPadding, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "¿Quitar \"$title\" de la lista? El archivo no se toca.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text("Cancelar") }
        TextButton(onClick = onConfirm) { Text("Quitar") }
    }
}
