package com.hifiplayer.presentation.library.playlists

import com.google.common.truth.Truth.assertThat
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.error.StorageError
import com.hifiplayer.domain.model.library.Playlist
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.repository.PlaylistRepository
import com.hifiplayer.domain.usecase.playlists.AddTrackToPlaylistUseCase
import com.hifiplayer.domain.usecase.playlists.CreatePlaylistUseCase
import com.hifiplayer.domain.usecase.playlists.GetPlaylistsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The picker is what makes "Añadir a una lista" real (requirement 46).
 *
 * What matters here: adding writes to the playlist, creating-and-adding does both, and when storage
 * fails the user is told — the track is never silently dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistPickerViewModelTest {

    private val repository = FakePlaylistRepository()
    private lateinit var viewModel: PlaylistPickerViewModel
    private val subject = track("t1")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = PlaylistPickerViewModel(
            getPlaylists = GetPlaylistsUseCase(repository),
            addTrack = AddTrackToPlaylistUseCase(repository),
            createPlaylist = CreatePlaylistUseCase(repository),
            logger = AppLogger.logger(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the picker offers the existing lists`() = runTest {
        repository.playlists.value = listOf(playlist("p1", "Coche"), playlist("p2", "Noche"))

        val state = viewModel.state.first { it.playlists.isNotEmpty() }

        assertThat(state.playlists.map { it.name }).containsExactly("Coche", "Noche").inOrder()
    }

    @Test
    fun `adding a track to a list writes it and confirms`() = runTest {
        repository.playlists.value = listOf(playlist("p1", "Coche"))

        viewModel.onAdd("p1", subject)

        assertThat(repository.added).containsExactly("p1" to "t1")
        val state = viewModel.state.first { it.message != null }
        assertThat(state.messageIsError).isFalse()
        assertThat(state.message).contains("Reflektor")
    }

    @Test
    fun `creating and adding does both`() = runTest {
        viewModel.onCreateAndAdd("Vinilos", subject)

        assertThat(repository.created).containsExactly("Vinilos")
        assertThat(repository.added).containsExactly(repository.lastCreatedId to "t1")
        val state = viewModel.state.first { it.message != null }
        assertThat(state.messageIsError).isFalse()
    }

    @Test
    fun `a storage failure is reported instead of pretending the track was added`() = runTest {
        repository.failWrites = true

        viewModel.onAdd("p1", subject)

        assertThat(repository.added).isEmpty()
        val state = viewModel.state.first { it.message != null }
        assertThat(state.messageIsError).isTrue()
        assertThat(state.message).isEqualTo(StorageError.NoSpaceLeft(requiredBytes = 1L, availableBytes = 0L).userMessage)
    }

    private fun playlist(id: String, name: String) = Playlist(
        id = id,
        name = name,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        trackCount = 0,
        totalDurationMs = 0L,
    )

    private fun track(id: String) = Track(
        id = id,
        uri = "content://music/$id.flac",
        title = "Reflektor",
        artist = "Arcade Fire",
        albumArtist = "Arcade Fire",
        album = "Reflektor",
        albumId = "a1",
        artistId = "ar1",
        durationMs = 245_000L,
        trackNumber = 1,
        discNumber = 1,
        year = 2013,
        genre = "Indie",
        sizeBytes = 43_000_000L,
        mimeType = "audio/flac",
        displayName = "01 Reflektor.flac",
        relativePath = "Reflektor/01 Reflektor.flac",
        dateAddedEpochSec = 0L,
        lastModifiedEpochSec = 0L,
        format = null,
    )

    /**
     * Only the members the picker uses do real work.
     *
     * The rest of the interface is implemented to fail loudly if the picker ever starts calling
     * something this test does not cover, which is a better outcome than silently returning a value
     * that would hide the new dependency.
     */
    private class FakePlaylistRepository : PlaylistRepository {

        val playlists = MutableStateFlow<List<Playlist>>(emptyList())
        val added = mutableListOf<Pair<String, String>>()
        val created = mutableListOf<String>()
        var lastCreatedId = "new-id"
        var failWrites = false

        override fun playlists(): Flow<List<Playlist>> = playlists

        override fun tracksOf(playlistId: String): Flow<List<Track>> = flowOf(emptyList())

        override suspend fun playlistById(playlistId: String): Playlist? =
            playlists.value.firstOrNull { it.id == playlistId }

        override suspend fun create(name: String): Outcome<Playlist> {
            if (failWrites) return failure()
            created += name
            lastCreatedId = "p-${created.size}"
            val playlist = Playlist(
                id = lastCreatedId,
                name = name,
                createdAtEpochMs = 0L,
                updatedAtEpochMs = 0L,
                trackCount = 0,
                totalDurationMs = 0L,
            )
            playlists.value = playlists.value + playlist
            return Outcome.Success(playlist)
        }

        override suspend fun rename(playlistId: String, newName: String): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun delete(playlistId: String): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun addTrack(playlistId: String, trackId: String, position: Int?): Outcome<Unit> {
            if (failWrites) return failure()
            added += playlistId to trackId
            return Outcome.Success(Unit)
        }

        override suspend fun addTracks(playlistId: String, trackIds: List<String>): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun removeTrack(playlistId: String, trackId: String): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun moveTrack(playlistId: String, fromIndex: Int, toIndex: Int): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun hasTrack(playlistId: String, trackId: String): Boolean = false

        private fun <T> failure(): Outcome<T> =
            Outcome.Failure(StorageError.NoSpaceLeft(requiredBytes = 1L, availableBytes = 0L))
    }
}
