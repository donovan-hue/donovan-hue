package com.hifiplayer.presentation.library.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.designsystem.component.HiFiBottomPanel
import com.hifiplayer.core.designsystem.component.HiFiEmptyState
import com.hifiplayer.core.designsystem.component.HiFiSearchField
import com.hifiplayer.core.designsystem.theme.TechLabelStyle
import com.hifiplayer.domain.model.library.Track

/**
 * Which track the action panel is talking about.
 *
 * Held here so a row can be a plain function: it either plays the track or asks for its actions,
 * and every screen shares the same panel with the same wording.
 */
class TrackActionSheetState {
    var track: Track? by mutableStateOf(null)
        private set

    fun show(track: Track) {
        this.track = track
    }

    fun dismiss() {
        track = null
    }
}

@Composable
fun rememberTrackActionSheetState(): TrackActionSheetState = remember { TrackActionSheetState() }

/**
 * The whole track-interaction surface of a screen: the action panel plus the playlist picker.
 *
 * They are hosted together on purpose — "add to a playlist" is the only entry of the panel that opens
 * a second panel, and keeping that transition in one place means no screen can forget to render the
 * picker and leave a dead entry behind (requirement 46).
 */
class TrackInteractionState {
    val actions = TrackActionSheetState()

    var pickerTrack: Track? by mutableStateOf(null)
        private set

    fun show(track: Track) = actions.show(track)

    /** The panel asked to add [track] to a playlist: close it and open the picker. */
    fun openPickerFor(track: Track) {
        actions.dismiss()
        pickerTrack = track
    }

    fun dismissPicker() {
        pickerTrack = null
    }
}

@Composable
fun rememberTrackInteractionState(): TrackInteractionState = remember { TrackInteractionState() }

@Composable
fun TrackInteractionHost(
    state: TrackInteractionState,
    isFavorite: (Track) -> Boolean,
    onToggleFavorite: (Track) -> Unit,
    onPlay: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    pickerActions: PlaylistPickerActions,
) {
    TrackActionSheetHost(
        state = state.actions,
        isFavorite = isFavorite,
        onToggleFavorite = onToggleFavorite,
        onPlay = onPlay,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        onAddToPlaylist = state::openPickerFor,
    )

    val pickerTrack = state.pickerTrack ?: return
    PlaylistPickerSheet(
        track = pickerTrack,
        actions = pickerActions,
        onDismiss = state::dismissPicker,
    )
}

/**
 * Actions for one track.
 *
 * Every entry performs a real operation: play, play next, add to the queue, add to a playlist and
 * toggle favourite. There is no share, no "song info" that opens nothing and no entry that is there
 * just to fill the list (requirement 46).
 */
@Composable
fun TrackActionSheetHost(
    state: TrackActionSheetState,
    isFavorite: (Track) -> Boolean,
    onToggleFavorite: (Track) -> Unit,
    onPlay: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
) {
    val track = state.track ?: return
    val favorite = isFavorite(track)

    Box(modifier = Modifier.fillMaxSize()) {
        HiFiBottomPanel(
            title = track.displayTitle,
            onDismiss = state::dismiss,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = listOfNotNull(
                    track.artist,
                    track.album,
                    track.format?.label,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ActionEntry(
                icon = Icons.Filled.PlayArrow,
                label = "Reproducir",
                onClick = {
                    state.dismiss()
                    onPlay(track)
                },
            )
            ActionEntry(
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                label = "Reproducir a continuación",
                onClick = {
                    state.dismiss()
                    onPlayNext(track)
                },
            )
            ActionEntry(
                icon = Icons.Filled.Add,
                label = "Añadir a la cola",
                onClick = {
                    state.dismiss()
                    onAddToQueue(track)
                },
            )
            ActionEntry(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                label = "Añadir a una lista",
                onClick = {
                    state.dismiss()
                    onAddToPlaylist(track)
                },
            )
            ActionEntry(
                icon = if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                label = if (favorite) "Quitar de favoritos" else "Añadir a favoritos",
                onClick = {
                    state.dismiss()
                    onToggleFavorite(track)
                },
            )
        }
    }
}

@Composable
private fun ActionEntry(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableRow(onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

/** What the playlist picker needs from its owner: the lists, and two real mutations. */
data class PlaylistPickerActions(
    val playlists: List<PlaylistOption>,
    val onAdd: (playlistId: String, track: Track) -> Unit,
    val onCreate: (name: String, track: Track) -> Unit,
)

data class PlaylistOption(val id: String, val name: String, val trackCount: Int)

/**
 * Playlist picker: appears after choosing "Añadir a una lista".
 *
 * It can also create the list on the spot, because sending the user to another screen to create one
 * would lose the track they were about to add.
 */
@Composable
fun PlaylistPickerSheet(
    track: Track,
    actions: PlaylistPickerActions,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        HiFiBottomPanel(
            title = "Añadir a una lista",
            onDismiss = onDismiss,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = track.displayTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HiFiSearchField(
                value = newName,
                onValueChange = { newName = it },
                placeholder = "Nombre de la lista nueva…",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Crear y añadir",
                    style = TechLabelStyle,
                    color = if (newName.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.clickableRow {
                        if (newName.isNotBlank()) {
                            actions.onCreate(newName.trim(), track)
                            newName = ""
                        }
                    }.padding(vertical = 10.dp),
                )
            }

            if (actions.playlists.isEmpty()) {
                HiFiEmptyState(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    title = "Todavía no hay listas",
                    message = "Escribe un nombre arriba para crear la primera y añadir esta pista.",
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    items(actions.playlists, key = { it.id }) { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickableRow { actions.onAdd(option.id, track) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = option.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (option.trackCount == 1) "1 pista" else "${option.trackCount} pistas",
                                style = TechLabelStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
