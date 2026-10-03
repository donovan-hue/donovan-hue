package com.hifiplayer.presentation.library.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.logging.Logger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.onFailure
import com.hifiplayer.domain.model.error.AppError
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.library.TrackQuery
import com.hifiplayer.domain.model.playback.QueueOrigin
import com.hifiplayer.domain.repository.MusicRepository
import com.hifiplayer.domain.usecase.favorites.ObserveFavoriteIdsUseCase
import com.hifiplayer.domain.usecase.favorites.ToggleFavoriteUseCase
import com.hifiplayer.domain.usecase.playback.PlayTracksUseCase
import com.hifiplayer.domain.usecase.playback.SetShuffleUseCase
import com.hifiplayer.domain.usecase.queue.AddToQueueUseCase
import com.hifiplayer.domain.usecase.queue.AddTracksToQueueUseCase
import com.hifiplayer.domain.usecase.queue.PlayNextUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One screen for every "list of tracks that belongs to something": an album, an artist, a genre or a
 * folder.
 *
 * They are the same screen because they are the same problem — a title, a list of tracks and the
 * same four actions. Keeping one implementation means one place to fix, and it guarantees the album
 * view and the folder view can never behave differently. What changes is injected: the title, the
 * subtitle and the query.
 */
class CollectionViewModel(
    private val musicRepository: MusicRepository,
    val title: String,
    val subtitle: String?,
    val origin: QueueOrigin,
    private val query: TrackQuery,
    observeFavoriteIds: ObserveFavoriteIdsUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
    private val playTracks: PlayTracksUseCase,
    private val setShuffle: SetShuffleUseCase,
    private val playNext: PlayNextUseCase,
    private val addToQueue: AddToQueueUseCase,
    private val addTracksToQueue: AddTracksToQueueUseCase,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger = AppLogger.logger(),
) : ViewModel() {

    private val message = MutableStateFlow<Message?>(null)

    val state: StateFlow<CollectionUiState> = combine(
        musicRepository.tracks(query),
        observeFavoriteIds(),
        message,
    ) { tracks, favoriteIds, currentMessage ->
        CollectionUiState(
            title = title,
            subtitle = subtitle,
            tracks = tracks,
            favoriteIds = favoriteIds,
            message = currentMessage?.text,
            messageIsError = currentMessage?.isError == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionUiState(title = title, subtitle = subtitle))

    /** Plays the whole collection, starting where the user tapped. */
    fun onPlayTrack(track: Track, tracks: List<Track>) {
        viewModelScope.launch {
            val index = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            playTracks(tracks, index, origin).onFailure(::report)
        }
    }

    fun onPlayAll(tracks: List<Track>) {
        viewModelScope.launch { playTracks(tracks, 0, origin).onFailure(::report) }
    }

    /**
     * Shuffle plays the collection in a random order *and* turns the shuffle mode on, so what the
     * player shows matches what the listener is hearing.
     */
    fun onShuffleAll(tracks: List<Track>) {
        viewModelScope.launch {
            playTracks(tracks.shuffled(), 0, origin).onFailure(::report)
            setShuffle(true).onFailure(::report)
        }
    }

    fun onAddAllToQueue(tracks: List<Track>) {
        viewModelScope.launch {
            addTracksToQueue(tracks, next = false).onFailure(::report)
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
        logger.w(TAG, "Acción de colección fallida: ${error.code}")
        message.value = Message(error.userMessage, isError = true)
    }

    private data class Message(val text: String, val isError: Boolean)

    private companion object {
        const val TAG = "Collection"
    }
}
