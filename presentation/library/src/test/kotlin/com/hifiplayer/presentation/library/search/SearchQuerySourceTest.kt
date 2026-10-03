package com.hifiplayer.presentation.library.search

import com.google.common.truth.Truth.assertThat
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.error.MetadataError
import com.hifiplayer.domain.model.library.SearchHit
import com.hifiplayer.domain.model.library.SearchHitType
import com.hifiplayer.domain.model.library.SearchResults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Requirement 26: the search waits about 300 ms and only then queries.
 *
 * These tests exist because the timing is a promise the app makes to the database and to the user:
 * typing ten characters must produce one query, not ten, and a slow query for a short term must never
 * land after the results for the finished word.
 */
class SearchQuerySourceTest {

    private val searches = mutableListOf<String>()

    private fun source(
        delayMs: Long = SearchQuerySource.DEFAULT_DEBOUNCE_MS,
        result: (String) -> Outcome<SearchResults> = { query -> Outcome.Success(success(query)) },
    ) = SearchQuerySource(
        search = { query ->
            searches += query
            result(query)
        },
        debounceMs = delayMs,
        logger = AppLogger.logger(),
    )

    @Test
    fun `no query is executed before the debounce window elapses`() = runTest {
        val term = MutableStateFlow("")
        val states = source().states(term)

        val collected = mutableListOf<SearchState>()
        val job = launch { states.toList(collected) }

        term.value = "be"
        advanceTimeBy(200)
        assertThat(searches).isEmpty()

        advanceTimeBy(150)
        assertThat(searches).containsExactly("be")

        job.cancel()
    }

    @Test
    fun `typing fast produces a single query with the last term`() = runTest {
        val term = MutableStateFlow("")
        val states = source().states(term)

        val collected = mutableListOf<SearchState>()
        val job = launch { states.toList(collected) }

        "beethoven".forEachIndexed { index, _ ->
            term.value = "beethoven".substring(0, index + 1)
            advanceTimeBy(50)
        }
        advanceTimeBy(400)
        advanceUntilIdle()

        assertThat(searches).containsExactly("beethoven")
        job.cancel()
    }

    @Test
    fun `a term shorter than the minimum never reaches the database`() = runTest {
        val term = MutableStateFlow("")
        val states = source().states(term)

        val collected = mutableListOf<SearchState>()
        val job = launch { states.toList(collected) }

        term.value = "b"
        advanceTimeBy(500)
        advanceUntilIdle()

        assertThat(searches).isEmpty()
        assertThat(collected.last()).isEqualTo(SearchState.Idle)
        job.cancel()
    }

    @Test
    fun `running is emitted before the results`() = runTest {
        val term = MutableStateFlow("")
        val states = source().states(term)

        val collected = mutableListOf<SearchState>()
        val job = launch { states.toList(collected) }

        term.value = "flac"
        advanceUntilIdle()

        assertThat(collected).containsExactly(
            SearchState.Running,
            SearchState.Ready(success("flac")),
        ).inOrder()
        job.cancel()
    }

    @Test
    fun `a failing search is reported as a failure with a message, never as empty results`() = runTest {
        val term = MutableStateFlow("")
        val states = source { Outcome.Failure(MetadataError.ArtworkFailed("content://x/1.flac", "no se pudo leer")) }.states(term)

        val collected = mutableListOf<SearchState>()
        val job = launch { states.toList(collected) }

        term.value = "vinyl"
        advanceUntilIdle()

        val last = collected.last()
        assertThat(last).isInstanceOf(SearchState.Failed::class.java)
        assertThat((last as SearchState.Failed).message).isNotEmpty()
        job.cancel()
    }

    private fun success(query: String): SearchResults = SearchResults(
        query = query,
        hits = listOf(
            SearchHit(
                type = SearchHitType.TRACK,
                id = "track-1",
                title = "Reflektor",
                subtitle = "Arcade Fire",
                artworkUri = null,
            ),
        ),
        tookMs = 1L,
    )
}
