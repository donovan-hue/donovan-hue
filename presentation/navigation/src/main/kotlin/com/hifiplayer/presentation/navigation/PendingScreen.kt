package com.hifiplayer.presentation.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.designsystem.component.HiFiEmptyState
import com.hifiplayer.core.designsystem.component.HiFiTopBar
import com.hifiplayer.core.designsystem.theme.LocalHiFiExtraColors
import com.hifiplayer.core.designsystem.theme.TechLabelStyle

/**
 * Honest placeholder (requirement 46).
 *
 * The specification forbids decorative buttons and fake data, so a screen that is not built yet
 * says exactly that, names the phase that will build it, and offers nothing to press. There is no
 * sample data and there is no "coming soon" button.
 */
@Composable
fun PendingScreen(
    title: String,
    phase: String,
    willContain: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        HiFiTopBar(title = title)
        Text(
            text = "PENDIENTE · $phase",
            style = TechLabelStyle,
            color = LocalHiFiExtraColors.current.warning,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        HiFiEmptyState(
            icon = Icons.Filled.Construction,
            title = "$title todavía no está construido",
            message = willContain,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "Esta pantalla no muestra datos de ejemplo ni botones decorativos: aparecerá " +
                "cuando el módulo real esté conectado y verificado.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}
