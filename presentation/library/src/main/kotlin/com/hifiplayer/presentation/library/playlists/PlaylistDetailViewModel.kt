package com.hifiplayer.presentation.library.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.logging.Logger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.onFailure
import com.hifiplayer.domain.model.error.AppError
import com.hifiplayer.domain.model.library.Playlist
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.playback.QueueOrigin
import com.hifiplayer.domain.usecase.favorites.ObserveFavoriteIdsUseCase
import com.hifiplayer.domain.usecase.favorites.ToggleFavoriteUseCase
import com.hifiplayer.domain.usecase.playback.PlayTracksUseCase
import com.hifiplayer.domain.usecase.playback.SetShuffleUseCase
import com.hifiplayer.domain.usecase.playlists.GetPlaylistTracksUseCase
import com.hifiplayer.domain.usecase.playlists.GetPlaylistsUseCase
import com.hifiplayer.domain.usecase.playlists.MovePlaylistTrackUseCase
import com.hifiplayer.domain.usecase.playlists.RemoveTrackFromPlaylistUseCase
import com.hifiplayer.domain.usecase.queue.AddToQueueUseCase
import com.hifiplayer.domain.usecase.queue.AddTracksToQueueUseCase
import com.hifiplayer.domain.usecase.queue.PlayNextUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Playlist detail (phase 6): the tracks of one list, with reordering.
 *
 * The order is the list's own — `position` in the database — so a move is a real write and not a
 * local sort. Reopening the list shows exactly the order the user left.
 */
class PlaylistDetailViewModel(
    val playlistId: String,
    getPlaylists: GetPlaylistsUseCase,
    getTracks: GetPlaylistTracksUseCase,
    observeFavoriteIds: ObserveFavoriteIdsUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
    private val removeTrack: RemoveTrackFromPlaylistUseCase,
    private val moveTrack: MovePlaylistTrackUseCase,
    private val playTracks: PlayTracksUseCase,
    private val setShuffle: SetShuffleUseCase,
    private val playNext: PlayNextUseCase,
    private val addToQueue: AddToQueueUseCase,
    private val addTracksToQueue: AddTracksToQueueUseCase,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger = AppLogger.logger(),
) : ViewModel() {

    private val message = MutableStateFlow<Message?>(null)
    private val pendingRemove = MutableStateFlow<String?>(null)

    val state: StateFlow<PlaylistDetailUiState> = combine(
        getPlaylists().map { list -> list.firstOrNull { it.id == playlistId } },
        getTracks(playlistId),
        observeFavoriteIds(),
        combine(message, pendingRemove) { msg, pending -> msg to pending },
    ) { playlist, tracks, favoriteIds, (currentMessage, pending) ->
        PlaylistDetailUiState(
            playlist = playlist,
            tracks = tracks,
            favoriteIds = favoriteIds,
            pendingRemoveTrackId = pending,
            message = currentMessage?.text,
            messageIsError = currentMessage?.isError == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistDetailUiState())

    // ------------------------------------------------------------------ playlist contents

    fun onRemoveRequested(trackId: String) {
        pendingRemove.value = trackId
    }

    fun onRemoveCancelled() {
        pendingRemove.value = null
    }

    fun onRemoveConfirmed() {
        val trackId = pendingRemove.value ?: return
        viewModelScope.launch {
            removeTrack(playlistId, trackId).onFailure(::report)
            pendingRemove.value = null
        }
    }

    /** Applies a drag: positions are the playlist's own indices, exactly what the drag reported. */
    fun onMove(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        viewModelScope.launch { moveTrack(playlistId, fromIndex, toIndex).onFailure(::report) }
    }

    fun onToggleFavorite(track: Track) {
        viewModelScope.launch { toggleFavorite(track.id).onFailure(::report) }
    }

    // ------------------------------------------------------------------ playback

    fun onPlayTrack(track: Track, tracks: List<Track>) {
        viewModelScope.launch {
            val index = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            playTracks(tracks, index, QueueOrigin.PLAYLIST).onFailure(::report)
        }
    }

    fun onPlayAll(tracks: List<Track>) {
        viewModelScope.launch { playTracks(tracks, 0, QueueOrigin.PLAYLIST).onFailure(::report) }
    }

    fun onShuffleAll(tracks: List<Track>) {
        viewModelScope.launch {
            playTracks(tracks.shuffled(), 0, QueueOrigin.PLAYLIST).onFailure(::report)
            setShuffle(true).onFailure(::report)
        }
    }

    fun onAddAllToQueue(tracks: List<Track>) {
        viewModelScope.launch { addTracksToQueue(tracks, next = false).onFailure(::report) }
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
        logger.w(TAG, "Operación sobre la lista fallida: ${error.code}")
        message.value = Message(error.userMessage, isError = true)
    }

    private data class Message(val text: String, val isError: Boolean)

    private companion object {
        const val TAG = "PlaylistDetail"
    }
}

data class PlaylistDetailUiState(
    val playlist: Playlist? = null,
    val tracks: List<Track> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val pendingRemoveTrackId: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    val title: String get() = playlist?.displayName ?: "Lista"
    val isEmpty: Boolean get() = tracks.isEmpty()
}
