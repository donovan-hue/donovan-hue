package com.hifiplayer.domain.model.error

/**
 * Requirement 31: explicit, typed errors. Nothing in this app throws a bare exception to the
 * UI, and `catch (Exception) { }` is forbidden – every failure becomes one of these.
 */
sealed class AppError {
    /** Stable code for logs and diagnostics (never shown to the user). */
    abstract val code: String

    /** Message written for the user, in Spanish, actionable when possible. */
    abstract val userMessage: String

    /** Technical detail for the diagnostics screen and bug reports. */
    open val technicalDetail: String? = null

    /** Whether retrying or choosing something else can fix it. */
    open val recoverable: Boolean = true
}

// --------------------------- Playback ---------------------------

sealed class PlaybackError : AppError() {

    data class DecoderUnavailable(
        val codecName: String,
        val reason: String? = null,
        override val technicalDetail: String? = null,
    ) : PlaybackError() {
        override val code: String = "E_PLAYBACK_DECODER_UNAVAILABLE"
        override val userMessage: String = if (codecName.equals("ALAC", ignoreCase = true)) {
            "Este dispositivo no incluye un decodificador de ALAC. Convierte el archivo a FLAC/WAV sin pérdida o usa otra pista."
        } else {
            "No hay decodificador disponible para $codecName en este dispositivo."
        }
        override val recoverable: Boolean = false
    }

    data class UnsupportedFormat(
        val formatLabel: String,
        val fileName: String? = null,
        override val technicalDetail: String? = null,
    ) : PlaybackError() {
        override val code: String = "E_PLAYBACK_UNSUPPORTED_FORMAT"
        override val userMessage: String = "El formato $formatLabel no es compatible con este dispositivo."
        override val recoverable: Boolean = false
    }

    data class FileNotFound(
        val uri: String,
        val reason: String? = null,
    ) : PlaybackError() {
        override val code: String = "E_PLAYBACK_FILE_NOT_FOUND"
        override val userMessage: String =
            "No se encontró el archivo. Puede que se haya movido, eliminado o que el almacenamiento esté desconectado."
        override val technicalDetail: String = "uri=$uri reason=${reason ?: "-"}"
    }

    data class PermissionRevoked(val scope: String) : PlaybackError() {
        override val code: String = "E_PLAYBACK_PERMISSION"
        override val userMessage: String = "Se revocó el permiso de acceso a $scope. Vuelve a autorizarlo para seguir reproduciendo."
    }

    data class AudioTrackInitFailed(val detail: String) : PlaybackError() {
        override val code: String = "E_PLAYBACK_AUDIO_TRACK_INIT"
        override val userMessage: String = "No se pudo inicializar la salida de audio del sistema."
        override val technicalDetail: String = detail
    }

    data class AudioTrackWriteFailed(val detail: String) : PlaybackError() {
        override val code: String = "E_PLAYBACK_AUDIO_TRACK_WRITE"
        override val userMessage: String = "Se perdió la salida de audio durante la reproducción."
        override val technicalDetail: String = detail
    }

    data class CorruptFile(val uri: String, val detail: String? = null) : PlaybackError() {
        override val code: String = "E_PLAYBACK_CORRUPT"
        override val userMessage: String = "El archivo está dañado o incompleto y no se puede reproducir."
        override val technicalDetail: String = "uri=$uri detail=${detail ?: "-"}"
        override val recoverable: Boolean = false
    }

    data class Unknown(
        val errorCodeName: String,
        val cause: String? = null,
        val uri: String? = null,
    ) : PlaybackError() {
        override val code: String = "E_PLAYBACK_$errorCodeName"
        override val userMessage: String = "Ocurrió un error de reproducción ($errorCodeName)."
        override val technicalDetail: String = buildString {
            append("code=$errorCodeName")
            uri?.let { append(" uri=").append(it) }
            cause?.let { append(" cause=").append(it) }
        }
    }
}

// --------------------------- Storage ---------------------------

sealed class StorageError : AppError() {

    data class PermissionRevoked(val uri: String) : StorageError() {
        override val code: String = "E_STORAGE_PERMISSION"
        override val userMessage: String =
            "La app perdió el acceso a la carpeta seleccionada. Vuelve a elegirla en Ajustes › Biblioteca."
        override val technicalDetail: String = "uri=$uri"
    }

    data class SafTreeUnavailable(val treeUri: String) : StorageError() {
        override val code: String = "E_STORAGE_SAF_TREE"
        override val userMessage: String = "La carpeta autorizada ya no está disponible (¿se desconectó el almacenamiento externo?)."
        override val technicalDetail: String = "tree=$treeUri"
    }

    data class NoSpaceLeft(val requiredBytes: Long, val availableBytes: Long) : StorageError() {
        override val code: String = "E_STORAGE_NO_SPACE"
        override val userMessage: String = "No hay espacio suficiente para completar la operación."
        override val technicalDetail: String = "required=$requiredBytes available=$availableBytes"
    }

    data class DeviceUnavailable(val detail: String) : StorageError() {
        override val code: String = "E_STORAGE_DEVICE_UNAVAILABLE"
        override val userMessage: String = "El almacenamiento no está disponible en este momento."
        override val technicalDetail: String = detail
    }

    data class ScanFailed(val phase: String, val cause: String) : StorageError() {
        override val code: String = "E_STORAGE_SCAN"
        override val userMessage: String = "La indexación falló en la fase \"$phase\"."
        override val technicalDetail: String = cause
    }

    data class FileMissing(val uri: String) : StorageError() {
        override val code: String = "E_STORAGE_FILE_MISSING"
        override val userMessage: String = "El archivo ya no existe en la ubicación indexada."
        override val technicalDetail: String = "uri=$uri"
    }

    data class CorruptFile(val uri: String, val detail: String? = null) : StorageError() {
        override val code: String = "E_STORAGE_CORRUPT"
        override val userMessage: String = "El archivo está dañado."
        override val technicalDetail: String = "uri=$uri detail=${detail ?: "-"}"
        override val recoverable: Boolean = false
    }

    data class Unknown(val detail: String) : StorageError() {
        override val code: String = "E_STORAGE_UNKNOWN"
        override val userMessage: String = "Ocurrió un problema de almacenamiento."
        override val technicalDetail: String = detail
    }
}

// --------------------------- Metadata ---------------------------

sealed class MetadataError : AppError() {

    data class ParseFailed(val uri: String, val cause: String) : MetadataError() {
        override val code: String = "E_METADATA_PARSE"
        override val userMessage: String = "No se pudieron leer las etiquetas; se mostrará la información básica del archivo."
        override val technicalDetail: String = "uri=$uri cause=$cause"
    }

    data class CorruptHeader(val uri: String, val detail: String? = null) : MetadataError() {
        override val code: String = "E_METADATA_CORRUPT_HEADER"
        override val userMessage: String = "La cabecera del archivo no se pudo interpretar; se omitirá el análisis de formato."
        override val technicalDetail: String = "uri=$uri detail=${detail ?: "-"}"
    }

    data class ArtworkFailed(val uri: String, val cause: String) : MetadataError() {
        override val code: String = "E_METADATA_ARTWORK"
        override val userMessage: String = "No se pudo leer la portada incrustada en el archivo."
        override val technicalDetail: String = "uri=$uri cause=$cause"
    }

    data class UnsupportedContainer(val uri: String, val container: String) : MetadataError() {
        override val code: String = "E_METADATA_CONTAINER"
        override val userMessage: String = "El contenedor $container no es compatible para leer etiquetas."
        override val technicalDetail: String = "uri=$uri container=$container"
        override val recoverable: Boolean = false
    }

    data class Unknown(val uri: String, val detail: String) : MetadataError() {
        override val code: String = "E_METADATA_UNKNOWN"
        override val userMessage: String = "Ocurrió un problema al leer los metadatos."
        override val technicalDetail: String = "uri=$uri detail=$detail"
    }
}

// --------------------------- Audio device ---------------------------

sealed class AudioDeviceError : AppError() {

    data class UsbPermissionDenied(val deviceName: String) : AudioDeviceError() {
        override val code: String = "E_DEVICE_USB_PERMISSION"
        override val userMessage: String =
            "No se autorizó el acceso al DAC USB \"$deviceName\". Se seguirá usando la salida del sistema."
        override val technicalDetail: String = "device=$deviceName"
    }

    data class UsbDeviceDetached(val deviceName: String) : AudioDeviceError() {
        override val code: String = "E_DEVICE_USB_DETACHED"
        override val userMessage: String =
            "El DAC USB \"$deviceName\" se desconectó. La reproducción se pausó para evitar enviar audio a otra salida sin avisar."
        override val technicalDetail: String = "device=$deviceName"
    }

    data class OutputUnavailable(val requestedId: String, val reason: String) : AudioDeviceError() {
        override val code: String = "E_DEVICE_OUTPUT_UNAVAILABLE"
        override val userMessage: String = "La salida seleccionada no está disponible: $reason"
        override val technicalDetail: String = "requested=$requestedId reason=$reason"
    }

    data class CapabilityMismatch(
        val requestedLabel: String,
        val deliveredLabel: String,
        val cause: String,
    ) : AudioDeviceError() {
        override val code: String = "E_DEVICE_CAPABILITY_MISMATCH"
        override val userMessage: String = "El archivo es $requestedLabel y la salida entrega $deliveredLabel ($cause)."
        override val technicalDetail: String = "requested=$requestedLabel delivered=$deliveredLabel cause=$cause"
    }

    data class BitPerfectUnavailable(val blockers: List<String>) : AudioDeviceError() {
        override val code: String = "E_DEVICE_BITPERFECT_UNAVAILABLE"
        override val userMessage: String = blockers.firstOrNull()
            ?: "Bit-perfect no disponible en esta configuración de audio."
        override val technicalDetail: String = blockers.joinToString(" | ")
        override val recoverable: Boolean = true
    }

    data class MixerRejected(val requestedLabel: String, val detail: String) : AudioDeviceError() {
        override val code: String = "E_DEVICE_MIXER_REJECTED"
        override val userMessage: String = "El sistema rechazó el modo sin mezcla para $requestedLabel."
        override val technicalDetail: String = detail
    }

    data class Unknown(val detail: String) : AudioDeviceError() {
        override val code: String = "E_DEVICE_UNKNOWN"
        override val userMessage: String = "Ocurrió un problema con el dispositivo de audio."
        override val technicalDetail: String = detail
    }
}

// --------------------------- Permissions / generic ---------------------------

sealed class PermissionError : AppError() {
    data class Denied(val permission: String, val scope: String) : PermissionError() {
        override val code: String = "E_PERMISSION_DENIED"
        override val userMessage: String = "HiFi Player necesita permiso para $scope."
        override val technicalDetail: String = "permission=$permission"
    }

    data class PermanentlyDenied(val permission: String, val scope: String) : PermissionError() {
        override val code: String = "E_PERMISSION_PERMANENTLY_DENIED"
        override val userMessage: String =
            "El permiso para $scope está bloqueado. Actívalo desde los ajustes del sistema."
        override val technicalDetail: String = "permission=$permission"
        override val recoverable: Boolean = false
    }

    data class NotRequired(val detail: String) : PermissionError() {
        override val code: String = "E_PERMISSION_NOT_REQUIRED"
        override val userMessage: String = "Esta versión no necesita ese permiso: $detail"
        override val technicalDetail: String = detail
    }
}

/** Used where a capability genuinely does not exist yet – never claimed as working. */
data class UnsupportedByDesign(
    val feature: String,
    val explanation: String,
) : AppError() {
    override val code: String = "E_NOT_IMPLEMENTED"
    override val userMessage: String = explanation
    override val technicalDetail: String = "feature=$feature"
    override val recoverable: Boolean = false
}

/** Invalid call (wrong state, missing preconditions). Should be rare and always a bug. */
data class IllegalState(val detail: String) : AppError() {
    override val code: String = "E_ILLEGAL_STATE"
    override val userMessage: String = "La acción no está disponible en este momento."
    override val technicalDetail: String = detail
}
