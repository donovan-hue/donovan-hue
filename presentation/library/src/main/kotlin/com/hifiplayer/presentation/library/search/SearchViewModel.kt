package com.hifiplayer.presentation.library.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.logging.Logger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.onFailure
import com.hifiplayer.domain.model.error.AppError
import com.hifiplayer.domain.model.library.SearchHit
import com.hifiplayer.domain.model.library.SearchHitType
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.playback.QueueOrigin
import com.hifiplayer.domain.repository.MusicRepository
import com.hifiplayer.domain.usecase.library.SearchLibraryUseCase
import com.hifiplayer.domain.usecase.playback.PlayTrackUseCase
import com.hifiplayer.domain.usecase.playback.PlayTracksUseCase
import com.hifiplayer.domain.usecase.queue.AddToQueueUseCase
import com.hifiplayer.domain.usecase.queue.PlayNextUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Global search (requirement 26: ~300 ms debounce).
 *
 * The debounce lives in [SearchQuerySource] and not in the text field, so no keystroke ever reaches
 * the database: the query only runs after the user stops typing. This class owns the screen state and
 * the actions on a result; the timing and the cancellation of stale queries are in that source.
 */
class SearchViewModel(
    private val musicRepository: MusicRepository,
    private val searchLibrary: SearchLibraryUseCase,
    private val playTrack: PlayTrackUseCase,
    private val playTracks: PlayTracksUseCase,
    private val playNext: PlayNextUseCase,
    private val addToQueue: AddToQueueUseCase,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger = AppLogger.logger(),
) : ViewModel() {

    private val term = MutableStateFlow("")
    private val message = MutableStateFlow<Message?>(null)
    private val selectedType = MutableStateFlow<SearchHitType?>(null)

    private val querySource = SearchQuerySource(
        search = { query -> searchLibrary(query) },
        logger = logger,
    )

    private val results: StateFlow<SearchState> = querySource
        .states(term)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState.Idle)

    val state: StateFlow<SearchUiState> = combine(
        term,
        results,
        selectedType,
        combine(musicRepository.scanProgress, message) { scan, msg -> scan to msg },
    ) { query, result, type, (scan, currentMessage) ->
        val hits = (result as? SearchState.Ready)?.results?.hits.orEmpty()
        SearchUiState(
            term = query,
            hits = if (type == null) hits else hits.filter { it.type == type },
            availableTypes = hits.map { it.type }.distinct(),
            selectedType = type,
            searching = result is SearchState.Running,
            emptyQuery = query.trim().length < SearchQuerySource.DEFAULT_MIN_LENGTH,
            noMatches = result is SearchState.Ready && hits.isEmpty(),
            error = (result as? SearchState.Failed)?.message,
            scannedTracks = scan.processed,
            message = currentMessage?.text,
            messageIsError = currentMessage?.isError == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun onTermChange(value: String) {
        term.value = value
        if (value.isBlank()) selectedType.value = null
    }

    fun onSelectType(type: SearchHitType?) {
        selectedType.value = if (selectedType.value == type) null else type
    }

    /**
     * Plays a search result.
     *
     * Tracks play directly; an album or a playlist hit is resolved to its tracks first, so the queue
     * is real music and not a placeholder.
     */
    fun onOpenHit(hit: SearchHit, onNavigate: (SearchHit) -> Unit) {
        if (hit.type == SearchHitType.TRACK && hit.trackUri != null) {
            viewModelScope.launch {
                val tracks = playableHits()
                val index = tracks.indexOfFirst { it.id == hit.id }.coerceAtLeast(0)
                if (tracks.isEmpty()) {
                    onNavigate(hit)
                } else {
                    playTracks(tracks, index, QueueOrigin.ALL_TRACKS).onFailure(::report)
                }
            }
        } else {
            onNavigate(hit)
        }
    }

    /** Tracks of the current result set, so playing a hit also fills the queue with its neighbours. */
    private suspend fun playableHits(): List<Track> {
        val hits = (results.value as? SearchState.Ready)?.results?.byType(SearchHitType.TRACK).orEmpty()
        if (hits.isEmpty()) return emptyList()
        return musicRepository.tracksByIds(hits.map { it.id })
    }

    fun onPlayNext(track: Track) = launch { playNext(track) }

    fun onAddToQueue(track: Track) = launch { addToQueue(track) }

    fun onDismissMessage() {
        message.value = null
    }

    private fun launch(block: suspend () -> Outcome<*>) {
        viewModelScope.launch { block().onFailure(::report) }
    }

    private fun report(error: AppError) {
        message.value = Message(error.userMessage, isError = true)
    }

    private data class Message(val text: String, val isError: Boolean)

    private companion object {
        const val TAG = "Search"
    }
}
