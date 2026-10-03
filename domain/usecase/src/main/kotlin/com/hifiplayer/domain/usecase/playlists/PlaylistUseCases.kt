package com.hifiplayer.domain.usecase.playlists

import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.domain.model.library.Playlist
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.playback.QueueOrigin
import com.hifiplayer.domain.repository.PlaybackRepository
import com.hifiplayer.domain.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Requirement 25: playlist CRUD and playback. */
class GetPlaylistsUseCase(private val playlists: PlaylistRepository) {
    operator fun invoke(): Flow<List<Playlist>> = playlists.playlists()
}

class GetPlaylistTracksUseCase(private val playlists: PlaylistRepository) {
    operator fun invoke(playlistId: String): Flow<List<Track>> = playlists.tracksOf(playlistId)
}

class CreatePlaylistUseCase(private val playlists: PlaylistRepository) {
    suspend operator fun invoke(name: String): Outcome<Playlist> {
        val clean = name.trim()
        if (clean.isEmpty()) return Outcome.Failure(
            com.hifiplayer.domain.model.error.IllegalState("El nombre de la playlist no puede estar vacío"),
        )
        return playlists.create(clean)
    }
}

class RenamePlaylistUseCase(private val playlists: PlaylistRepository) {
    suspend operator fun invoke(playlistId: String, newName: String): Outcome<Unit> {
        val clean = newName.trim()
        if (clean.isEmpty()) return Outcome.Failure(
            com.hifiplayer.domain.model.error.IllegalState("El nombre de la playlist no puede estar vacío"),
        )
        return playlists.rename(playlistId, clean)
    }
}

class DeletePlaylistUseCase(private val playlists: PlaylistRepository) {
    suspend operator fun invoke(playlistId: String): Outcome<Unit> = playlists.delete(playlistId)
}

class AddTrackToPlaylistUseCase(private val playlists: PlaylistRepository) {
    suspend operator fun invoke(playlistId: String, trackId: String): Outcome<Unit> =
        playlists.addTrack(playlistId, trackId)
}

class RemoveTrackFromPlaylistUseCase(private val playlists: PlaylistRepository) {
    suspend operator fun invoke(playlistId: String, trackId: String): Outcome<Unit> =
        playlists.removeTrack(playlistId, trackId)
}

class MovePlaylistTrackUseCase(private val playlists: PlaylistRepository) {
    suspend operator fun invoke(playlistId: String, fromIndex: Int, toIndex: Int): Outcome<Unit> =
        playlists.moveTrack(playlistId, fromIndex, toIndex)
}

class PlayPlaylistUseCase(
    private val playlists: PlaylistRepository,
    private val playback: PlaybackRepository,
) {
    suspend operator fun invoke(playlistId: String, shuffle: Boolean = false): Outcome<Unit> {
        val tracks = playlists.tracksOf(playlistId).first()
        if (tracks.isEmpty()) return Outcome.Failure(
            com.hifiplayer.domain.model.error.IllegalState("La playlist está vacía"),
        )
        val ordered = if (shuffle) tracks.shuffled() else tracks
        val result = playback.playTracks(ordered, 0, QueueOrigin.PLAYLIST)
        if (result is Outcome.Success && shuffle) playback.setShuffle(true)
        return result
    }
}
