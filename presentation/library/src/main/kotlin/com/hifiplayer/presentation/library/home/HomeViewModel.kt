package com.hifiplayer.presentation.library.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.logging.Logger
import com.hifiplayer.core.common.result.onFailure
import com.hifiplayer.domain.model.library.Album
import com.hifiplayer.domain.model.library.LibraryStats
import com.hifiplayer.domain.model.library.ScanProgress
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.repository.MusicRepository
import com.hifiplayer.domain.usecase.favorites.GetFavoriteTracksUseCase
import com.hifiplayer.domain.usecase.favorites.ObserveFavoriteIdsUseCase
import com.hifiplayer.domain.usecase.favorites.ToggleFavoriteUseCase
import com.hifiplayer.domain.usecase.library.GetAlbumsUseCase
import com.hifiplayer.domain.usecase.library.GetLibraryStatsUseCase
import com.hifiplayer.domain.usecase.library.GetRecentlyAddedUseCase
import com.hifiplayer.domain.usecase.library.GetRecentlyPlayedUseCase
import com.hifiplayer.domain.usecase.library.RefreshLibraryUseCase
import com.hifiplayer.domain.usecase.playback.PlayTracksUseCase
import com.hifiplayer.domain.usecase.queue.AddTracksToQueueUseCase
import com.hifiplayer.domain.usecase.queue.AddToQueueUseCase
import com.hifiplayer.domain.usecase.queue.PlayNextUseCase
import com.hifiplayer.domain.model.playback.QueueOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home (Rule 47: library surface).
 *
 * Everything shown here comes from the library that was already scanned: no demo data, no
 * placeholders. When the library is empty the screen says how to fill it (the scanner takes SAF
 * folders) instead of showing empty carousels.
 */
class HomeViewModel(
    private val musicRepository: MusicRepository,
    getRecentlyPlayed: GetRecentlyPlayedUseCase,
    getRecentlyAdded: GetRecentlyAddedUseCase,
    getFavorites: GetFavoriteTracksUseCase,
    getAlbums: GetAlbumsUseCase,
    getStats: GetLibraryStatsUseCase,
    observeFavoriteIds: ObserveFavoriteIdsUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
    private val refreshLibrary: RefreshLibraryUseCase,
    private val playTracks: PlayTracksUseCase,
    private val playNext: PlayNextUseCase,
    private val addToQueue: AddToQueueUseCase,
    private val addTracksToQueue: AddTracksToQueueUseCase,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger = AppLogger.logger(),
) : ViewModel() {

    private val message = MutableStateFlow<Message?>(null)

    /**
     * The library snapshot is combined in three steps with named types.
     *
     * `combine` only has typed overloads up to five flows, and casting a mixed array would be the
     * kind of thing that compiles once and breaks silently later, so the groups are explicit.
     */
    private val librarySnapshot = combine(
        getRecentlyPlayed(RECENT_LIMIT),
        getRecentlyAdded(RECENT_LIMIT),
        getFavorites(),
        getAlbums(),
        getStats(),
    ) { played, added, favorites, albums, stats ->
        SnapshotPart1(played, added, favorites, albums, stats)
    }.combine(observeFavoriteIds()) { part, favoriteIds ->
        SnapshotPart2(part, favoriteIds)
    }.combine(musicRepository.scanProgress) { part, scan ->
        LibrarySnapshot(
            recentlyPlayed = part.first.recentlyPlayed,
            recentlyAdded = part.first.recentlyAdded,
            favorites = part.first.favorites,
            albums = part.first.albums,
            stats = part.first.stats,
            favoriteIds = part.favoriteIds,
            scan = scan,
        )
    }

    val state: StateFlow<HomeUiState> = combine(librarySnapshot, message) { snapshot, currentMessage ->
        HomeUiState(
            recentlyPlayed = snapshot.recentlyPlayed,
            recentlyAdded = snapshot.recentlyAdded,
            favorites = snapshot.favorites,
            albums = snapshot.albums,
            stats = snapshot.stats,
            favoriteIds = snapshot.favoriteIds,
            scan = snapshot.scan,
            isLibraryEmpty = snapshot.stats.trackCount == 0 && !snapshot.scan.isRunning,
            message = currentMessage?.text,
            messageIsError = currentMessage?.isError == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Plays a whole section starting at the tapped track, so "next" continues down the list. */
    fun onPlayFromSection(tracks: List<Track>, track: Track, origin: QueueOrigin) {
        viewModelScope.launch {
            val index = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            playTracks(tracks, index, origin).onFailure(::report)
        }
    }

    fun onPlayNext(track: Track) = launch { playNext(track) }

    fun onAddToQueue(track: Track) = launch { addToQueue(track) }

    fun onAddAllToQueue(tracks: List<Track>, origin: QueueOrigin) = launch {
        val outcome = addTracksToQueue(tracks, next = false)
        if (outcome.isSuccess) {
            logger.i(TAG, "Añadidas ${tracks.size} pistas a la cola desde ${origin.name}")
        }
        outcome
    }

    fun onToggleFavorite(track: Track) = launch { toggleFavorite(track.id) }

    /** Rescans the library. The scanner reports its own progress through [MusicRepository]. */
    fun onRefresh() = launch { refreshLibrary(force = true) }

    fun onDismissMessage() {
        message.value = null
    }

    private fun launch(block: suspend () -> com.hifiplayer.core.common.result.Outcome<*>) {
        viewModelScope.launch {
            block().onFailure(::report)
        }
    }

    private fun report(error: com.hifiplayer.domain.model.error.AppError) {
        logger.w(TAG, "Acción de biblioteca fallida: ${error.code}")
        message.value = Message(error.userMessage, isError = true)
    }

    private data class SnapshotPart1(
        val recentlyPlayed: List<Track>,
        val recentlyAdded: List<Track>,
        val favorites: List<Track>,
        val albums: List<Album>,
        val stats: LibraryStats,
    )

    private data class SnapshotPart2(val first: SnapshotPart1, val favoriteIds: Set<String>)

    private data class LibrarySnapshot(
        val recentlyPlayed: List<Track>,
        val recentlyAdded: List<Track>,
        val favorites: List<Track>,
        val albums: List<Album>,
        val stats: LibraryStats,
        val favoriteIds: Set<String>,
        val scan: ScanProgress,
    )

    private data class Message(val text: String, val isError: Boolean)

    private companion object {
        const val TAG = "Home"
        const val RECENT_LIMIT = 20
    }
}
