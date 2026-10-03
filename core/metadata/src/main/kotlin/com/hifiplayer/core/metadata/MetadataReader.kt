package com.hifiplayer.core.metadata

import android.net.Uri
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.result.Outcome
import com.hifiplayer.core.common.result.outcomeOf
import com.hifiplayer.core.storage.FileAccess

/**
 * Single entry point used by the scanner: reads what the file *actually* says.
 *
 * The reader never guesses. If the header cannot be parsed, [format] is null; if the container
 * carries no tags, [tags] is null; both cases are reported with a note so the UI can be explicit
 * ("sin analizar", "sin etiquetas") rather than pretending.
 */
class MetadataReader(private val fileAccess: FileAccess) {

    /**
     * @param initialReadBytes head bytes read on the first pass
     * @param extendedReadBytes second, larger pass used when FLAC metadata blocks were cut off
     */
    suspend fun read(
        uri: Uri,
        fileSizeBytes: Long,
        mimeType: String? = null,
        initialReadBytes: Int = INITIAL_READ_BYTES,
        extendedReadBytes: Int = EXTENDED_READ_BYTES,
    ): Outcome<ProbedMetadata> = outcomeOf(TAG) {
        val headResult = fileAccess.readHead(uri, initialReadBytes)
        val head = when (headResult) {
            is Outcome.Success -> headResult.value
            is Outcome.Failure -> return@outcomeOf ProbedMetadata(
                format = null,
                tags = null,
                notes = listOf("No se pudo leer el archivo: ${headResult.error.userMessage}"),
                error = headResult.error,
            )
        }
        if (head.isEmpty()) {
            return@outcomeOf ProbedMetadata(null, null, listOf("El archivo está vacío"), null)
        }

        val format = AudioHeaderProbe.probe(head, fileSizeBytes)
        val notes = mutableListOf<String>()

        // FLAC keeps tags in metadata blocks that a small head read may not cover: read more.
        val shouldExtend = format?.codec == com.hifiplayer.domain.model.audio.Codec.FLAC &&
            !containsVorbisCommentBlock(head) &&
            fileSizeBytes > head.size
        val tagsHead = if (shouldExtend) {
            when (val extended = fileAccess.readHead(uri, extendedReadBytes)) {
                is Outcome.Success -> {
                    if (extended.value.size > head.size) {
                        notes += "Etiquetas leídas en una segunda pasada (bloques FLAC extensos)"
                        extended.value
                    } else {
                        head
                    }
                }
                is Outcome.Failure -> head
            }
        } else {
            head
        }

        val tags = TagParser.parse(tagsHead, format?.codec ?: CodecGuess.fromMimeOrName(mimeType, uri), fileSizeBytes)
        if (tags == null) notes += "El archivo no contiene etiquetas legibles"
        if (format == null) notes += "No se pudo determinar el formato desde la cabecera; se mostrará como \"sin analizar\""
        format?.notes?.let(notes::addAll)
        tags?.let { if (!it.isEmpty && it.embeddedArtworkBytes == null && !it.hasEmbeddedArtwork) notes += "Sin portada incrustada" }

        ProbedMetadata(format = format, tags = tags, notes = notes, error = null)
    }

    private fun containsVorbisCommentBlock(head: ByteArray): Boolean {
        var offset = 4
        while (offset + 4 <= head.size) {
            val header = head[offset].toInt() and 0xFF
            val type = header and 0x7F
            val length = ((head[offset + 1].toInt() and 0xFF) shl 16) or ((head[offset + 2].toInt() and 0xFF) shl 8) or
                (head[offset + 3].toInt() and 0xFF)
            if (type == 4) return true
            if (header and 0x80 != 0) return false
            offset += 4 + length
        }
        return false
    }

    data class ProbedMetadata(
        val format: ProbedFormat?,
        val tags: ParsedTags?,
        val notes: List<String>,
        val error: com.hifiplayer.domain.model.error.AppError?,
    )

    private object CodecGuess {
        fun fromMimeOrName(mimeType: String?, uri: Uri): com.hifiplayer.domain.model.audio.Codec {
            val fromMime = com.hifiplayer.domain.model.audio.Codec.fromMimeType(mimeType)
            if (fromMime != com.hifiplayer.domain.model.audio.Codec.UNKNOWN) return fromMime
            val extension = uri.lastPathSegment?.substringAfterLast('.', "") ?: ""
            return com.hifiplayer.domain.model.audio.Codec.fromExtension(extension)
        }
    }

    private companion object {
        const val TAG = "MetadataReader"
        const val INITIAL_READ_BYTES = 96 * 1024
        const val EXTENDED_READ_BYTES = 1024 * 1024
    }
}
