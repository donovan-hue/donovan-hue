package com.hifiplayer.core.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.storage.AudioFileCandidate
import com.hifiplayer.domain.model.library.LibrarySource

/**
 * Reads the device's music index (requirement 17).
 *
 * MediaStore is the fastest, permission-friendliest way to find audio on modern Android, and it
 * already gives us album/artist ids. It is *not* trusted for technical data: only the file URI,
 * name and size are taken from here – codec, sample rate and bit depth are probed later.
 */
class MediaStoreAudioSource(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    /** Columns we actually use; keeping the projection small keeps the query fast. */
    private val projection: Array<String> = buildList {
        add(MediaStore.Audio.Media._ID)
        add(MediaStore.Audio.Media.DISPLAY_NAME)
        add(MediaStore.Audio.Media.SIZE)
        add(MediaStore.Audio.Media.DATE_ADDED)
        add(MediaStore.Audio.Media.DATE_MODIFIED)
        add(MediaStore.Audio.Media.MIME_TYPE)
        add(MediaStore.Audio.Media.ALBUM_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Audio.Media.RELATIVE_PATH)
    }.toTypedArray()

    /**
     * @param excludedFolders folder paths (relative) the user asked to ignore
     * @param minimumSizeBytes skip artwork/dialogue stubs that would only pollute the library
     */
    fun query(
        excludedFolders: Set<String> = emptySet(),
        minimumSizeBytes: Long = 0L,
        onBatch: (List<AudioFileCandidate>) -> Unit,
    ) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} ASC"

        resolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val relativeColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                -1
            }

            val batch = ArrayList<AudioFileCandidate>(256)
            while (cursor.moveToNext()) {
                val id = cursor.getLongOrNull(idColumn) ?: continue
                val size = cursor.getLongOrNull(sizeColumn) ?: 0L
                if (size in 1 until minimumSizeBytes) continue
                val relativePath = if (relativeColumn >= 0) cursor.getStringOrNull(relativeColumn) else null
                val folderPath = relativePath?.trim('/')?.substringBeforeLast('/', missingDelimiterValue = "") ?: ""
                if (excludedFolders.any { folderPath.startsWith(it.trim('/')) && it.isNotBlank() }) continue

                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                batch += AudioFileCandidate(
                    uri = uri,
                    displayName = cursor.getStringOrNull(nameColumn) ?: "pista_$id",
                    sizeBytes = size,
                    lastModifiedEpochSec = cursor.getLongOrNull(modifiedColumn) ?: 0L,
                    mimeType = cursor.getStringOrNull(mimeColumn),
                    relativePath = relativePath,
                    folderPath = folderPath,
                    source = LibrarySource.MediaStore,
                    dateAddedEpochSec = cursor.getLongOrNull(addedColumn) ?: 0L,
                    mediaStoreId = id,
                    mediaStoreAlbumId = cursor.getLongOrNull(albumColumn)?.toString(),
                )
                if (batch.size >= 256) {
                    onBatch(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) onBatch(batch.toList())
        } ?: AppLogger.w(TAG, "MediaStore no devolvió resultados para audio")
    }

    /** True when at least one audio file is visible to the app. */
    fun hasAnyAudio(): Boolean = resolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Audio.Media._ID),
        null,
        null,
        null,
    )?.use { it.count > 0 } ?: false

    /** Album artwork URI from MediaStore, used as a cheap last resort for covers. */
    fun albumArtworkUri(albumId: String): Uri? = runCatching {
        ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId.toLong())
    }.getOrNull()

    private companion object {
        const val TAG = "MediaStoreAudioSource"
    }
}
