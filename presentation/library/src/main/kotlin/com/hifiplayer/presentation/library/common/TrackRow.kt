package com.hifiplayer.presentation.library.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.common.ext.formatDuration
import com.hifiplayer.core.designsystem.theme.LocalHiFiDimens
import com.hifiplayer.core.designsystem.theme.TechLabelStyle
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.presentation.library.artwork.TrackArtwork

/**
 * The one track row used by every list in the app.
 *
 * It always shows the same three things — cover, title and a second line — and the second line is
 * assembled from what the library really knows: artist/album, plus the measured format when the
 * "technical info in lists" setting is on. Missing values are skipped, never printed as dashes.
 */
@Composable
fun TrackRow(
    track: Track,
    onPlay: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onShowActions: () -> Unit,
    modifier: Modifier = Modifier,
    showTechnicalInfo: Boolean = true,
    isCurrent: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val dimens = LocalHiFiDimens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = dimens.screenPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TrackArtwork(track = track, size = dimens.artworkList)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val secondLine = buildSubtitle(track, showTechnicalInfo)
            Text(
                text = secondLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = if (track.durationMs > 0) formatDuration(track.durationMs) else "",
            style = TechLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (trailing == null) {
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier
                    .size(dimens.touchTarget)
                    .semantics {
                        contentDescription = if (isFavorite) {
                            "Quitar ${track.displayTitle} de favoritos"
                        } else {
                            "Añadir ${track.displayTitle} a favoritos"
                        }
                    },
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onShowActions, modifier = Modifier.size(dimens.touchTarget)) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Acciones para ${track.displayTitle}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            trailing()
        }
    }
}

/** "Artista · Álbum · 24-bit / 96 kHz", skipping whatever was not detected. */
internal fun buildSubtitle(track: Track, showTechnicalInfo: Boolean): String {
    val parts = mutableListOf<String>()
    track.artist?.takeIf { it.isNotBlank() }?.let { parts += it }
    track.album?.takeIf { it.isNotBlank() }?.let { parts += it }
    if (showTechnicalInfo) {
        val format = track.format
        if (format == null) {
            // The scanner could not read this file's header: say so instead of inventing a format.
            parts += "formato no detectado"
        } else {
            // Codec name first, then the measured figures. For lossy codecs [AudioFormatSpec.label]
            // already ends with the bitrate, so it is only added here for lossless files, where the
            // label has no bitrate of its own — otherwise the row would print it twice.
            parts += format.codec.displayName
            parts += format.label
            val bitrate = format.bitrateKbps
            if (bitrate != null && format.codec.isLossless && !format.label.contains("kbps")) {
                parts += "$bitrate kbps"
            }
        }
    }
    return parts.joinToString(" · ").ifBlank { track.displayName }
}

/** Row used by album/artist/genre/folder lists, where the "track" is really a container. */
@Composable
fun CollectionRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    trailingText: String? = null,
) {
    val dimens = LocalHiFiDimens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.screenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = TechLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Small inline play control reused by collection rows and headers. */
@Composable
fun InlinePlayButton(
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(LocalHiFiDimens.current.touchTarget)) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = contentDescription,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Keeps a stable placeholder box the size of a row while a list is still loading. */
@Composable
fun RowSpacer(height: Int = 1, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(vertical = 1.dp)) { }
}
