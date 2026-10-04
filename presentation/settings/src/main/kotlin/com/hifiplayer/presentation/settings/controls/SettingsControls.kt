package com.hifiplayer.presentation.settings.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.designsystem.component.HiFiChip
import com.hifiplayer.core.designsystem.component.HiFiSlider
import com.hifiplayer.core.designsystem.theme.LocalHiFiDimens
import com.hifiplayer.core.designsystem.theme.LocalHiFiExtraColors
import com.hifiplayer.core.designsystem.theme.TechLabelStyle

/**
 * The settings vocabulary.
 *
 * Every control here is a real control for a real value: a switch writes, a choice writes, a slider
 * writes, and a row with an arrow opens a screen that exists. There is deliberately no "coming soon"
 * row (requirement 46).
 */
@Composable
fun SettingsSectionHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(horizontal = LocalHiFiDimens.current.screenPadding, vertical = 8.dp)) {
        Text(
            text = title.uppercase(),
            style = TechLabelStyle,
            color = MaterialTheme.colorScheme.secondary,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** A switch that writes the setting. [subtitle] explains the consequence, not the label. */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val dimens = LocalHiFiDimens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * Mutually exclusive options shown as flat chips, so the active one is visible without opening.
 *
 * The chips **wrap** onto as many lines as they need. Before, they were laid out in a single row:
 * with labels like "Negro puro (OLED)" and "Seguir al sistema" the row ran out of width and Compose
 * squeezed the last chips down to a bare coloured box — buttons with no text, which is exactly what
 * a user reported ("los botones están vacíos, no pasa nada si los pulsas"). Nothing here may depend
 * on the labels being short.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SettingsChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val dimens = LocalHiFiDimens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding, vertical = 8.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                HiFiChip(
                    label = label(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

/** A slider with its value printed: the number is the point, so it is always visible. */
@Composable
fun SettingsSliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueLabel: String,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = -12f..12f,
    subtitle: String? = null,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val dimens = LocalHiFiDimens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = TechLabelStyle,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HiFiSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.padding(vertical = 2.dp),
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

/** A row that opens another screen. If it is on the screen, it navigates somewhere real. */
@Composable
fun SettingsNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
) {
    val dimens = LocalHiFiDimens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.screenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = TechLabelStyle,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Read-only value row, used for things the app measured (cache size, counts, versions). */
@Composable
fun SettingsValueRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val dimens = LocalHiFiDimens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(text = value, style = TechLabelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * How loud a note is. It matters: painting a fact about the phone in the error colour makes the user
 * think the app is broken (reported: "son varias las que marcan así en rojo ... marca error"). Red is
 * reserved for things that failed. A state the user chose, or a capability the hardware lacks, is a
 * warning or a plain explanation — not a failure.
 */
enum class SettingsNoteLevel { INFO, WARNING, ERROR }

/** Inline note: what the user must be told about, in the colour the fact deserves. */
@Composable
fun SettingsNote(text: String, isWarning: Boolean, modifier: Modifier = Modifier) {
    SettingsNote(
        text = text,
        level = if (isWarning) SettingsNoteLevel.WARNING else SettingsNoteLevel.INFO,
        modifier = modifier,
    )
}

/** Inline note with an explicit level. */
@Composable
fun SettingsNote(text: String, level: SettingsNoteLevel, modifier: Modifier = Modifier) {
    val warningColor = LocalHiFiExtraColors.current.warning
    val (background, foreground) = when (level) {
        SettingsNoteLevel.INFO -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) to
            MaterialTheme.colorScheme.onSurfaceVariant
        SettingsNoteLevel.WARNING -> warningColor.copy(alpha = 0.14f) to warningColor
        SettingsNoteLevel.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f) to
            MaterialTheme.colorScheme.error
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LocalHiFiDimens.current.screenPadding, vertical = 6.dp)
            .background(color = background, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = foreground)
    }
}

/**
 * A note that comes with the action that resolves it.
 *
 * Used for the one case that confuses everybody: an effect that is switched on but not being
 * applied. Explaining it without offering a way out leaves the user stuck ("esa configuración no
 * tiene función"); here the button turns the interfering setting off.
 */
@Composable
fun SettingsNoteWithAction(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    level: SettingsNoteLevel = SettingsNoteLevel.WARNING,
) {
    val warningColor = LocalHiFiExtraColors.current.warning
    val (background, foreground) = when (level) {
        SettingsNoteLevel.INFO -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) to
            MaterialTheme.colorScheme.onSurfaceVariant
        SettingsNoteLevel.WARNING -> warningColor.copy(alpha = 0.14f) to warningColor
        SettingsNoteLevel.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f) to
            MaterialTheme.colorScheme.error
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LocalHiFiDimens.current.screenPadding, vertical = 6.dp)
            .background(color = background, shape = RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = foreground)
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}
