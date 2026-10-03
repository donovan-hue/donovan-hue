package com.hifiplayer.presentation.playback.audio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.designsystem.component.HiFiCard
import com.hifiplayer.core.designsystem.component.HiFiCardTitle
import com.hifiplayer.core.designsystem.component.HiFiChip
import com.hifiplayer.core.designsystem.component.HiFiEmptyState
import com.hifiplayer.core.designsystem.component.HiFiInfoRow
import com.hifiplayer.core.designsystem.component.HiFiSectionHeader
import com.hifiplayer.core.designsystem.component.HiFiTopBar
import com.hifiplayer.core.designsystem.theme.LocalHiFiDimens
import com.hifiplayer.core.designsystem.theme.TechLabelStyle
import com.hifiplayer.presentation.playback.AudioInfoCard
import com.hifiplayer.presentation.playback.BitPerfectBanner

/**
 * Audio Information (phase 8, requirement 30).
 *
 * SOURCE and OUTPUT are the engine's own measurements, and every stage between them is listed with
 * the settings that are really on. The screen also carries the two things that make the claim
 * checkable: the platform's decoder list and the on-demand verification of the current format.
 */
@Composable
fun AudioInfoScreen(
    state: AudioInfoUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onVerifyCurrentTrack: () -> Unit,
    onOpenOutputDevices: () -> Unit,
    onOpenBitPerfect: () -> Unit,
    onOpenEq: () -> Unit,
    onOpenReplayGain: () -> Unit,
    onOpenCrossfeed: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalHiFiDimens.current

    Column(modifier = modifier.fillMaxSize()) {
        HiFiTopBar(
            title = "Información de audio",
            subtitle = state.activeRouteName,
            onBack = onBack,
            actions = { TextButton(onClick = onRefresh) { Text("Volver a leer") } },
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "banner") {
                Box(modifier = Modifier.padding(horizontal = dimens.screenPadding)) {
                    BitPerfectBanner(banner = state.audio.bitPerfect)
                }
            }

            item(key = "path") {
                Box(modifier = Modifier.padding(horizontal = dimens.screenPadding)) {
                    AudioInfoCard(audio = state.audio)
                }
            }

            item(key = "links") {
                Row(
                    modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HiFiChip(label = "Salidas", selected = false, onClick = onOpenOutputDevices)
                    HiFiChip(label = "Bit-perfect", selected = false, onClick = onOpenBitPerfect)
                    HiFiChip(label = "EQ", selected = state.audio.eqActive, onClick = onOpenEq)
                    HiFiChip(label = "ReplayGain", selected = state.audio.replayGainLabel != null, onClick = onOpenReplayGain)
                    HiFiChip(label = "Crossfeed", selected = state.audio.crossfeedLabel != null, onClick = onOpenCrossfeed)
                }
            }

            item(key = "verify") {
                HiFiCard(modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 6.dp)) {
                    HiFiCardTitle("¿Este formato sale sin tocar?")
                    Text(
                        text = "Pregunta al sistema, para el formato exacto de la pista que está sonando, si " +
                            "acepta entregarlo sin mezclar ni remuestrear. La respuesta se muestra tal cual, " +
                            "aunque sea negativa.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onVerifyCurrentTrack, enabled = state.track != null) {
                        Text("Comprobar la pista actual")
                    }
                    if (state.track == null) {
                        Text(
                            text = "No hay ninguna pista sonando.",
                            style = TechLabelStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.verifications.forEach { row ->
                        VerificationRowView(row)
                    }
                }
            }

            item(key = "device") {
                HiFiCard(modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 6.dp)) {
                    HiFiCardTitle("Salida activa")
                    HiFiInfoRow(label = "Ruta", value = state.activeRouteName)
                    HiFiInfoRow(label = "Tipo", value = state.activeRouteType)
                    HiFiInfoRow(label = "Bit-perfect", value = state.supportLabel)
                    HiFiInfoRow(label = "Mezclador del sistema", value = state.mixerLabel)
                    HiFiInfoRow(label = "Frames por buffer", value = state.report?.mixerFramesPerBuffer?.toString())
                    HiFiInfoRow(
                        label = "Salida de baja latencia",
                        value = state.report?.let { if (it.isLowLatencyOutputSupported) "sí" else "no" },
                    )
                    HiFiInfoRow(
                        label = "API de mezclador sin mezcla",
                        value = state.report?.let { if (it.bitPerfectApiAvailable) "disponible" else "no disponible" },
                    )
                }
            }

            if (state.blockers.isNotEmpty()) {
                item(key = "blockers") {
                    HiFiCard(modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 6.dp)) {
                        HiFiCardTitle("Por qué no hay bit-perfect ahora mismo")
                        state.blockers.forEach { blocker ->
                            Text(
                                text = "• $blocker",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            if (state.evidence.isNotEmpty()) {
                item(key = "evidence") {
                    HiFiCard(modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 6.dp)) {
                        HiFiCardTitle("Lo que respondió el sistema")
                        state.evidence.forEach { line ->
                            Text(
                                text = line,
                                style = TechLabelStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            item(key = "decoders") { HiFiSectionHeader(title = "Decodificadores del dispositivo") }
            if (state.decoders.isEmpty()) {
                item(key = "decoders-empty") {
                    HiFiEmptyState(
                        icon = Icons.Filled.Tune,
                        title = "Sin datos de decodificadores",
                        message = "La consulta al sistema todavía no ha devuelto resultados.",
                    )
                }
            } else {
                items(state.decoders, key = { it.codec.id }) { decoder ->
                    DecoderRowView(decoder)
                }
            }

            item(key = "bottom") { Box(modifier = Modifier.height(dimens.sectionSpacing)) }
        }
    }
}

@Composable
private fun VerificationRowView(row: VerificationRow) {
    val color = if (row.bitPerfectAchieved) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${row.formatLabel} → ${row.deliveredLabel}",
                style = TechLabelStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = row.message,
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
    }
}

@Composable
private fun DecoderRowView(decoder: DecoderRow) {
    val dimens = LocalHiFiDimens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = decoder.codec.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val detail = listOfNotNull(
                when {
                    !decoder.playable -> "no disponible en este dispositivo"
                    decoder.isSoftwareOnly -> "solo decodificador por software"
                    else -> "decodificador del sistema"
                },
                decoder.decoderName,
                decoder.notes,
            ).joinToString(" · ")
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (decoder.playable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
