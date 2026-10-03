package com.hifiplayer.presentation.library.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.common.ext.formatDuration
import com.hifiplayer.core.designsystem.component.HiFiChip
import com.hifiplayer.core.designsystem.component.HiFiEmptyState
import com.hifiplayer.core.designsystem.component.HiFiTopBar
import com.hifiplayer.core.designsystem.theme.LocalHiFiDimens
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.presentation.library.common.PlaylistPickerActions
import com.hifiplayer.presentation.library.common.ScreenBanner
import com.hifiplayer.presentation.library.common.TrackInteractionHost
import com.hifiplayer.presentation.library.common.TrackInteractionState
import com.hifiplayer.presentation.library.common.TrackRow

data class CollectionUiState(
    val title: String,
    val subtitle: String? = null,
    val tracks: List<Track> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    val summary: String
        get() {
            if (tracks.isEmpty()) return subtitle.orEmpty()
            val count = if (tracks.size == 1) "1 pista" else "${tracks.size} pistas"
            val duration = formatDuration(tracks.sumOf { it.durationMs })
            val lossless = tracks.count { it.isLossless }
            return listOf(
                count,
                duration,
                if (lossless == tracks.size) "todo sin pérdida" else "$lossless sin pérdida",
            ).joinToString(" · ")
        }
}

/** Album / artist / genre / folder detail. */
@Composable
fun CollectionScreen(
    state: CollectionUiState,
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
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    showTechnicalInfo: Boolean = true,
) {
    val dimens = LocalHiFiDimens.current

    Column(modifier = modifier.fillMaxSize()) {
        HiFiTopBar(
            title = state.title,
            subtitle = state.summary,
            onBack = onBack,
        )

        if (state.tracks.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HiFiChip(label = "Reproducir", selected = true, onClick = { onPlayAll(state.tracks) })
                HiFiChip(label = "Aleatorio", selected = false, onClick = { onShuffleAll(state.tracks) })
                HiFiChip(label = "A la cola", selected = false, onClick = { onAddAllToQueue(state.tracks) })
            }
        }

        if (state.tracks.isEmpty()) {
            HiFiEmptyState(
                icon = Icons.Filled.LibraryMusic,
                title = "No hay pistas aquí",
                message = "Esta vista está vacía: puede que los archivos se hayan movido o que todavía " +
                    "no se haya completado el escaneo.",
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.tracks, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    onPlay = { onPlayTrack(track, state.tracks) },
                    isFavorite = track.id in state.favoriteIds,
                    onToggleFavorite = { onToggleFavorite(track) },
                    onShowActions = { interaction.show(track) },
                    showTechnicalInfo = showTechnicalInfo,
                )
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
