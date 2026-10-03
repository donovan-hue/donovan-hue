package com.hifiplayer.presentation.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.designsystem.component.HiFiBadge
import com.hifiplayer.core.designsystem.component.HiFiCard
import com.hifiplayer.core.designsystem.component.HiFiCardTitle
import com.hifiplayer.core.designsystem.component.HiFiInfoRow
import com.hifiplayer.core.designsystem.component.HiFiPendingNotice
import com.hifiplayer.core.designsystem.theme.LocalHiFiExtraColors
import com.hifiplayer.core.designsystem.theme.TechLabelStyle

/**
 * The banner at the top of the audio panel.
 *
 * Its text is the engine's own verdict — the ViewModel copies [BitPerfectBanner.title] and
 * [BitPerfectBanner.detail] verbatim, so the screen can never say "BIT-PERFECT" when the engine
 * did not verify a non-mixing path (requirements 6 and 30).
 */
@Composable
fun BitPerfectBanner(
    banner: BitPerfectBanner,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HiFiBadge(text = banner.title.ifBlank { "sin datos" }, active = banner.isActive)
        Text(
            text = banner.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * SOURCE vs OUTPUT (requirement 30).
 *
 * Three flat panels, one per stage: what the file contains, what the decoder produced and what is
 * leaving the device. Rows whose value was never measured print "no detectado" — the card never
 * fills a gap with a plausible-looking number.
 */
@Composable
fun AudioInfoCard(
    audio: AudioPathUi,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HiFiCard {
            HiFiCardTitle(text = "Source · archivo")
            HiFiInfoRow(label = "Formato", value = audio.sourceFormatLabel)
            HiFiInfoRow(label = "Codec", value = audio.sourceCodecName)
            HiFiInfoRow(
                label = "Pérdida",
                value = audio.sourceIsLossless?.let { if (it) "sin pérdida" else "con pérdida" },
            )
            HiFiInfoRow(label = "Canales", value = audio.sourceChannels)
            HiFiInfoRow(label = "Bitrate", value = audio.sourceBitrate)
        }

        HiFiCard {
            HiFiCardTitle(text = "Decode · decodificador")
            HiFiInfoRow(label = "PCM decodificado", value = audio.decodedFormatLabel)
            HiFiInfoRow(label = "Decoder", value = audio.decoderName)
            HiFiInfoRow(
                label = "Remuestreo",
                value = if (audio.resampled) "sí" else "no",
            )
            HiFiInfoRow(
                label = "Mezcla de canales",
                value = if (audio.downmixed) "sí (downmix)" else "no",
            )
        }

        HiFiCard {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                HiFiCardTitle(text = "Output · salida", modifier = Modifier.weight(1f))
                HiFiBadge(text = audio.outputStatusLabel, active = audio.outputStatusLabel == "Direct")
            }
            HiFiInfoRow(label = "Formato entregado", value = audio.outputFormatLabel)
            HiFiInfoRow(label = "Dispositivo", value = audio.outputDeviceName)
            HiFiInfoRow(label = "Tipo de salida", value = audio.outputDeviceType)
            HiFiInfoRow(label = "Cadena activa", value = audio.dspChainDescription)
            HiFiInfoRow(label = "ReplayGain", value = audio.replayGainLabel)
            HiFiInfoRow(
                label = "EQ",
                value = if (audio.eqActive) "activo" else "fuera de la cadena",
            )
            HiFiInfoRow(label = "Crossfeed", value = audio.crossfeedLabel)
            HiFiInfoRow(label = "Ganancia aplicada", value = audio.appliedGainLabel)
            if (audio.clippingPrevented) {
                Text(
                    text = "Se redujo la ganancia para evitar clipping en esta pista.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalHiFiExtraColors.current.warning,
                )
            }
        }

        if (audio.note != null) {
            Text(
                text = audio.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The "Audio" entry list of the player (requirement 24).
 *
 * Only the entries that really exist today are shown as content; the rest are listed with the phase
 * that will implement them. They are deliberately *not* buttons: a row that looks pressable but
 * does nothing would violate requirement 46.
 */
@Composable
fun AudioPendingList(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HiFiPendingNotice(text = "Pendiente · Ecualizador paramétrico de 10+ bandas (fase 12)")
        HiFiPendingNotice(text = "Pendiente · ReplayGain OFF/TRACK/ALBUM (fase 11)")
        HiFiPendingNotice(text = "Pendiente · Crossfeed OFF/BAJO/MEDIO/ALTO (fase 13)")
        HiFiPendingNotice(text = "Pendiente · Selector de dispositivo de salida y USB DAC (fase 9)")
        Text(
            text = "BIT-PERFECT: no verificado en esta configuración de audio",
            style = TechLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
