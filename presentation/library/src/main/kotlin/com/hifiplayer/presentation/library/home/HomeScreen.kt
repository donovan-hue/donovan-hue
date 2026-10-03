package com.hifiplayer.presentation.library.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.designsystem.component.HiFiEmptyState
import com.hifiplayer.core.designsystem.component.HiFiSectionHeader
import com.hifiplayer.core.designsystem.component.HiFiSearchField
import com.hifiplayer.core.designsystem.component.HiFiTopBar
import com.hifiplayer.core.designsystem.theme.LocalHiFiDimens
import com.hifiplayer.core.designsystem.theme.TechLabelStyle
import com.hifiplayer.domain.model.library.Album
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.playback.QueueOrigin
import com.hifiplayer.presentation.library.artwork.AlbumCover
import com.hifiplayer.presentation.library.artwork.ArtworkSizes
import com.hifiplayer.presentation.library.common.PlaylistPickerActions
import com.hifiplayer.presentation.library.common.ScreenBanner
import com.hifiplayer.presentation.library.common.TrackInteractionHost
import com.hifiplayer.presentation.library.common.TrackInteractionState
import com.hifiplayer.presentation.library.common.TrackRow

/**
 * Home (requirement 24).
 *
 * Sections appear only when they have real content, and each section plays as a list: tapping a
 * track sets the queue to that section, which is what a listener expects from "Play" in a shelf.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    interaction: TrackInteractionState,
    banner: ScreenBanner = ScreenBanner(),
    onPlaySection: (List<Track>, Track, QueueOrigin) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    pickerActions: PlaylistPickerActions,
    onToggleFavorite: (Track) -> Unit,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenFavorites: () -> Unit,
    onAddFolder: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalHiFiDimens.current

    Column(modifier = modifier.fillMaxSize()) {
        HiFiTopBar(
            title = "HiFi Player",
            subtitle = state.librarySummary() ?: "biblioteca vacía",
            actions = {
                TextButton(onClick = onRefresh) { Text("Escanear") }
            },
        )

        Box(modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 4.dp)) {
            // Tapping the field opens the dedicated search screen: the 300 ms debounce and the
            // results live there, not in an overlay.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSearch),
            ) {
                HiFiSearchField(value = "", onValueChange = {}, placeholder = "Buscar en tu biblioteca…")
            }
        }

        if (state.showScanBanner) {
            ScanBanner(processed = state.scan.processed, total = state.scan.total, label = state.scanLabel)
        }

        if (state.isLibraryEmpty) {
            HiFiEmptyState(
                icon = Icons.Filled.LibraryMusic,
                title = "Tu biblioteca está vacía",
                message = "Añade una carpeta de música y HiFi Player la analizará: leerá el formato real de " +
                    "cada archivo (codec, sample rate, profundidad) y las etiquetas que ya tienen.",
                actionLabel = "Añadir carpeta",
                onAction = onAddFolder,
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (state.recentlyPlayed.isNotEmpty()) {
                item(key = "recent-title") {
                    HiFiSectionHeader(
                        title = "Reproducido recientemente",
                        trailingLabel = "A la cola",
                        onTrailingClick = { onPlaySection(state.recentlyPlayed, state.recentlyPlayed.first(), QueueOrigin.ALL_TRACKS) },
                    )
                }
                items(state.recentlyPlayed.size) { index ->
                    val track = state.recentlyPlayed[index]
                    TrackRow(
                        track = track,
                        onPlay = { onPlaySection(state.recentlyPlayed, track, QueueOrigin.ALL_TRACKS) },
                        isFavorite = track.id in state.favoriteIds,
                        onToggleFavorite = { onToggleFavorite(track) },
                        onShowActions = { interaction.show(track) },
                    )
                }
            }

            if (state.albums.isNotEmpty()) {
                item(key = "albums-title") {
                    HiFiSectionHeader(title = "Álbumes")
                }
                item(key = "albums-row") {
                    AlbumShelf(albums = state.albums.take(ALBUM_SHELF_LIMIT), onOpenAlbum = onOpenAlbum)
                }
            }

            if (state.favorites.isNotEmpty()) {
                item(key = "fav-title") {
                    HiFiSectionHeader(
                        title = "Favoritos",
                        trailingLabel = "Ver todo",
                        onTrailingClick = onOpenFavorites,
                    )
                }
                items(state.favorites.size) { index ->
                    val track = state.favorites[index]
                    TrackRow(
                        track = track,
                        onPlay = { onPlaySection(state.favorites, track, QueueOrigin.ALL_TRACKS) },
                        isFavorite = true,
                        onToggleFavorite = { onToggleFavorite(track) },
                        onShowActions = { interaction.show(track) },
                    )
                }
            }

            if (state.recentlyAdded.isNotEmpty()) {
                item(key = "added-title") { HiFiSectionHeader(title = "Añadido recientemente") }
                items(state.recentlyAdded.size) { index ->
                    val track = state.recentlyAdded[index]
                    TrackRow(
                        track = track,
                        onPlay = { onPlaySection(state.recentlyAdded, track, QueueOrigin.ALL_TRACKS) },
                        isFavorite = track.id in state.favoriteIds,
                        onToggleFavorite = { onToggleFavorite(track) },
                        onShowActions = { interaction.show(track) },
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

            item(key = "bottom-space") { Box(modifier = Modifier.height(dimens.sectionSpacing)) }
        }
    }

    TrackInteractionHost(
        state = interaction,
        isFavorite = { it.id in state.favoriteIds },
        onToggleFavorite = onToggleFavorite,
        onPlay = { track -> onPlaySection(state.recentlyPlayed + state.favorites + state.recentlyAdded, track, QueueOrigin.ALL_TRACKS) },
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        pickerActions = pickerActions,
    )
}

@Composable
private fun ScanBanner(processed: Int, total: Int, label: String) {
    val dimens = LocalHiFiDimens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = TechLabelStyle,
            color = MaterialTheme.colorScheme.secondary,
        )
        if (total > 0) {
            LinearProgressIndicator(
                progress = { (processed.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/** Horizontal shelf of albums. Tapping one opens its track list. */
@Composable
private fun AlbumShelf(
    albums: List<Album>,
    onOpenAlbum: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = LocalHiFiDimens.current.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        albums.forEach { album ->
            Column(
                modifier = Modifier
                    .width(140.dp)
                    .clickable { onOpenAlbum(album) },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AlbumCover(
                    coverUri = album.coverTrackUri,
                    cacheKey = "album:${album.id}",
                    size = 140.dp,
                    targetPx = ArtworkSizes.GRID_PX,
                )
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        album.displayArtist,
                        "${album.trackCount} ${if (album.trackCount == 1) "pista" else "pistas"}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = album.formatSummary,
                    style = TechLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Kept as a constant so the shelf length is a decision, not a magic number in the layout. */
private const val ALBUM_SHELF_LIMIT = 12
