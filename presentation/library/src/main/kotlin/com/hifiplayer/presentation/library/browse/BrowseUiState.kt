package com.hifiplayer.presentation.library.browse

import com.hifiplayer.domain.model.library.Album
import com.hifiplayer.domain.model.library.Artist
import com.hifiplayer.domain.model.library.Genre
import com.hifiplayer.domain.model.library.LibraryFolder
import com.hifiplayer.domain.model.library.LibraryStats
import com.hifiplayer.domain.model.library.ScanProgress
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.library.TrackSort

/** The five ways the specification asks the library to be browsable (requirement 25). */
enum class LibraryTab(val label: String) {
    SONGS("Canciones"),
    ALBUMS("Álbumes"),
    ARTISTS("Artistas"),
    GENRES("Géneros"),
    FOLDERS("Carpetas"),
}

data class BrowseUiState(
    val tab: LibraryTab = LibraryTab.SONGS,
    val songs: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val folders: List<LibraryFolder> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val sort: TrackSort = TrackSort.TITLE,
    val favoritesOnly: Boolean = false,
    val losslessOnly: Boolean = false,
    val stats: LibraryStats? = null,
    val scan: ScanProgress = ScanProgress.IDLE,
    val loading: Boolean = true,
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    val countLabel: String
        get() = when (tab) {
            LibraryTab.SONGS -> countOf(songs.size, "pista", "pistas")
            LibraryTab.ALBUMS -> countOf(albums.size, "álbum", "álbumes")
            LibraryTab.ARTISTS -> countOf(artists.size, "artista", "artistas")
            LibraryTab.GENRES -> countOf(genres.size, "género", "géneros")
            LibraryTab.FOLDERS -> countOf(folders.size, "carpeta", "carpetas")
        }

    val isEmpty: Boolean
        get() = when (tab) {
            LibraryTab.SONGS -> songs.isEmpty()
            LibraryTab.ALBUMS -> albums.isEmpty()
            LibraryTab.ARTISTS -> artists.isEmpty()
            LibraryTab.GENRES -> genres.isEmpty()
            LibraryTab.FOLDERS -> folders.isEmpty()
        }

    private fun countOf(value: Int, singular: String, plural: String): String =
        if (value == 1) "1 $singular" else "$value $plural"
}

/** Filtering applied when a list is empty for a reason the user chose, not because there is no data. */
fun BrowseUiState.emptyMessage(): String = when {
    favoritesOnly -> "No hay favoritos entre las pistas que cumplen el filtro. Prueba a quitar los filtros."
    losslessOnly -> "Ninguna pista cumple el filtro de sin pérdida. Prueba a quitar los filtros."
    else -> "La biblioteca todavía no tiene contenido en esta vista."
}
