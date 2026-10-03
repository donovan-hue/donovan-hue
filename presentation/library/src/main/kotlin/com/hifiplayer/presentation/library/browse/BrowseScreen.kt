package com.hifiplayer.presentation.library.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.common.ext.formatBytes
import com.hifiplayer.core.common.ext.formatDuration
import com.hifiplayer.core.designsystem.component.HiFiChip
import com.hifiplayer.core.designsystem.component.HiFiChipRow
import com.hifiplayer.core.designsystem.component.HiFiEmptyState
import com.hifiplayer.core.designsystem.component.HiFiSectionHeader
import com.hifiplayer.core.designsystem.component.HiFiTopBar
import com.hifiplayer.core.designsystem.theme.LocalHiFiDimens
import com.hifiplayer.core.designsystem.theme.TechLabelStyle
import com.hifiplayer.domain.model.library.Album
import com.hifiplayer.domain.model.library.Artist
import com.hifiplayer.domain.model.library.Genre
import com.hifiplayer.domain.model.library.LibraryFolder
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.library.TrackSort
import com.hifiplayer.presentation.library.artwork.AlbumCover
import com.hifiplayer.presentation.library.artwork.ArtworkSizes
import com.hifiplayer.presentation.library.common.CollectionRow
import com.hifiplayer.presentation.library.common.PlaylistPickerActions
import com.hifiplayer.presentation.library.common.ScreenBanner
import com.hifiplayer.presentation.library.common.TrackInteractionHost
import com.hifiplayer.presentation.library.common.TrackInteractionState
import com.hifiplayer.presentation.library.common.TrackRow

/**
 * Library browsing with the five views the specification asks for (requirement 25).
 *
 * Sorting and filtering are chips rather than a dropdown menu: on a phone held one-handed, a chip
 * row shows the active choice without opening anything, and every chip is a real query change.
 */
@Composable
fun BrowseScreen(
    state: BrowseUiState,
    interaction: TrackInteractionState,
    banner: ScreenBanner = ScreenBanner(),
    onSelectTab: (LibraryTab) -> Unit,
    onSelectSort: (TrackSort) -> Unit,
    onToggleFavoritesFilter: () -> Unit,
    onToggleLosslessFilter: () -> Unit,
    onRefresh: () -> Unit,
    onPlaySong: (Track, List<Track>) -> Unit,
    onPlayAll: (List<Track>) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    pickerActions: PlaylistPickerActions,
    onToggleFavorite: (Track) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    onOpenGenre: (Genre) -> Unit,
    onOpenFolder: (LibraryFolder) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    showTechnicalInfo: Boolean = true,
) {
    val dimens = LocalHiFiDimens.current

    Column(modifier = modifier.fillMaxSize()) {
        HiFiTopBar(
            title = "Biblioteca",
            subtitle = state.countLabel,
            actions = { TextButton(onClick = onRefresh) { Text("Escanear") } },
        )

        HiFiChipRow(
            options = LibraryTab.entries,
            selected = state.tab,
            label = { it.label },
            onSelect = onSelectTab,
        )

        if (state.tab == LibraryTab.SONGS) {
            HiFiChipRow(
                options = TrackSort.entries,
                selected = state.sort,
                label = { it.displayName },
                onSelect = onSelectSort,
            )
            Row(
                modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HiFiChip(
                    label = "Solo sin pérdida",
                    selected = state.losslessOnly,
                    onClick = onToggleLosslessFilter,
                )
                HiFiChip(
                    label = "Solo favoritos",
                    selected = state.favoritesOnly,
                    onClick = onToggleFavoritesFilter,
                )
                if (state.songs.isNotEmpty()) {
                    HiFiChip(
                        label = "Reproducir todo",
                        selected = false,
                        onClick = { onPlayAll(state.songs) },
                    )
                }
            }
        }

        if (state.isEmpty) {
            HiFiEmptyState(
                icon = when (state.tab) {
                    LibraryTab.ALBUMS -> Icons.Filled.Album
                    LibraryTab.ARTISTS -> Icons.Filled.Person
                    LibraryTab.GENRES -> Icons.Filled.Tune
                    LibraryTab.FOLDERS -> Icons.Filled.Folder
                    LibraryTab.SONGS -> Icons.Filled.LibraryMusic
                },
                title = "Nada que mostrar en ${state.tab.label.lowercase()}",
                message = state.emptyMessage(),
                actionLabel = "Volver a escanear",
                onAction = onRefresh,
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            when (state.tab) {
                LibraryTab.SONGS -> items(state.songs, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        onPlay = { onPlaySong(track, state.songs) },
                        isFavorite = track.id in state.favoriteIds,
                        onToggleFavorite = { onToggleFavorite(track) },
                        onShowActions = { interaction.show(track) },
                        showTechnicalInfo = showTechnicalInfo,
                    )
                }

                LibraryTab.ALBUMS -> items(state.albums, key = { it.id }) { album ->
                    CollectionRow(
                        title = album.title,
                        subtitle = listOfNotNull(
                            album.displayArtist,
                            album.year?.toString(),
                            "${album.trackCount} ${if (album.trackCount == 1) "pista" else "pistas"}",
                            album.formatSummary.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        onClick = { onOpenAlbum(album) },
                        leading = {
                            AlbumCover(
                                coverUri = album.coverTrackUri,
                                cacheKey = "album:${album.id}",
                                size = 56.dp,
                                targetPx = ArtworkSizes.LIST_PX,
                            )
                        },
                    )
                }

                LibraryTab.ARTISTS -> items(state.artists, key = { it.id }) { artist ->
                    CollectionRow(
                        title = artist.name,
                        subtitle = "${artist.trackCount} pistas · ${artist.albumCount} álbumes",
                        onClick = { onOpenArtist(artist) },
                        leading = { CollectionIcon(icon = Icons.Filled.Person) },
                    )
                }

                LibraryTab.GENRES -> items(state.genres, key = { it.name }) { genre ->
                    CollectionRow(
                        title = genre.name,
                        subtitle = if (genre.trackCount == 1) "1 pista" else "${genre.trackCount} pistas",
                        onClick = { onOpenGenre(genre) },
                        leading = { CollectionIcon(icon = Icons.Filled.Tune) },
                    )
                }

                LibraryTab.FOLDERS -> items(state.folders, key = { it.path }) { folder ->
                    CollectionRow(
                        title = folder.displayName,
                        subtitle = listOf(
                            "${folder.trackCount} ${if (folder.trackCount == 1) "pista" else "pistas"}",
                            formatBytes(folder.totalSizeBytes),
                            folder.source.label,
                        ).joinToString(" · "),
                        onClick = { onOpenFolder(folder) },
                        leading = { CollectionIcon(icon = Icons.Filled.Folder) },
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

            if (state.stats != null && state.tab == LibraryTab.SONGS) {
                item(key = "stats") {
                    LibraryStatsFooter(state)
                }
            }

            item(key = "bottom") { Box(modifier = Modifier.height(dimens.sectionSpacing)) }
        }
    }

    TrackInteractionHost(
        state = interaction,
        isFavorite = { it.id in state.favoriteIds },
        onToggleFavorite = onToggleFavorite,
        onPlay = { track -> onPlaySong(track, state.songs) },
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        pickerActions = pickerActions,
    )
}

/** Square icon tile used as the leading element of artist, genre and folder rows. */
@Composable
private fun CollectionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Footer with the real library numbers.
 *
 * It exists because "how much music do I have, and how much of it is high resolution" is a question
 * this app can answer exactly — and it answers it from the scanner's data, not from an estimate.
 */
@Composable
private fun LibraryStatsFooter(state: BrowseUiState) {
    val stats = state.stats ?: return
    val dimens = LocalHiFiDimens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HiFiSectionHeader(title = "Resumen de la biblioteca")
        Text(
            text = "Duración total: ${formatDuration(stats.totalDurationMs)} · Tamaño: ${formatBytes(stats.totalSizeBytes)}",
            style = TechLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Sin pérdida: ${stats.losslessCount} · Alta resolución: ${stats.highResolutionCount}",
            style = TechLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val breakdown = stats.codecBreakdown.entries
            .filter { it.value > 0 }
            .joinToString(" · ") { "${it.key.displayName}: ${it.value}" }
        if (breakdown.isNotBlank()) {
            Text(
                text = "Codecs: $breakdown",
                style = TechLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (stats.unknownFormatCount > 0) {
            Text(
                text = "${stats.unknownFormatCount} archivos sin formato reconocido (no se muestra su calidad)",
                style = TechLabelStyle,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
