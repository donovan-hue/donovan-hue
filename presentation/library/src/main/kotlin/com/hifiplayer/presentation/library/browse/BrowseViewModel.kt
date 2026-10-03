package com.hifiplayer.presentation.library.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.logging.Logger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.onFailure
import com.hifiplayer.domain.model.error.AppError
import com.hifiplayer.domain.model.library.Album
import com.hifiplayer.domain.model.library.Artist
import com.hifiplayer.domain.model.library.Genre
import com.hifiplayer.domain.model.library.LibraryFolder
import com.hifiplayer.domain.model.library.LibraryStats
import com.hifiplayer.domain.model.library.ScanProgress
import com.hifiplayer.domain.model.library.SortDirection
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.library.TrackQuery
import com.hifiplayer.domain.model.library.TrackSort
import com.hifiplayer.domain.model.playback.QueueOrigin
import com.hifiplayer.domain.repository.MusicRepository
import com.hifiplayer.domain.usecase.favorites.ObserveFavoriteIdsUseCase
import com.hifiplayer.domain.usecase.favorites.ToggleFavoriteUseCase
import com.hifiplayer.domain.usecase.library.GetAlbumsUseCase
import com.hifiplayer.domain.usecase.library.GetArtistsUseCase
import com.hifiplayer.domain.usecase.library.GetFoldersUseCase
import com.hifiplayer.domain.usecase.library.GetGenresUseCase
import com.hifiplayer.domain.usecase.library.GetLibraryStatsUseCase
import com.hifiplayer.domain.usecase.library.GetTracksUseCase
import com.hifiplayer.domain.usecase.library.RefreshLibraryUseCase
import com.hifiplayer.domain.usecase.playback.PlayTracksUseCase
import com.hifiplayer.domain.usecase.queue.AddToQueueUseCase
import com.hifiplayer.domain.usecase.queue.PlayNextUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Browsing the library (requirement 25).
 *
 * The song list is produced by [TrackQuery], which the repository turns into SQL: sorting and
 * filtering happen in the database, so a library with thousands of tracks stays responsive and the
 * screen never sorts a big list on the main thread (requirement 37).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowseViewModel(
    private val musicRepository: MusicRepository,
    getTracks: GetTracksUseCase,
    getAlbums: GetAlbumsUseCase,
    getArtists: GetArtistsUseCase,
    getGenres: GetGenresUseCase,
    getFolders: GetFoldersUseCase,
    getStats: GetLibraryStatsUseCase,
    observeFavoriteIds: ObserveFavoriteIdsUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
    private val refreshLibrary: RefreshLibraryUseCase,
    private val playTracks: PlayTracksUseCase,
    private val playNext: PlayNextUseCase,
    private val addToQueue: AddToQueueUseCase,
    private val logger: Logger = AppLogger.logger(),
) : ViewModel() {

    private val tab = MutableStateFlow(LibraryTab.SONGS)
    private val sort = MutableStateFlow(TrackSort.TITLE)
    private val favoritesOnly = MutableStateFlow(false)
    private val losslessOnly = MutableStateFlow(false)
    private val message = MutableStateFlow<Message?>(null)

    /**
     * The query is rebuilt whenever a control changes, and `distinctUntilChanged` keeps Room from
     * re-running the same query when the user taps the filter that is already active.
     */
    private val query = combine(sort, favoritesOnly, losslessOnly) { sort, favorites, lossless ->
        TrackQuery(
            sort = sort,
            direction = SortDirection.ASCENDING,
            favoritesOnly = favorites,
            losslessOnly = lossless,
        )
    }.distinctUntilChanged()

    private val songs = query.flatMapLatest { trackQuery -> getTracks(trackQuery) }

    val state: StateFlow<BrowseUiState> = combine(
        combine(tab, songs, getAlbums(), getArtists(), getGenres()) { tab, songs, albums, artists, genres ->
            Part1(tab, songs, albums, artists, genres)
        },
        combine(getFolders(), observeFavoriteIds(), getStats(), musicRepository.scanProgress) { folders, favorites, stats, scan ->
            Part2(folders, favorites, stats, scan)
        },
        combine(sort, favoritesOnly, losslessOnly) { s, f, l -> Triple(s, f, l) },
        message,
    ) { one, two, filters, currentMessage ->
        BrowseUiState(
            tab = one.tab,
            songs = one.songs,
            albums = one.albums,
            artists = one.artists,
            genres = one.genres,
            folders = two.folders,
            favoriteIds = two.favoriteIds,
            sort = filters.first,
            favoritesOnly = filters.second,
            losslessOnly = filters.third,
            stats = two.stats,
            scan = two.scan,
            loading = false,
            message = currentMessage?.text,
            messageIsError = currentMessage?.isError == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

    // ------------------------------------------------------------------ controls

    fun onSelectTab(value: LibraryTab) {
        tab.value = value
    }

    fun onSelectSort(value: TrackSort) {
        sort.value = value
    }

    fun onToggleFavoritesFilter() {
        favoritesOnly.value = !favoritesOnly.value
    }

    fun onToggleLosslessFilter() {
        losslessOnly.value = !losslessOnly.value
    }

    fun onRefresh() = launch { refreshLibrary(force = true) }

    // ------------------------------------------------------------------ playback

    /** Plays the visible song list from [track], so the queue follows what the user is looking at. */
    fun onPlaySong(track: Track, visible: List<Track>, origin: QueueOrigin = QueueOrigin.ALL_TRACKS) {
        viewModelScope.launch {
            val index = visible.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            playTracks(visible, index, origin).onFailure(::report)
        }
    }

    fun onPlayAll(tracks: List<Track>, origin: QueueOrigin = QueueOrigin.ALL_TRACKS) {
        viewModelScope.launch {
            playTracks(tracks, 0, origin).onFailure(::report)
        }
    }

    fun onPlayNext(track: Track) = launch { playNext(track) }

    fun onAddToQueue(track: Track) = launch { addToQueue(track) }

    fun onToggleFavorite(track: Track) = launch { toggleFavorite(track.id) }

    fun onDismissMessage() {
        message.value = null
    }

    private fun launch(block: suspend () -> Outcome<*>) {
        viewModelScope.launch { block().onFailure(::report) }
    }

    private fun report(error: AppError) {
        logger.w(TAG, "Acción de biblioteca fallida: ${error.code}")
        message.value = Message(error.userMessage, isError = true)
    }

    private data class Part1(
        val tab: LibraryTab,
        val songs: List<Track>,
        val albums: List<Album>,
        val artists: List<Artist>,
        val genres: List<Genre>,
    )

    private data class Part2(
        val folders: List<LibraryFolder>,
        val favoriteIds: Set<String>,
        val stats: LibraryStats,
        val scan: ScanProgress,
    )

    private data class Message(val text: String, val isError: Boolean)

    private companion object {
        const val TAG = "Browse"
    }
}
