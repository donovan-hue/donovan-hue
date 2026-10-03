package com.hifiplayer.presentation.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class TabSpec(
    val destination: HiFiDestination,
    val label: String,
    val icon: ImageVector,
)

private val TABS = listOf(
    TabSpec(HiFiDestination.Home, "Inicio", Icons.Filled.Home),
    TabSpec(HiFiDestination.Library, "Biblioteca", Icons.Filled.LibraryMusic),
    TabSpec(HiFiDestination.Playlists, "Listas", Icons.Filled.QueueMusic),
    TabSpec(HiFiDestination.Settings, "Ajustes", Icons.Filled.Tune),
)

/**
 * Bottom navigation (requirement 25). The mini player is drawn directly above this bar by
 * [HiFiApp], so the two never overlap the content underneath.
 */
@Composable
fun HiFiBottomBar(
    currentRoute: String?,
    onSelect: (HiFiDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TABS.forEach { tab ->
                NavigationBarItem(
                    selected = currentRoute == tab.destination.route,
                    onClick = { onSelect(tab.destination) },
                    icon = { Icon(imageVector = tab.icon, contentDescription = null) },
                    label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}
