package com.hifiplayer.core.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.hifiplayer.core.common.error.toAppError
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.outcomeOf
import com.hifiplayer.domain.model.error.StorageError
import java.io.InputStream

/**
 * Single place that opens files, so validation rules (requirement 40) are applied uniformly:
 * URI present, readable, right size, real MIME – and never a raw exception leaking upwards.
 */
class FileAccess(private val context: Context) {

    /** Cheap metadata query: display name, size and declared MIME. */
    suspend fun describe(uri: Uri): Outcome<FileDescription> = outcomeOf("FileAccess.describe") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw java.io.FileNotFoundException(uri.toString())
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            FileDescription(
                displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else uri.lastPathSegment.orEmpty(),
                sizeBytes = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L,
                mimeType = context.contentResolver.getType(uri),
            )
        } ?: throw java.io.FileNotFoundException(uri.toString())
    }

    suspend fun openInputStream(uri: Uri): Outcome<InputStream> = outcomeOf("FileAccess.openInputStream") {
        context.contentResolver.openInputStream(uri)
            ?: throw java.io.FileNotFoundException("no se pudo abrir $uri")
    }

    /**
     * Validates that a URI is usable for playback before the engine is asked to open it, which
     * turns "mysterious playback error" into a precise, actionable message.
     */
    suspend fun validate(uri: Uri, minimumSizeBytes: Long = 0L): Outcome<FileValidation> = outcomeOf("FileAccess.validate") {
        val description = when (val result = describe(uri)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> return@outcomeOf mapFailure(result)
        }
        when {
            !description.exists -> FileValidation.Missing("el proveedor no encuentra el documento")
            description.sizeBytes in 1 until minimumSizeBytes -> FileValidation.TooSmall(description.sizeBytes)
            else -> FileValidation.Valid
        }
    }

    private fun mapFailure(failure: Outcome.Failure): FileValidation =
        FileValidation.NotReadable(failure.error.technicalDetail ?: failure.error.userMessage)

    /** Reads up to [maxBytes] from the head of the file: used by the format probe. */
    suspend fun readHead(uri: Uri, maxBytes: Int): Outcome<ByteArray> = outcomeOf("FileAccess.readHead") {
        openInputStream(uri).getOrElse { throw com.hifiplayer.core.common.error.TypedAppException(it) }.use { stream ->
            val buffer = ByteArray(maxBytes)
            var read = 0
            while (read < maxBytes) {
                val count = stream.read(buffer, read, maxBytes - read)
                if (count <= 0) break
                read += count
            }
            if (read == maxBytes) buffer else buffer.copyOf(read)
        }
    }

    /** Full read used by analysis passes (loudness scanning). Cancellable by coroutine. */
    fun openForAnalysis(uri: Uri): InputStream? = try {
        context.contentResolver.openInputStream(uri)
    } catch (security: SecurityException) {
        AppLogger.w(TAG, "Permiso revocado al abrir un archivo para análisis")
        null
    } catch (io: Exception) {
        AppLogger.w(TAG, "No se pudo abrir un archivo para análisis: ${io.javaClass.simpleName}")
        null
    }

    fun storageErrorFor(uri: Uri, cause: Throwable): StorageError =
        when (val error = cause.toAppError("FileAccess")) {
            is StorageError -> error
            else -> StorageError.Unknown("${error.code} al acceder a ${uri.lastPathSegment ?: "archivo"}")
        }

    data class FileDescription(
        val displayName: String,
        val sizeBytes: Long,
        val mimeType: String?,
        val exists: Boolean = true,
    )

    private companion object {
        const val TAG = "FileAccess"
    }
}
