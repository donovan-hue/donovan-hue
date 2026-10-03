package com.hifiplayer.presentation.library.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.logging.Logger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.onFailure
import com.hifiplayer.domain.model.error.AppError
import com.hifiplayer.domain.model.library.ScanProgress
import com.hifiplayer.domain.repository.MusicRepository
import com.hifiplayer.domain.usecase.library.AddMusicFolderUseCase
import com.hifiplayer.domain.usecase.library.CancelScanUseCase
import com.hifiplayer.domain.usecase.library.PruneMissingTracksUseCase
import com.hifiplayer.domain.usecase.library.RefreshLibraryUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The library's housekeeping actions: add a folder, rescan, cancel, prune.
 *
 * It is app-level because the folder picker is a platform dialog that MainActivity launches, and the
 * result must land somewhere that survives while the user is on any screen. Scan progress itself is
 * not duplicated here: it comes from the repository, which is the single source of truth.
 */
class LibraryAdminViewModel(
    musicRepository: MusicRepository,
    private val addMusicFolder: AddMusicFolderUseCase,
    private val refreshLibrary: RefreshLibraryUseCase,
    private val cancelScan: CancelScanUseCase,
    private val pruneMissing: PruneMissingTracksUseCase,
    private val logger: Logger = AppLogger.logger(),
) : ViewModel() {

    private val message = MutableStateFlow<Message?>(null)
    private val busy = MutableStateFlow(false)

    val state: StateFlow<LibraryAdminUiState> = combine(
        musicRepository.scanProgress,
        message,
        busy,
    ) { scan, currentMessage, isBusy ->
        LibraryAdminUiState(
            scan = scan,
            message = currentMessage?.text,
            messageIsError = currentMessage?.isError == true,
            busy = isBusy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryAdminUiState())

    /** Called with the tree URI returned by the system folder picker. */
    fun onFolderPicked(treeUri: String) {
        viewModelScope.launch {
            busy.value = true
            addMusicFolder(treeUri)
                .onFailure(::report)
                .also { outcome ->
                    if (outcome is Outcome.Success) {
                        message.value = Message("Carpeta añadida. Analizando los archivos…", isError = false)
                    }
                    busy.value = false
                }
        }
    }

    fun onRefresh() {
        viewModelScope.launch {
            refreshLibrary(force = true).onFailure(::report)
        }
    }

    fun onCancelScan() {
        viewModelScope.launch {
            cancelScan().onFailure(::report)
        }
    }

    /** Removes files that are no longer on disk. Never touches playlists or favorites of live files. */
    fun onPruneMissing() {
        viewModelScope.launch {
            val outcome = pruneMissing()
            if (outcome is Outcome.Success) {
                val count = outcome.value
                message.value = Message(
                    if (count == 0) "No había archivos ausentes." else "Se quitaron $count archivos ausentes de la biblioteca.",
                    isError = false,
                )
            } else {
                (outcome as? Outcome.Failure)?.let { report(it.error) }
            }
        }
    }

    fun onDismissMessage() {
        message.value = null
    }

    private fun report(error: AppError) {
        logger.w(TAG, "Acción de biblioteca fallida: ${error.code}")
        message.value = Message(error.userMessage, isError = true)
    }

    private data class Message(val text: String, val isError: Boolean)

    private companion object {
        const val TAG = "LibraryAdmin"
    }
}

data class LibraryAdminUiState(
    val scan: ScanProgress = ScanProgress.IDLE,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val busy: Boolean = false,
)
