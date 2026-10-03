package com.hifiplayer.core.common.error

import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.domain.model.error.AppError
import com.hifiplayer.domain.model.error.AudioDeviceError
import com.hifiplayer.domain.model.error.MetadataError
import com.hifiplayer.domain.model.error.PermissionError
import com.hifiplayer.domain.model.error.PlaybackError
import com.hifiplayer.domain.model.error.StorageError
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * Translates platform exceptions into the typed errors declared in `domain:model`
 * (`PlaybackError`, `StorageError`, `MetadataError`, `AudioDeviceError`, ...).
 *
 * Requirement 31: no empty catch blocks, ever. Anything that throws is mapped here, logged with
 * its code and surfaced to the user with a message that says what happened and what to do.
 *
 * This class is deliberately platform-independent: Android-specific exceptions
 * (`SQLiteException`, `MediaCodec.CodecException`, `SecurityException` with content URIs, ...)
 * are recognised by class name so the domain stays free of Android imports.
 */
interface ErrorMapper {
    fun map(throwable: Throwable, contextTag: String): AppError

    companion object {
        val Default: ErrorMapper = DefaultErrorMapper()

        /** Overridable in tests. */
        @Volatile
        var override: ErrorMapper? = null

        fun current(): ErrorMapper = override ?: Default
    }
}

fun Throwable.toAppError(contextTag: String): AppError = ErrorMapper.current().map(this, contextTag)

/** Lets inner layers throw a typed error while preserving the stack trace. */
class TypedAppException(val error: AppError, cause: Throwable? = null) : RuntimeException(error.userMessage, cause)

class DefaultErrorMapper : ErrorMapper {

    override fun map(throwable: Throwable, contextTag: String): AppError {
        val className = throwable.javaClass.name
        return when {
            throwable is TypedAppException -> throwable.error

            throwable is FileNotFoundException -> PlaybackError.FileNotFound(
                uri = throwable.message.orEmpty(),
                reason = "archivo no encontrado",
            )

            className == "android.database.sqlite.SQLiteException" ||
                className == "android.database.sqlite.SQLiteDatabaseCorruptException" ->
                StorageError.DeviceUnavailable("Base de datos: ${throwable.message ?: "error de SQLite"}")

            throwable is SecurityException -> PermissionError.Denied(
                permission = throwable.message.orEmpty().ifBlank { "desconocido" },
                scope = "leer tu música",
            )

            throwable is TimeoutException -> PlaybackError.Unknown(
                errorCodeName = "TIMEOUT",
                cause = throwable.message ?: "la operación agotó el tiempo de espera",
            )

            throwable is OutOfMemoryError -> StorageError.Unknown(
                "Memoria insuficiente en $contextTag (revisa el tamaño de portada en caché)",
            )

            throwable is StackOverflowError -> StorageError.Unknown("Desbordamiento de pila en $contextTag")

            throwable is IOException -> StorageError.Unknown(
                "IOException en $contextTag: ${throwable.message ?: throwable.javaClass.simpleName}",
            )

            className.contains("MediaCodec") -> PlaybackError.Unknown(
                errorCodeName = "MEDIACODEC",
                cause = "${throwable.javaClass.simpleName}: ${throwable.message}",
            )

            className.contains("Usb") -> AudioDeviceError.Unknown("USB en $contextTag: ${throwable.message}")

            className.contains("jaudiotagger") || className.contains("TagException") || className.contains("Tag") ->
                MetadataError.Unknown(uri = "", detail = "${throwable.javaClass.simpleName}: ${throwable.message}")

            else -> StorageError.Unknown("$contextTag: ${throwable.javaClass.simpleName}: ${throwable.message ?: "sin detalle"}")
        }
    }
}

/** Maps a Media3 `PlaybackException` without importing Media3 into the domain. */
fun playbackFailure(
    errorCode: Int,
    errorCodeName: String,
    cause: Throwable?,
    uri: String?,
    isDecoderUnavailable: Boolean = false,
    codecName: String? = null,
): AppError = if (isDecoderUnavailable && codecName != null) {
    PlaybackError.DecoderUnavailable(
        codecName = codecName,
        reason = cause?.message,
        technicalDetail = "code=$errorCode ($errorCodeName) uri=$uri",
    )
} else {
    PlaybackError.Unknown(
        errorCodeName = errorCodeName,
        cause = cause?.let { "${it.javaClass.simpleName}: ${it.message}" },
        uri = uri,
    )
}

/** Logs an error once, with its stable code, and returns it so callers can propagate it. */
fun AppError.logAndReturn(tag: String): AppError {
    AppLogger.e(tag, "Error ${code}: $userMessage", technicalDetail?.let { RuntimeException(it) })
    return this
}
