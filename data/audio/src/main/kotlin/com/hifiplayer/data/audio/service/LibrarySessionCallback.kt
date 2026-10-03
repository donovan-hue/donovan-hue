package com.hifiplayer.data.audio.service

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.domain.model.library.Album
import com.hifiplayer.domain.model.library.Artist
import com.hifiplayer.domain.model.library.Genre
import com.hifiplayer.domain.model.library.Playlist
import com.hifiplayer.domain.model.library.Track
import com.hifiplayer.domain.model.library.TrackQuery
import com.hifiplayer.domain.repository.MusicRepository
import com.hifiplayer.domain.repository.PlaylistRepository
import com.hifiplayer.nativeaudio.engine.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The library as seen from outside the app: Android Auto, Android Automotive and any other media
 * browser (requirement 27).
 *
 * Everything it answers comes from the same repositories the phone screens use, so there is no second
 * copy of the library and no way for the car to show music the app does not have. When the library
 * cannot be read, the controller is told so ([LibraryResult.ofError]) instead of receiving an empty
 * list that would look like an empty library — the difference matters to a driver.
 *
 * Every query runs on [Dispatchers.IO] and answers through a future, so nothing is read from disk on
 * the session's thread (requirement 36).
 */
internal class LibrarySessionCallback(
    private val music: MusicRepository,
    private val playlists: PlaylistRepository,
) : MediaLibraryService.MediaLibrarySession.Callback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = async {
        when (val parsed = BrowseNode.parse(parentId)) {
            BrowseNode.Parsed.Root -> LibraryResult.ofItemList(childrenOfRoot(), params)
            is BrowseNode.Parsed.Section -> LibraryResult.ofItemList(sectionChildren(parsed.sectionId, page, pageSize), params)
            is BrowseNode.Parsed.Collection -> LibraryResult.ofItemList(collectionChildren(parsed, page, pageSize), params)
            is BrowseNode.Parsed.Song -> LibraryResult.ofItemList(ImmutableList.of(), params)
            is BrowseNode.Parsed.Unknown -> LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = async {
        val item = itemFor(mediaId)
        if (item == null) LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE) else LibraryResult.ofItem(item, null)
    }

    /**
     * Search inside the car. The result is announced asynchronously through the session, which is
     * what Media3 expects: the query runs while the controller keeps drawing.
     */
    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        scope.launch {
            val term = query.trim()
            val count = if (term.length < MIN_SEARCH_CHARS) 0 else searchResults(term).size
            session.notifySearchResultChanged(browser, term, count, params)
        }
        return Futures.immediateFuture(LibraryResult.ofVoid(params))
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = async {
        val term = query.trim()
        val items = if (term.length < MIN_SEARCH_CHARS) {
            emptyList()
        } else {
            page(searchResults(term), page, pageSize).map { it.toMediaItem() }
        }
        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
    }

    // ------------------------------------------------------------------ internals

    private fun childrenOfRoot(): ImmutableList<MediaItem> =
        ImmutableList.copyOf(BrowseNode.SECTIONS.map { it.toMediaItem() })

    private suspend fun searchResults(term: String): List<Track> = music.tracks(TrackQuery.All).first()
        .filter { it.matches(term) }
        .take(MAX_SEARCH_RESULTS)

    private suspend fun sectionChildren(sectionId: String, page: Int, pageSize: Int): ImmutableList<MediaItem> {
        val items: List<MediaItem> = when (sectionId) {
            BrowseNode.RECENT_ID -> page(music.recentlyPlayed(limit = RECENT_LIMIT).first(), page, pageSize)
                .map { it.toMediaItem() }

            BrowseNode.SONG_ID -> page(music.tracks(TrackQuery.All).first(), page, pageSize).map { it.toMediaItem() }
            BrowseNode.FAVOURITES_ID -> page(music.tracks(TrackQuery(favoritesOnly = true)).first(), page, pageSize)
                .map { it.toMediaItem() }

            BrowseNode.ALBUM_ID -> page(music.albums().first(), page, pageSize).map { it.toCollectionItem(BrowseNode.CollectionKind.ALBUM) }
            BrowseNode.ARTIST_ID -> page(music.artists().first(), page, pageSize).map { it.toCollectionItem(BrowseNode.CollectionKind.ARTIST) }
            BrowseNode.GENRE_ID -> page(music.genres().first(), page, pageSize).map { it.toCollectionItem(BrowseNode.CollectionKind.GENRE) }
            BrowseNode.PLAYLIST_ID -> page(playlists.playlists().first(), page, pageSize)
                .map { it.toCollectionItem(BrowseNode.CollectionKind.PLAYLIST) }

            else -> emptyList()
        }
        return ImmutableList.copyOf(items)
    }

    private suspend fun collectionChildren(
        parsed: BrowseNode.Parsed.Collection,
        page: Int,
        pageSize: Int,
    ): ImmutableList<MediaItem> {
        val tracks = when (parsed.kind) {
            BrowseNode.CollectionKind.ALBUM -> music.tracksInAlbum(parsed.id).first()
            BrowseNode.CollectionKind.ARTIST -> music.tracksByArtist(parsed.id).first()
            BrowseNode.CollectionKind.GENRE -> music.tracks(TrackQuery(genre = parsed.id)).first()
            BrowseNode.CollectionKind.PLAYLIST -> playlists.tracksOf(parsed.id).first()
        }
        return ImmutableList.copyOf(page(tracks, page, pageSize).map { it.toMediaItem() })
    }

    private suspend fun itemFor(mediaId: String): MediaItem? = when (val parsed = BrowseNode.parse(mediaId)) {
        BrowseNode.Parsed.Root -> rootItem()
        is BrowseNode.Parsed.Section -> BrowseNode.SECTIONS.firstOrNull { it.mediaId == parsed.sectionId }?.toMediaItem()
        is BrowseNode.Parsed.Collection -> collectionItem(parsed)
        is BrowseNode.Parsed.Song -> music.trackById(parsed.trackId)?.toMediaItem()
        is BrowseNode.Parsed.Unknown -> null
    }

    private suspend fun collectionItem(parsed: BrowseNode.Parsed.Collection): MediaItem? = when (parsed.kind) {
        BrowseNode.CollectionKind.ALBUM -> music.albums().first().firstOrNull { it.id == parsed.id }?.toCollectionItem(parsed.kind)
        BrowseNode.CollectionKind.ARTIST -> music.artists().first().firstOrNull { it.id == parsed.id }?.toCollectionItem(parsed.kind)
        BrowseNode.CollectionKind.GENRE -> music.genres().first().firstOrNull { it.name == parsed.id }?.toCollectionItem(parsed.kind)
        BrowseNode.CollectionKind.PLAYLIST -> playlists.playlists().first().firstOrNull { it.id == parsed.id }?.toCollectionItem(parsed.kind)
    }

    /**
     * Runs the query off the session thread and answers through the future. A failure is reported as
     * a failure: answering "empty" would tell the driver that their library is empty.
     */
    private fun <T : Any> async(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val future = SettableFuture.create<LibraryResult<T>>()
        scope.launch {
            val outcome = runCatching { block() }
            future.set(
                outcome.getOrElse { error ->
                    AppLogger.e(TAG, "No se pudo responder al controlador: ${error.message}")
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
                },
            )
        }
        return future
    }

    private fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(BrowseNode.ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(BrowseNode.Root.title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build(),
        )
        .build()

    private companion object {
        const val TAG = "AutoLibrary"
        const val MIN_SEARCH_CHARS = 2
        const val MAX_SEARCH_RESULTS = 100
        const val RECENT_LIMIT = 100
    }
}

/** `pageSize == Int.MAX_VALUE` means "everything", which is what a controller without paging asks. */
internal fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T> {
    if (pageSize == Int.MAX_VALUE || pageSize <= 0) return items
    val from = (page.coerceAtLeast(0).toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
    val to = (from + pageSize).coerceAtMost(items.size)
    return items.subList(from, to)
}

private fun Track.matches(query: String): Boolean {
    val needle = query.lowercase()
    return title.lowercase().contains(needle) ||
        artist?.lowercase()?.contains(needle) == true ||
        album?.lowercase()?.contains(needle) == true
}

private fun Album.toCollectionItem(kind: BrowseNode.CollectionKind): MediaItem =
    collectionItem(kind, id, title, displayArtist, coverTrackUri)

private fun Artist.toCollectionItem(kind: BrowseNode.CollectionKind): MediaItem =
    collectionItem(kind, id, name, "$albumCount álbumes", null)

private fun Genre.toCollectionItem(kind: BrowseNode.CollectionKind): MediaItem =
    collectionItem(kind, name, name, "$trackCount pistas", null)

private fun Playlist.toCollectionItem(kind: BrowseNode.CollectionKind): MediaItem =
    collectionItem(kind, id, displayName, "$trackCount pistas", artworkTrackUri)

private fun BrowseNode.Section.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build(),
    )
    .build()

private fun collectionItem(
    kind: BrowseNode.CollectionKind,
    id: String,
    title: String,
    subtitle: String?,
    artworkUri: String?,
): MediaItem = MediaItem.Builder()
    .setMediaId(kind.prefix + id)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .apply { subtitle?.takeIf { it.isNotBlank() }?.let { setArtist(it) } }
            .apply { artworkUri?.takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(it)) } }
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build(),
    )
    .build()
