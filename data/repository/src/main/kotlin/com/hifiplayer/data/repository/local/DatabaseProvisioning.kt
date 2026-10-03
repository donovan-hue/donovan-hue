package com.hifiplayer.data.repository.local

import android.content.Context
import com.hifiplayer.core.database.HiFiDatabase
import com.hifiplayer.core.database.dao.EqPresetDao
import com.hifiplayer.core.database.dao.FavoriteDao
import com.hifiplayer.core.database.dao.LibrarySourceDao
import com.hifiplayer.core.database.dao.LibraryStructureDao
import com.hifiplayer.core.database.dao.PlayHistoryDao
import com.hifiplayer.core.database.dao.PlaylistDao
import com.hifiplayer.core.database.dao.QueueDao
import com.hifiplayer.core.database.dao.TrackDao

/**
 * Single point where the data layer grabs Room DAOs.
 *
 * Keeping it in one small class means the repositories receive DAOs (easy to test with an
 * in-memory database) instead of a [Context] each, and there is exactly one place that knows how
 * the database is built.
 */
class DatabaseProvisioning(context: Context) {

    private val database: HiFiDatabase = HiFiDatabase.get(context)

    val tracks: TrackDao get() = database.trackDao()
    val structure: LibraryStructureDao get() = database.libraryStructureDao()
    val playlists: PlaylistDao get() = database.playlistDao()
    val favorites: FavoriteDao get() = database.favoriteDao()
    val history: PlayHistoryDao get() = database.playHistoryDao()
    val queue: QueueDao get() = database.queueDao()
    val sources: LibrarySourceDao get() = database.librarySourceDao()
    val eqPresets: EqPresetDao get() = database.eqPresetDao()
}
