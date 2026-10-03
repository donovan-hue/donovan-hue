package com.hifiplayer.presentation.library.search

import com.hifiplayer.core.common.logging.Logger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.library.SearchResults
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/** What the search box is showing right now. */
sealed interface SearchState {
    /** Nothing typed yet, or fewer characters than the minimum. */
    data object Idle : SearchState

    /** The query is running. Emitted before the query starts, never after. */
    data object Running : SearchState

    data class Ready(val results: SearchResults) : SearchState

    data class Failed(val message: String) : SearchState
}

/**
 * Turns keystrokes into queries (requirement 26: about 300 ms debounce).
 *
 * It is its own class instead of five lines inside the ViewModel because this is where the behaviour
 * that the user can feel — how long the app waits, and that a slow query for "be" never overwrites
 * the results for "beethoven" — actually lives. Being separate is what makes both of those testable
 * without a screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchQuerySource(
    private val search: suspend (String) -> Outcome<SearchResults>,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val minLength: Int = DEFAULT_MIN_LENGTH,
    private val logger: Logger,
) {

    fun states(term: Flow<String>): Flow<SearchState> = term
        .debounce(debounceMs)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            flow {
                if (query.trim().length < minLength) {
                    emit(SearchState.Idle)
                } else {
                    emit(SearchState.Running)
                    emit(
                        when (val outcome = search(query)) {
                            is Outcome.Success -> SearchState.Ready(outcome.value)
                            is Outcome.Failure -> {
                                logger.w(TAG, "La búsqueda falló: ${outcome.error.code}")
                                SearchState.Failed(outcome.error.userMessage)
                            }
                        },
                    )
                }
            }
        }

    companion object {
        /** Requirement 26: ~300 ms between the last keystroke and the query. */
        const val DEFAULT_DEBOUNCE_MS = 300L

        /** One character matches half the library; two is the shortest useful term. */
        const val DEFAULT_MIN_LENGTH = 2

        private const val TAG = "Search"
    }
}
