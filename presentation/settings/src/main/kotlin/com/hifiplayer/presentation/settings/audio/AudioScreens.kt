package com.hifiplayer.presentation.settings.audio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.designsystem.component.HiFiBadge
import com.hifiplayer.core.designsystem.component.HiFiCard
import com.hifiplayer.core.designsystem.component.HiFiCardTitle
import com.hifiplayer.core.designsystem.component.HiFiEmptyState
import com.hifiplayer.core.designsystem.component.HiFiInfoRow
import com.hifiplayer.core.designsystem.component.HiFiTopBar
import com.hifiplayer.core.designsystem.theme.LocalHiFiDimens
import com.hifiplayer.core.designsystem.theme.LocalHiFiExtraColors
import com.hifiplayer.core.designsystem.theme.TechLabelStyle
import com.hifiplayer.presentation.settings.controls.SettingsNote
import com.hifiplayer.presentation.settings.controls.SettingsSwitchRow

/**
 * Output devices (phase 9).
 *
 * The list contains what the system reports right now, and the chosen route is remembered by the
 * settings, not by this screen. A route whose permission was revoked says so, and instead of silently
 * falling back to the speaker the app offers the permission request.
 */
@Composable
fun OutputDevicesScreen(
    state: AudioDeviceUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onUseSystemDefault: () -> Unit,
    onRequestPermission: (String) -> Unit,
    onUsbAutoRouteChange: (Boolean) -> Unit,
    onPauseOnDisconnectChange: (Boolean) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalHiFiDimens.current

    Column(modifier = modifier.fillMaxSize()) {
        HiFiTopBar(
            title = "Dispositivo de salida",
            subtitle = state.activeOutputName?.let { "sonando por $it" },
            onBack = onBack,
            actions = { TextButton(onClick = onRefresh) { Text("Actualizar") } },
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "auto") {
                OutputRow(
                    title = "Automático (lo que decida el sistema)",
                    subtitle = "Sigue la salida activa: altavoz, auriculares, Bluetooth o USB DAC.",
                    selected = state.activeOutputId == null && state.playback.preferredOutputDeviceId == null,
                    badge = null,
                    onClick = onUseSystemDefault,
                    actionLabel = null,
                    onAction = null,
                )
            }

            if (state.outputs.isEmpty()) {
                item(key = "empty") {
                    HiFiEmptyState(
                        icon = Icons.Filled.SurroundSound,
                        title = "Sin salidas detectadas",
                        message = "El sistema no ha reportado ninguna ruta de audio todavía. Pulsa Actualizar.",
                    )
                }
            } else {
                items(state.outputs, key = { it.device.id }) { row ->
                    OutputRow(
                        title = row.device.name,
                        subtitle = row.capabilityLine,
                        selected = row.device.id == state.activeOutputId,
                        badge = when {
                            row.device.isSelectedByUser -> "fijada por ti"
                            row.device.isActive -> "activa"
                            else -> null
                        },
                        onClick = { onSelectDevice(row.device.id) },
                        actionLabel = row.permissionNote?.let {
                            if (row.device.hasPermission) null else "Conceder permiso"
                        },
                        onAction = { onRequestPermission(row.device.id) },
                        note = row.permissionNote,
                    )
                }
            }

            item(key = "device-detail") {
                HiFiCard(modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 6.dp)) {
                    HiFiCardTitle("Lo que admite esta ruta")
                    HiFiInfoRow(label = "Ruta", value = state.capabilities?.routeName)
                    HiFiInfoRow(label = "Tipo", value = state.capabilities?.routeType?.displayName)
                    HiFiInfoRow(
                        label = "Frecuencias",
                        value = state.capabilities?.supportedSampleRates
                            ?.sorted()
                            ?.takeIf { it.isNotEmpty() }
                            ?.joinToString(", ") { "${"%.1f".format(it / 1000.0)} kHz" },
                    )
                    HiFiInfoRow(
                        label = "Profundidades",
                        value = state.capabilities?.supportedBitDepths
                            ?.sorted()
                            ?.takeIf { it.isNotEmpty() }
                            ?.joinToString(", ") { "$it-bit" },
                    )
                    HiFiInfoRow(
                        label = "Canales",
                        value = state.capabilities?.maxChannelCount?.toString(),
                    )
                    HiFiInfoRow(
                        label = "Mezclador sin mezcla",
                        value = state.capabilities?.let { if (it.supportsBitPerfectMixer) "sí" else "no" },
                    )
                    if (state.capabilities?.usb != null) {
                        val usb = state.capabilities.usb
                        HiFiInfoRow(label = "Fabricante USB", value = usb?.manufacturer)
                        HiFiInfoRow(label = "Producto USB", value = usb?.productName)
                        HiFiInfoRow(label = "VID:PID", value = usb?.vendorId?.let { v -> usb.productId?.let { p -> "%04x:%04x".format(v, p) } })
                    }
                    state.capabilities?.warnings?.forEach { warning ->
                        SettingsNote(text = warning, isWarning = true)
                    }
                }
            }

            item(key = "behavior") {
                SettingsSwitchRow(
                    title = "Cambiar a un DAC USB al conectarlo",
                    checked = state.playback.usbAutoRoute,
                    onCheckedChange = onUsbAutoRouteChange,
                    subtitle = "Si aparece un DAC USB, se convierte en la salida activa.",
                )
            }
            item(key = "behavior-pause") {
                SettingsSwitchRow(
                    title = "Pausar si se desconecta la salida",
                    checked = state.playback.pauseOnOutputDisconnect,
                    onCheckedChange = onPauseOnDisconnectChange,
                    subtitle = "Al desenchufar, la reproducción se pausa en lugar de cambiar de altavoz sola.",
                )
            }

            if (state.message != null) {
                item(key = "message") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = dimens.screenPadding, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.messageIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissMessage) { Text("Entendido") }
                    }
                }
            }

            item(key = "bottom") { Box(modifier = Modifier.height(dimens.sectionSpacing)) }
        }
    }
}

@Composable
private fun OutputRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    badge: String?,
    onClick: () -> Unit,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    note: String? = null,
) {
    val dimens = LocalHiFiDimens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.screenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (badge != null) {
                    HiFiBadge(text = badge, active = selected)
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = TechLabelStyle,
                    // Ni rojo: "necesita permiso" no es un fallo, es un paso que falta y que el
                    // propio botón de al lado resuelve.
                    color = if (note.startsWith("necesita")) {
                        LocalHiFiExtraColors.current.warning
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * Bit-perfect (phase 10).
 *
 * The screen separates the two facts that must never be merged: what the user asked for (a setting)
 * and what the platform confirmed (a measurement). When it is not active, the reasons are listed one
 * by one, and the evidence block shows the raw answers from the system.
 */
@Composable
fun BitPerfectScreen(
    state: AudioDeviceUiState,
    onBack: () -> Unit,
    onBitPerfectChange: (Boolean) -> Unit,
    onOpenOutputs: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalHiFiDimens.current
    val bitPerfect = state.bitPerfect

    Column(modifier = modifier.fillMaxSize()) {
        HiFiTopBar(
            title = "Bit-perfect",
            subtitle = if (bitPerfect.isActive) "confirmado por el sistema" else "no activo",
            onBack = onBack,
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "switch") {
                Box(modifier = Modifier.padding(horizontal = dimens.screenPadding)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (bitPerfect.isActive) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                },
                                RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = bitPerfect.bannerTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = bitPerfect.bannerDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = state.playback.bitPerfectEnabled,
                            onCheckedChange = onBitPerfectChange,
                        )
                    }
                }
            }

            item(key = "explanation") {
                SettingsNote(
                    text = "Con bit-perfect activado, la app apaga el ecualizador, el ReplayGain, el crossfeed, " +
                        "la ganancia y cualquier conversión, y pide al sistema una ruta sin mezclador. Si el " +
                        "sistema no la concede, se muestra como no disponible: la app nunca dirá que lo consiguió " +
                        "si no lo comprobó.",
                    isWarning = false,
                )
            }

            item(key = "state") {
                HiFiCard(modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 6.dp)) {
                    HiFiCardTitle("Estado medido")
                    HiFiInfoRow(label = "Ruta", value = bitPerfect.routeName ?: state.activeOutputName)
                    HiFiInfoRow(label = "Solicitado", value = if (bitPerfect.requested) "sí" else "no")
                    HiFiInfoRow(label = "Activo", value = if (bitPerfect.isActive) "sí" else "no")
                    HiFiInfoRow(label = "Soporte", value = bitPerfect.support.displayName)
                    HiFiInfoRow(label = "Formato de origen", value = bitPerfect.sourceFormat?.label)
                    HiFiInfoRow(label = "Mezclador aplicado", value = bitPerfect.mixerFormat?.label)
                    HiFiInfoRow(label = "Formato entregado", value = bitPerfect.deliveredFormat?.label)
                    HiFiInfoRow(
                        label = "Procesamiento",
                        value = if (bitPerfect.dspActive) bitPerfect.dspChainDescription.ifBlank { "activo" } else "ninguno",
                    )
                }
            }

            if (bitPerfect.blockers.isNotEmpty()) {
                item(key = "blockers-title") {
                    HiFiCardTitle(
                        text = "Qué lo impide ahora mismo",
                        modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 6.dp),
                    )
                }
                items(bitPerfect.blockers, key = { it.name }) { blocker ->
                    SettingsNote(text = blocker.explanation, isWarning = true)
                }
            }

            if (bitPerfect.evidence.isNotEmpty()) {
                item(key = "evidence") {
                    HiFiCard(modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 6.dp)) {
                        HiFiCardTitle("Respuestas del sistema")
                        bitPerfect.evidence.forEach { line ->
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

            item(key = "outputs") {
                SettingsNote(
                    text = "Bit-perfect solo puede confirmarse en una salida por cable o USB: por Bluetooth o por " +
                        "el altavoz del teléfono, el propio sistema mezcla y recomprime la señal.",
                    isWarning = false,
                )
            }
            item(key = "open-outputs") {
                Box(modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 4.dp)) {
                    TextButton(onClick = onOpenOutputs) { Text("Ver y elegir la salida") }
                }
            }

            if (state.message != null) {
                item(key = "message") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = dimens.screenPadding, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissMessage) { Text("Entendido") }
                    }
                }
            }

            item(key = "bottom") { Box(modifier = Modifier.height(dimens.sectionSpacing)) }
        }
    }
}
