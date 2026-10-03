package com.hifiplayer.presentation.library.search

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hifiplayer.core.designsystem.component.HiFiChip
import com.hifiplayer.core.designsystem.component.HiFiChipRow
import com.hifiplayer.core.designsystem.component.HiFiEmptyState
import com.hifiplayer.core.designsystem.component.HiFiSearchField
import com.hifiplayer.core.designsystem.component.HiFiTopBar
import com.hifiplayer.core.designsystem.theme.LocalHiFiDimens
import com.hifiplayer.core.designsystem.theme.TechLabelStyle
import com.hifiplayer.domain.model.library.SearchHit
import com.hifiplayer.domain.model.library.SearchHitType

data class SearchUiState(
    val term: String = "",
    val hits: List<SearchHit> = emptyList(),
    val availableTypes: List<SearchHitType> = emptyList(),
    val selectedType: SearchHitType? = null,
    val searching: Boolean = false,
    val emptyQuery: Boolean = true,
    val noMatches: Boolean = false,
    val error: String? = null,
    val scannedTracks: Int = 0,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

/** Global search over the library (requirement 26). */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onTermChange: (String) -> Unit,
    onSelectType: (SearchHitType?) -> Unit,
    onOpenHit: (SearchHit) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalHiFiDimens.current

    Column(modifier = modifier.fillMaxSize()) {
        HiFiTopBar(
            title = "Buscar",
            subtitle = if (state.searching) "buscando…" else null,
            onBack = onBack,
        )

        Box(modifier = Modifier.padding(horizontal = dimens.screenPadding, vertical = 4.dp)) {
            HiFiSearchField(
                value = state.term,
                onValueChange = onTermChange,
                placeholder = "Pistas, álbumes, artistas…",
            )
        }

        if (state.availableTypes.size > 1) {
            HiFiChipRow(
                options = state.availableTypes,
                selected = state.selectedType ?: state.availableTypes.first(),
                label = { it.displayName },
                onSelect = { onSelectType(it) },
            )
        }

        when {
            state.emptyQuery -> HiFiEmptyState(
                icon = Icons.Filled.Search,
                title = "Buscar en tu biblioteca",
                message = "Escribe al menos dos letras. La búsqueda se ejecuta 300 ms después de la " +
                    "última tecla, así que no se consulta la base de datos en cada pulsación.",
            )

            state.error != null -> HiFiEmptyState(
                icon = Icons.Filled.Search,
                title = "No se pudo buscar",
                message = state.error,
            )

            state.noMatches -> HiFiEmptyState(
                icon = Icons.Filled.Search,
                title = "Sin resultados para \"${state.term}\"",
                message = "Se buscó en ${state.scannedTracks} pistas ya analizadas. Si acabas de añadir " +
                    "música, el escaneo puede seguir en curso.",
            )

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.hits, key = { "${it.type}-${it.id}" }) { hit ->
                    SearchHitRow(hit = hit, onClick = { onOpenHit(hit) })
                }
                item(key = "bottom") { Box(modifier = Modifier.height(dimens.sectionSpacing)) }
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: SearchHit, onClick: () -> Unit) {
    val dimens = LocalHiFiDimens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.screenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = iconFor(hit.type),
            contentDescription = hit.type.displayName,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hit.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = hit.subtitle
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
        Text(
            text = hit.type.displayName,
            style = TechLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun iconFor(type: SearchHitType): ImageVector = when (type) {
    SearchHitType.TRACK -> Icons.Filled.MusicNote
    SearchHitType.ALBUM -> Icons.Filled.Album
    SearchHitType.ARTIST -> Icons.Filled.Person
    SearchHitType.GENRE -> Icons.Filled.Tune
    SearchHitType.FOLDER -> Icons.Filled.Folder
    SearchHitType.PLAYLIST -> Icons.AutoMirrored.Filled.PlaylistPlay
}
