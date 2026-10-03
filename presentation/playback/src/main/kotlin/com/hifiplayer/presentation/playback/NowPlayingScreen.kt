package com.hifiplayer.presentation.playback

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.common.ext.formatDuration
import com.hifiplayer.core.designsystem.component.HiFiCard
import com.hifiplayer.core.designsystem.component.HiFiCardTitle
import com.hifiplayer.core.designsystem.component.HiFiEmptyState
import com.hifiplayer.core.designsystem.component.HiFiInfoRow
import com.hifiplayer.core.designsystem.component.HiFiSectionHeader
import com.hifiplayer.core.designsystem.component.HiFiSlider
import com.hifiplayer.core.designsystem.component.HiFiTopBar
import com.hifiplayer.core.designsystem.component.HiFiTransportButton
import com.hifiplayer.core.designsystem.theme.LocalHiFiDimens
import com.hifiplayer.core.designsystem.theme.TechLabelStyle
import com.hifiplayer.domain.model.playback.RepeatMode

/**
 * Now Playing (requirement 24; Rule 47 step 4).
 *
 * It is a pure function of [state]: every interaction is a callback the ViewModel turns into one
 * use-case call. The screen decides nothing about audio — if a value is not in the state, it is
 * shown as unknown rather than guessed.
 */
@Composable
fun NowPlayingScreen(
    state: NowPlayingUiState,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatCycle: () -> Unit,
    onScrubStart: () -> Unit,
    onScrubChange: (Float) -> Unit,
    onScrubFinish: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalHiFiDimens.current

    Column(modifier = modifier.fillMaxSize()) {
        HiFiTopBar(
            title = "Reproduciendo",
            subtitle = when {
                state.queueSize > 1 -> "cola de ${state.queueSize} pistas"
                state.queueSize == 1 -> "1 pista en cola"
                else -> null
            },
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = dimens.screenPadding, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.hasTrack) {
                HiFiEmptyState(
                    icon = Icons.Filled.Album,
                    title = "No hay nada reproduciéndose",
                    message = "Elige una pista en tu biblioteca para empezar. La información técnica " +
                        "aparecerá aquí en cuanto haya audio sonando.",
                )
                return@Column
            }

            ArtworkPanel(state = state)

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.artist,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val albumLine = listOfNotNull(
                    state.album.ifBlank { null },
                    state.year?.toString(),
                    state.trackNumberLabel,
                ).joinToString(" · ")
                if (albumLine.isNotBlank()) {
                    Text(
                        text = albumLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            PositionControls(
                state = state,
                onScrubStart = onScrubStart,
                onScrubChange = onScrubChange,
                onScrubFinish = onScrubFinish,
            )

            TransportControls(
                state = state,
                onTogglePlayPause = onTogglePlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                onShuffleToggle = onShuffleToggle,
                onRepeatCycle = onRepeatCycle,
            )

            if (state.message != null) {
                MessageBanner(
                    text = state.message,
                    isError = state.messageIsError,
                    onDismiss = onDismissMessage,
                )
            }

            HiFiSectionHeader(title = "Audio")
            BitPerfectBanner(banner = state.audio.bitPerfect)
            AudioInfoCard(audio = state.audio)

            HiFiCard {
                HiFiCardTitle(text = "Archivo")
                HiFiInfoRow(label = "Resumen", value = state.fileSummary)
                HiFiInfoRow(label = "Estado", value = state.statusLabel)
                HiFiInfoRow(label = "Artwork", value = state.artworkSourceLabel ?: if (state.artworkLoading) "cargando…" else null)
            }

            AudioPendingList()

            Box(modifier = Modifier.height(dimens.sectionSpacing))
        }
    }
}

@Composable
private fun ArtworkPanel(state: NowPlayingUiState, modifier: Modifier = Modifier) {
    val dimens = LocalHiFiDimens.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(dimens.artworkCorner)),
        contentAlignment = Alignment.Center,
    ) {
        val artwork = state.artwork
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = "Portada de ${state.album.ifBlank { state.title }}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(0.dp),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(0.18f).aspectRatio(1f),
                )
                Text(
                    text = if (state.artworkLoading) "cargando portada…" else "sin portada disponible",
                    style = TechLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun PositionControls(
    state: NowPlayingUiState,
    onScrubStart: () -> Unit,
    onScrubChange: (Float) -> Unit,
    onScrubFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = state.durationMs
    Column(modifier = modifier.fillMaxWidth()) {
        HiFiSlider(
            value = state.progress,
            onValueChange = { fraction ->
                onScrubStart()
                onScrubChange(fraction)
            },
            onValueChangeFinished = onScrubFinish,
            // Seeking needs a known duration; without it the control is honestly disabled.
            enabled = state.hasTrack && duration > 0L,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatDuration(if (state.isScrubbing) (duration * state.progress).toLong() else state.positionMs),
                style = TechLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (duration > 0L) formatDuration(duration) else "duración desconocida",
                style = TechLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransportControls(
    state: NowPlayingUiState,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalHiFiDimens.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HiFiTransportButton(
            icon = if (state.shuffleEnabled) Icons.Filled.ShuffleOn else Icons.Filled.Shuffle,
            contentDescription = if (state.shuffleEnabled) "Desactivar reproducción aleatoria" else "Activar reproducción aleatoria",
            onClick = onShuffleToggle,
            active = state.shuffleEnabled,
            enabled = state.queueSize > 1,
        )
        HiFiTransportButton(
            icon = Icons.Filled.SkipPrevious,
            contentDescription = "Pista anterior",
            onClick = onPrevious,
            enabled = state.hasPrevious,
        )
        HiFiTransportButton(
            icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (state.isPlaying) "Pausar" else "Reproducir",
            onClick = onTogglePlayPause,
            size = dimens.playButton,
            isPrimary = true,
            enabled = state.hasTrack,
        )
        HiFiTransportButton(
            icon = Icons.Filled.SkipNext,
            contentDescription = "Pista siguiente",
            onClick = onNext,
            enabled = state.hasNext,
        )
        HiFiTransportButton(
            icon = if (state.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            contentDescription = state.repeatLabel,
            onClick = onRepeatCycle,
            active = state.repeatMode != RepeatMode.OFF,
        )
    }
}

@Composable
private fun MessageBanner(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("Entendido") }
    }
}
