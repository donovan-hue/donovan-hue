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
import com.hifiplayer.domain.usecase.playlists.CreatePlaylistUseCase
import com.hifiplayer.domain.usecase.playlists.DeletePlaylistUseCase
import com.hifiplayer.domain.usecase.playlists.GetPlaylistsUseCase
import com.hifiplayer.domain.usecase.playlists.PlayPlaylistUseCase
import com.hifiplayer.domain.usecase.playlists.RenamePlaylistUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Playlist list (phase 6).
 *
 * Renaming, deleting and creating all happen in this screen because they are one-line operations on
 * the repository; opening a detail screen to rename would be a detour. Deleting asks for a second
 * tap, since it is the only destructive action here.
 */
class PlaylistsViewModel(
    getPlaylists: GetPlaylistsUseCase,
    private val createPlaylist: CreatePlaylistUseCase,
    private val renamePlaylist: RenamePlaylistUseCase,
    private val deletePlaylist: DeletePlaylistUseCase,
    private val playPlaylist: PlayPlaylistUseCase,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger = AppLogger.logger(),
) : ViewModel() {

    private val newName = MutableStateFlow("")
    private val renaming = MutableStateFlow<String?>(null)
    private val renameValue = MutableStateFlow("")
    private val pendingDelete = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<Message?>(null)

    val state: StateFlow<PlaylistsUiState> = combine(
        getPlaylists(),
        newName,
        combine(renaming, renameValue, pendingDelete) { id, value, pending -> Triple(id, value, pending) },
        message,
    ) { playlists, name, (renamingId, renameText, deleteId), currentMessage ->
        PlaylistsUiState(
            playlists = playlists,
            newName = name,
            renamingId = renamingId,
            renameValue = renameText,
            pendingDeleteId = deleteId,
            message = currentMessage?.text,
            messageIsError = currentMessage?.isError == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistsUiState())

    // ------------------------------------------------------------------ create / rename / delete

    fun onNewNameChange(value: String) {
        newName.value = value
    }

    fun onCreate() {
        val name = newName.value.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            createPlaylist(name)
                .onFailure(::report)
                .also { outcome ->
                    if (outcome is Outcome.Success) {
                        newName.value = ""
                        message.value = Message("Lista \"${outcome.value.displayName}\" creada.", isError = false)
                    }
                }
        }
    }

    fun onStartRename(playlist: Playlist) {
        renaming.value = playlist.id
        renameValue.value = playlist.name
    }

    fun onRenameValueChange(value: String) {
        renameValue.value = value
    }

    fun onCancelRename() {
        renaming.value = null
        renameValue.value = ""
    }

    fun onConfirmRename() {
        val id = renaming.value ?: return
        val name = renameValue.value.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            renamePlaylist(id, name).onFailure(::report)
            renaming.value = null
            renameValue.value = ""
        }
    }

    fun onRequestDelete(playlistId: String) {
        pendingDelete.value = playlistId
    }

    fun onCancelDelete() {
        pendingDelete.value = null
    }

    fun onConfirmDelete() {
        val id = pendingDelete.value ?: return
        viewModelScope.launch {
            deletePlaylist(id).onFailure(::report)
            pendingDelete.value = null
        }
    }

    // ------------------------------------------------------------------ playback

    fun onPlay(playlistId: String, shuffle: Boolean) {
        viewModelScope.launch {
            playPlaylist(playlistId, shuffle).onFailure(::report)
        }
    }

    fun onDismissMessage() {
        message.value = null
    }

    private fun report(error: AppError) {
        logger.w(TAG, "Operación de lista fallida: ${error.code}")
        message.value = Message(error.userMessage, isError = true)
    }

    private data class Message(val text: String, val isError: Boolean)

    private companion object {
        const val TAG = "Playlists"
    }
}

data class PlaylistsUiState(
    val playlists: List<Playlist> = emptyList(),
    val newName: String = "",
    val renamingId: String? = null,
    val renameValue: String = "",
    val pendingDeleteId: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    val isEmpty: Boolean get() = playlists.isEmpty()
    val canCreate: Boolean get() = newName.isNotBlank()
}
