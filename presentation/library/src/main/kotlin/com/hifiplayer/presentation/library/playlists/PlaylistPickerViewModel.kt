package com.hifiplayer.presentation.library.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.logging.Logger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.onFailure
import com.hifiplayer.domain.model.error.AppError
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.usecase.playlists.AddTrackToPlaylistUseCase
import com.hifiplayer.domain.usecase.playlists.CreatePlaylistUseCase
import com.hifiplayer.domain.usecase.playlists.GetPlaylistsUseCase
import com.hifiplayer.presentation.library.common.PlaylistOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Feeds the "add to a playlist" picker.
 *
 * It lives above the screens because the picker is offered from the home, the library, a collection,
 * the search results and a playlist itself — everywhere a track row exists. Adding a track to a list
 * and creating a list-and-adding are the two real mutations it performs.
 */
class PlaylistPickerViewModel(
    getPlaylists: GetPlaylistsUseCase,
    private val addTrack: AddTrackToPlaylistUseCase,
    private val createPlaylist: CreatePlaylistUseCase,
    private val logger: Logger = AppLogger.logger(),
) : ViewModel() {

    private val message = MutableStateFlow<Message?>(null)

    val state: StateFlow<PlaylistPickerUiState> = combine(getPlaylists(), message) { playlists, currentMessage ->
        PlaylistPickerUiState(
            playlists = playlists.map { PlaylistOption(id = it.id, name = it.displayName, trackCount = it.trackCount) },
            message = currentMessage?.text,
            messageIsError = currentMessage?.isError == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistPickerUiState())

    fun onAdd(playlistId: String, track: Track) {
        viewModelScope.launch {
            addTrack(playlistId, track.id)
                .onFailure(::report)
                .also { outcome ->
                    if (outcome is Outcome.Success) {
                        message.value = Message("«${track.displayTitle}» añadida a la lista.", isError = false)
                    }
                }
        }
    }

    fun onCreateAndAdd(name: String, track: Track) {
        viewModelScope.launch {
            when (val created = createPlaylist(name)) {
                is Outcome.Success -> {
                    addTrack(created.value.id, track.id)
                        .onFailure(::report)
                        .also { outcome ->
                            if (outcome is Outcome.Success) {
                                message.value = Message("Lista «$name» creada con «${track.displayTitle}».", isError = false)
                            }
                        }
                }

                is Outcome.Failure -> report(created.error)
            }
        }
    }

    fun onDismissMessage() {
        message.value = null
    }

    private fun report(error: AppError) {
        logger.w(TAG, "No se pudo actualizar la lista: ${error.code}")
        message.value = Message(error.userMessage, isError = true)
    }

    private data class Message(val text: String, val isError: Boolean)

    private companion object {
        const val TAG = "PlaylistPicker"
    }
}

data class PlaylistPickerUiState(
    val playlists: List<PlaylistOption> = emptyList(),
    val message: String? = null,
    val messageIsError: Boolean = false,
)
