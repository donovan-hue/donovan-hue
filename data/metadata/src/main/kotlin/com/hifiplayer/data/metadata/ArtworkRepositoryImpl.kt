package com.hifiplayer.data.metadata

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.hifiplayer.core.common.coroutines.DispatcherProvider
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.outcomeOf
import com.hifiplayer.core.metadata.EmbeddedArtwork
import com.hifiplayer.core.metadata.MetadataReader
import com.hifiplayer.core.storage.FileAccess
import com.hifiplayer.core.storage.SafFolderSource
import com.hifiplayer.domain.model.audio.Codec
import com.hifiplayer.domain.model.error.MetadataError
import com.hifiplayer.domain.model.library.ArtworkFileNames
import com.hifiplayer.domain.model.library.ArtworkSource
import com.hifiplayer.domain.repository.ArtworkRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Artwork resolution and disk cache (requirement 19/20).
 *
 * Priority is enforced here, not in the UI: embedded → cover.jpg → folder.jpg → artwork.jpg.
 * Images are decoded with `inSampleSize` computed from a bounds-only first pass, so a 4000×4000
 * cover never lands in memory just to draw a 160 px row (requirement 20).
 */
class ArtworkRepositoryImpl(
    private val context: Context,
    private val fileAccess: FileAccess,
    private val reader: MetadataReader,
    private val safFolderSource: SafFolderSource,
    private val dispatchers: DispatcherProvider,
) : ArtworkRepository {

    private val cacheDir: File get() = File(context.cacheDir, CACHE_DIR_NAME)
    private val cacheMutex = Mutex()

    override suspend fun resolveArtwork(
        trackId: String,
        uri: String,
        folderPath: String?,
    ): Outcome<ArtworkSource?> = withContext(dispatchers.io) {
        try {
            val parsedUri = Uri.parse(uri)
            embeddedArtwork(trackId, parsedUri)?.let { return@withContext Outcome.Success(it) }
            sidecarArtwork(parsedUri, folderPath)?.let { return@withContext Outcome.Success(it) }
            Outcome.Success(null)
        } catch (exception: SecurityException) {
            Outcome.Failure(MetadataError.ArtworkFailed(uri, "permiso revocado al leer la portada"))
        } catch (exception: Exception) {
            AppLogger.w(TAG, "No se pudo resolver la portada: ${exception.javaClass.simpleName}")
            Outcome.Failure(MetadataError.ArtworkFailed(uri, exception.message ?: "error desconocido"))
        }
    }

    override fun cacheKeyFor(source: ArtworkSource): String = source.key

    override suspend fun bytesFor(source: ArtworkSource, targetPx: Int): Outcome<ByteArray> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            val cached = cacheFileFor(source, targetPx)
            if (cached.exists() && cached.length() > 0L) return@outcomeOf cached.readBytes()

            val original = loadOriginal(source)
                ?: throw java.io.FileNotFoundException("sin imagen de origen para ${source.key}")
            val scaled = downscale(original, targetPx)
                ?: throw java.io.IOException("la imagen de portada no se pudo decodificar")
            cacheMutex.withLock {
                cacheDir.mkdirs()
                cached.writeBytes(scaled)
            }
            scaled
        }
    }

    override suspend fun cachedBytes(source: ArtworkSource): ByteArray? = withContext(dispatchers.io) {
        val file = cacheFileFor(source, DEFAULT_TARGET_PX)
        if (file.exists() && file.length() > 0L) file.readBytes() else null
    }

    override suspend fun clearArtworkCache(): Outcome<Long> = withContext(dispatchers.io) {
        outcomeOf(TAG) {
            val freed = cacheSizeBytes()
            cacheDir.listFiles()?.forEach { it.delete() }
            AppLogger.i(TAG, "Caché de portadas vaciada (${freed / 1024} KB)")
            freed
        }
    }

    override suspend fun cacheSizeBytes(): Long = withContext(dispatchers.io) {
        cacheDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }

    // ------------------------------------------------------------------ resolution

    private suspend fun embeddedArtwork(trackId: String, uri: Uri): ArtworkSource? {
        val head = fileAccess.readHead(uri, EMBEDDED_READ_BYTES).getOrNull() ?: return null
        val codec = Codec.fromMimeType(context.contentResolver.getType(uri))
        val extracted = EmbeddedArtwork.extract(head, codec) ?: return null
        val bounds = readBounds(extracted.bytes)
        return ArtworkSource(
            key = "embedded:$trackId",
            priority = ArtworkSource.Priority.EMBEDDED,
            ownerUri = uri.toString(),
            fileName = null,
            mimeType = extracted.mimeType,
            sizeBytes = extracted.sizeBytes,
            widthPx = extracted.declaredWidth ?: bounds?.first,
            heightPx = extracted.declaredHeight ?: bounds?.second,
        )
    }

    private fun sidecarArtwork(uri: Uri, folderPath: String?): ArtworkSource? {
        if (uri.scheme == "content" && uri.authority?.contains("documents") == true) {
            val (sidecarUri, name) = safFolderSource.findSidecarArtwork(uri) ?: return null
            return sidecarSource(sidecarUri, name)
        }
        val found = findSidecarInMediaStore(folderPath) ?: return null
        return sidecarSource(found.first, found.second)
    }

    private fun sidecarSource(uri: Uri, fileName: String): ArtworkSource? {
        val priority = ArtworkFileNames.ACCEPTED.firstOrNull { it.first == fileName.lowercase() }?.second
            ?: return null
        val size = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0L }
        return ArtworkSource(
            key = "sidecar:$uri",
            priority = priority,
            ownerUri = uri.toString(),
            fileName = fileName,
            mimeType = context.contentResolver.getType(uri) ?: guessMime(fileName),
            sizeBytes = size,
        )
    }

    /**
     * Sidecar search for MediaStore files. Uses `RELATIVE_PATH` on Android 10+ and the legacy
     * `DATA` column before that. Returns null when the folder has no accepted cover file.
     */
    private fun findSidecarInMediaStore(folderPath: String?): Pair<Uri, String>? {
        val names = ArtworkFileNames.ACCEPTED.map { it.first }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val placeholders = names.joinToString(",") { "?" }

        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val path = folderPath?.trim('/')?.let { if (it.isEmpty()) "" else "$it/" } ?: return null
            if (path.isEmpty() && folderPath.isNullOrBlank()) return null
            "(${MediaStore.Images.Media.DISPLAY_NAME} IN ($placeholders)) AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?" to (names + path)
        } else {
            val folder = folderPath?.trim('/') ?: return null
            if (folder.isEmpty()) return null
            "(${MediaStore.Images.Media.DISPLAY_NAME} IN ($placeholders)) AND ${MediaStore.Images.Media.DATA} LIKE ?" to (names + "%/$folder/%")
        }

        return try {
            resolver.query(collection, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME), selection, args.toTypedArray(), null)?.use { cursor ->
                val byName = mutableMapOf<String, Uri>()
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1)?.lowercase() ?: continue
                    byName[name] = ContentUris.withAppendedId(collection, id)
                }
                ArtworkFileNames.ACCEPTED.firstNotNullOfOrNull { (candidate, _) ->
                    byName[candidate]?.let { it to candidate }
                }
            }
        } catch (exception: SecurityException) {
            AppLogger.w(TAG, "Sin permiso para buscar portadas en MediaStore")
            null
        } catch (exception: Exception) {
            AppLogger.w(TAG, "Error buscando portadas: ${exception.javaClass.simpleName}")
            null
        }
    }

    // ------------------------------------------------------------------ bytes

    private suspend fun loadOriginal(source: ArtworkSource): ByteArray? {
        if (source.isEmbedded) {
            val uri = Uri.parse(source.ownerUri)
            val head = fileAccess.readHead(uri, EMBEDDED_READ_BYTES).getOrNull() ?: return null
            val codec = Codec.fromMimeType(context.contentResolver.getType(uri))
            return EmbeddedArtwork.extract(head, codec)?.bytes
        }
        return fileAccess.readBytes(Uri.parse(source.ownerUri), MAX_ARTWORK_BYTES).getOrNull()
    }

    /** Decodes a bounds-only pass, then asks for exactly the size the UI needs. */
    private fun downscale(original: ByteArray, targetPx: Int): ByteArray? {
        val bounds = readBounds(original)
        val longest = maxOf(bounds?.first ?: 0, bounds?.second ?: 0)
        val sampleSize = if (longest > 0) {
            var sample = 1
            while (longest / (sample * 2) >= targetPx.coerceAtLeast(MIN_TARGET_PX)) sample *= 2
            sample
        } else {
            1
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeByteArray(original, 0, original.size, options) ?: return null

        val scaled = if (maxOf(decoded.width, decoded.height) > targetPx.coerceAtLeast(MIN_TARGET_PX) * 2) {
            val ratio = targetPx.coerceAtLeast(MIN_TARGET_PX).toFloat() / maxOf(decoded.width, decoded.height)
            Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt(), (decoded.height * ratio).toInt(), true)
        } else {
            decoded
        }

        val format = if (scaled.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val output = java.io.ByteArrayOutputStream(scaled.byteCount / 2 + 1024)
        scaled.compress(format, if (format == Bitmap.CompressFormat.JPEG) 88 else 100, output)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return output.toByteArray()
    }

    private fun readBounds(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth
        val height = options.outHeight
        return if (width > 0 && height > 0) width to height else null
    }

    private fun cacheFileFor(source: ArtworkSource, targetPx: Int): File {
        val bucket = targetPx.coerceAtLeast(MIN_TARGET_PX).let { if (it <= 128) 128 else if (it <= 192) 192 else 256 }
        return File(cacheDir, "${sha1(source.key)}-$bucket.img")
    }

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        return digest.digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun guessMime(fileName: String): String? = when (fileName.lowercase().substringAfterLast('.', "")) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> null
    }

    private companion object {
        const val TAG = "ArtworkRepository"
        const val CACHE_DIR_NAME = "artwork"
        const val EMBEDDED_READ_BYTES = 1024 * 1024
        const val MAX_ARTWORK_BYTES = 12 * 1024 * 1024
        const val DEFAULT_TARGET_PX = 256
        const val MIN_TARGET_PX = 96
    }
}
