package com.hifiplayer.presentation.library.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.common.ext.formatDuration
import com.hifiplayer.core.designsystem.component.HiFiEmptyState
import com.hifiplayer.presentation.library.common.ScreenBanner
import com.hifiplayer.core.designsystem.component.HiFiSearchField
import com.hifiplayer.core.designsystem.component.HiFiTopBar
import com.hifiplayer.core.designsystem.theme.LocalHiFiDimens
import com.hifiplayer.domain.model.library.Playlist

/** Playlist list (phase 6). */
@Composable
fun PlaylistsScreen(
    state: PlaylistsUiState,
    onNewNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onOpen: (Playlist) -> Unit,
    onPlay: (String, Boolean) -> Unit,
    onStartRename: (Playlist) -> Unit,
    onRenameValueChange: (String) -> Unit,
    onConfirmRename: () -> Unit,
    onCancelRename: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    banner: ScreenBanner = ScreenBanner(),
) {
    val dimens = LocalHiFiDimens.current

    Column(modifier = modifier.fillMaxSize()) {
        HiFiTopBar(
            title = "Listas de reproducción",
            subtitle = if (state.playlists.isEmpty()) null else {
                if (state.playlists.size == 1) "1 lista" else "${state.playlists.size} listas"
            },
        )

        Row(
            modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                HiFiSearchField(
                    value = state.newName,
                    onValueChange = onNewNameChange,
                    placeholder = "Nombre de la lista nueva…",
                )
            }
            TextButton(onClick = onCreate, enabled = state.canCreate) { Text("Crear") }
        }

        if (state.isEmpty) {
            HiFiEmptyState(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                title = "Todavía no hay listas",
                message = "Escribe un nombre arriba y pulsa Crear. Después podrás añadir pistas desde " +
                    "cualquier lista de la biblioteca con el botón de acciones.",
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.playlists, key = { it.id }) { playlist ->
                Column {
                    PlaylistRow(
                        playlist = playlist,
                        isRenaming = state.renamingId == playlist.id,
                        renameValue = state.renameValue,
                        onRenameValueChange = onRenameValueChange,
                        onConfirmRename = onConfirmRename,
                        onCancelRename = onCancelRename,
                        onOpen = { onOpen(playlist) },
                        onPlay = { onPlay(playlist.id, false) },
                        onShuffle = { onPlay(playlist.id, true) },
                        onStartRename = { onStartRename(playlist) },
                        onRequestDelete = { onRequestDelete(playlist.id) },
                    )
                    if (state.pendingDeleteId == playlist.id) {
                        DeleteConfirmation(
                            playlist = playlist,
                            onCancel = onCancelDelete,
                            onConfirm = onConfirmDelete,
                        )
                    }
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
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    isRenaming: Boolean,
    renameValue: String,
    onRenameValueChange: (String) -> Unit,
    onConfirmRename: () -> Unit,
    onCancelRename: () -> Unit,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onStartRename: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val dimens = LocalHiFiDimens.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isRenaming, onClick = onOpen)
            .padding(horizontal = dimens.screenPadding, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                if (isRenaming) {
                    HiFiSearchField(
                        value = renameValue,
                        onValueChange = onRenameValueChange,
                        placeholder = "Nuevo nombre…",
                    )
                } else {
                    Text(
                        text = playlist.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOf(
                            if (playlist.trackCount == 1) "1 pista" else "${playlist.trackCount} pistas",
                            formatDuration(playlist.totalDurationMs),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isRenaming) {
                TextButton(onClick = onConfirmRename) { Text("Guardar") }
                TextButton(onClick = onCancelRename) { Text("Cancelar") }
            } else {
                IconButton(
                    onClick = onPlay,
                    enabled = playlist.trackCount > 0,
                    modifier = Modifier.size(dimens.touchTarget),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                        contentDescription = "Reproducir ${playlist.displayName}",
                        tint = if (playlist.trackCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(
                    onClick = onShuffle,
                    enabled = playlist.trackCount > 0,
                    modifier = Modifier.size(dimens.touchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = "Reproducir ${playlist.displayName} en orden aleatorio",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onStartRename, modifier = Modifier.size(dimens.touchTarget)) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Renombrar ${playlist.displayName}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRequestDelete, modifier = Modifier.size(dimens.touchTarget)) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Borrar ${playlist.displayName}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmation(playlist: Playlist, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalHiFiDimens.current.screenPadding, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "¿Borrar \"${playlist.displayName}\"? Se quitará la lista, no los archivos.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text("Cancelar") }
        TextButton(onClick = onConfirm) { Text("Borrar") }
    }
}
