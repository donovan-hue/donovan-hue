package com.hifiplayer.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.designsystem.theme.LocalHiFiDimens
import com.hifiplayer.core.designsystem.theme.LocalHiFiExtraColors
import com.hifiplayer.core.designsystem.theme.TechLabelStyle
import com.hifiplayer.core.designsystem.theme.TechValueStyle

/**
 * Screen header. Kept deliberately plain: title, optional technical subtitle, optional back
 * arrow and actions. No elevation, no gradient (requirements 28 and 33).
 */
@Composable
fun HiFiTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Opaca: en las pantallas que son pestaña el contenido pasa por debajo al desplazar, y sin
            // fondo se veía el texto y los interruptores dibujados encima del título (visto en una
            // captura del emulador). Con el fondo del tema, lo que pasa por debajo simplemente no se ve.
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = LocalHiFiDimens.current.screenPadding)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(LocalHiFiDimens.current.touchTarget)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver",
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun HiFiSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailingLabel: String? = null,
    onTrailingClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LocalHiFiDimens.current.screenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (trailingLabel != null && onTrailingClick != null) {
            // A real action only: this button exists when there is something to navigate to.
            TextButton(onClick = onTrailingClick) { Text(trailingLabel) }
        }
    }
}

/**
 * Empty state used by every list. [message] must explain *why* it is empty and what to do next —
 * an empty screen that says nothing is a bug in this app, not a design choice.
 */
@Composable
fun HiFiEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(LocalHiFiDimens.current.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * One measured value of the audio path.
 *
 * [value] is nullable on purpose: when a specification was not detected, the row says so instead of
 * printing a plausible number (requirements 14 and 30 — never show an undetected spec).
 */
@Composable
fun HiFiInfoRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    unknownLabel: String = "no detectado",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label.uppercase(),
            style = TechLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value ?: unknownLabel,
            style = TechValueStyle,
            color = if (value != null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                LocalHiFiExtraColors.current.unknown
            },
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.3f),
        )
    }
}

/** Flat panel used by the audio information cards. */
@Composable
fun HiFiCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
fun HiFiCardTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = TechLabelStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 6.dp),
    )
}

/** Badge for the bit-perfect banner and for output status (Direct / Converted). */
@Composable
fun HiFiBadge(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val background = if (active) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = text.uppercase(), style = TechLabelStyle, color = foreground)
    }
}

/**
 * Marks a feature that is not implemented yet (requirement 46).
 *
 * The alternative — showing a button that does nothing — is forbidden by the specification, so
 * anything pending says so out loud, including the phase it belongs to.
 */
@Composable
fun HiFiPendingNotice(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalHiFiExtraColors.current.warning,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

/** Large, accessible transport control. The tint marks "active" without an animation. */
@Composable
fun HiFiTransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = LocalHiFiDimens.current.transportButton,
    enabled: Boolean = true,
    active: Boolean = false,
    isPrimary: Boolean = false,
    tint: Color? = null,
) {
    val tintColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        tint != null -> tint
        isPrimary -> MaterialTheme.colorScheme.primary
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onBackground
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tintColor,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** Seek slider. Disabled while the duration is unknown, because seeking needs a real duration. */
@Composable
fun HiFiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    // The control works in fractions because that is what a slider is; the range it represents is
    // decided by the caller, so a negative dB range is expressed in the same units it is displayed.
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    Slider(
        value = ((value - valueRange.start) / span).coerceIn(0f, 1f),
        onValueChange = { fraction -> onValueChange(valueRange.start + fraction * span) },
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledActiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    )
}
