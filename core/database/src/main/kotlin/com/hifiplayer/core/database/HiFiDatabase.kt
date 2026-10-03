package com.hifiplayer.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.hifiplayer.core.common.config.AppConfig
import com.hifiplayer.core.database.dao.EqPresetDao
import com.hifiplayer.core.database.dao.FavoriteDao
import com.hifiplayer.core.database.dao.LibrarySourceDao
import com.hifiplayer.core.database.dao.LibraryStructureDao
import com.hifiplayer.core.database.dao.PlayHistoryDao
import com.hifiplayer.core.database.dao.PlaylistDao
import com.hifiplayer.core.database.dao.QueueDao
import com.hifiplayer.core.database.dao.TrackDao
import com.hifiplayer.core.database.entity.AlbumEntity
import com.hifiplayer.core.database.entity.ArtistEntity
import com.hifiplayer.core.database.entity.EqPresetEntity
import com.hifiplayer.core.database.entity.FavoriteEntity
import com.hifiplayer.core.database.entity.FolderEntity
import com.hifiplayer.core.database.entity.LibrarySourceEntity
import com.hifiplayer.core.database.entity.PlayHistoryEntity
import com.hifiplayer.core.database.entity.PlaylistEntity
import com.hifiplayer.core.database.entity.PlaylistTrackEntity
import com.hifiplayer.core.database.entity.QueueItemEntity
import com.hifiplayer.core.database.entity.QueueMetaEntity
import com.hifiplayer.core.database.entity.TrackEntity

/**
 * Local library database (requirement 4). Room is only ever touched from the data layer, always
 * off the main thread; the UI reads Flows through the repositories.
 */
@Database(
    entities = [
        TrackEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        FolderEntity::class,
        LibrarySourceEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        FavoriteEntity::class,
        PlayHistoryEntity::class,
        QueueItemEntity::class,
        QueueMetaEntity::class,
        EqPresetEntity::class,
    ],
    version = AppConfig.DATABASE_VERSION,
    exportSchema = false,
)
@TypeConverters(HiFiConverters::class)
abstract class HiFiDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun libraryStructureDao(): LibraryStructureDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun queueDao(): QueueDao
    abstract fun librarySourceDao(): LibrarySourceDao
    abstract fun eqPresetDao(): EqPresetDao

    companion object {
        @Volatile
        private var instance: HiFiDatabase? = null

        fun get(context: Context): HiFiDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): HiFiDatabase =
            Room.databaseBuilder(context, HiFiDatabase::class.java, AppConfig.DATABASE_NAME)
                // Library data is a cache of the user's files: a rebuild is cheap and always
                // consistent, so schema changes do not need to risk data migration bugs.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

/** Room type converters. Everything the schema needs is stored explicitly, never as blob. */
class HiFiConverters {
    @TypeConverter fun stringSetToCsv(value: Set<String>?): String = value?.joinToString(",") ?: ""
    @TypeConverter fun csvToStringSet(value: String?): Set<String> =
        value?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
}
