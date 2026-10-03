package com.hifiplayer.presentation.library.home

import com.hifiplayer.domain.model.library.Album
import com.hifiplayer.domain.model.library.LibraryStats
import com.hifiplayer.domain.model.library.ScanProgress
import com.hifiplayer.domain.model.library.Track

/**
 * Home (requirement 24).
 *
 * Each section carries its own list, and a section with no content is simply not drawn — an empty
 * carousel that says nothing is noise. The scan banner only appears while a scan is really running.
 */
data class HomeUiState(
    val recentlyPlayed: List<Track> = emptyList(),
    val recentlyAdded: List<Track> = emptyList(),
    val favorites: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val stats: LibraryStats? = null,
    val favoriteIds: Set<String> = emptySet(),
    val scan: ScanProgress = ScanProgress.IDLE,
    val isLibraryEmpty: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    val showScanBanner: Boolean get() = scan.isRunning
    val scanLabel: String
        get() = if (scan.total > 0) {
            "${scan.phase.displayName}… ${scan.processed}/${scan.total}"
        } else {
            scan.phase.displayName
        }
}

/** Compact description of the library shown under the header, built from real counts. */
fun HomeUiState.librarySummary(): String? {
    val s = stats ?: return null
    if (s.trackCount == 0) return null
    val tracks = if (s.trackCount == 1) "1 pista" else "${s.trackCount} pistas"
    val albums = if (s.albumCount == 1) "1 álbum" else "${s.albumCount} álbumes"
    val hiRes = if (s.highResolutionCount > 0) " · ${s.highResolutionCount} en alta resolución" else ""
    return "$tracks · $albums$hiRes"
}
