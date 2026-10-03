package com.hifiplayer.domain.model.library

import com.hifiplayer.domain.model.audio.AudioFormatSpec
import com.hifiplayer.domain.model.audio.Codec
import com.hifiplayer.domain.model.audio.ReplayGainInfo

/** Where a track was discovered. Needed to re-open the file without asking for permissions again. */
public sealed interface LibrarySource {
    public val label: String

    public data object MediaStore : LibrarySource {
        override val label: String get() = "MediaStore"
    }

    /** A tree URI granted through the Storage Access Framework (SD card, USB OTG, SAF folders). */
    public data class DocumentTree(public val treeUri: String) : LibrarySource {
        override val label: String get() = "Carpeta SAF"
    }

    /** A single file (opened from a file manager / shared into the app). */
    public data class SingleFile(public val uri: String) : LibrarySource {
        override val label: String get() = "Archivo suelto"
    }
}

/**
 * A playable audio file plus everything we have verified about it.
 *
 * [format] is null until the header has been probed. The UI shows "Analizando…" instead of
 * guessing, and "—" instead of a made-up bit depth.
 */
public data class Track(
    public val id: String,
    public val uri: String,
    public val title: String,
    public val artist: String?,
    public val albumArtist: String?,
    public val album: String?,
    public val albumId: String?,
    public val artistId: String?,
    public val durationMs: Long,
    public val trackNumber: Int?,
    public val discNumber: Int?,
    public val year: Int?,
    public val genre: String?,
    public val sizeBytes: Long,
    public val mimeType: String?,
    public val displayName: String,
    public val relativePath: String?,
    public val dateAddedEpochSec: Long,
    public val lastModifiedEpochSec: Long,
    public val format: AudioFormatSpec?,
    public val replayGain: ReplayGainInfo = ReplayGainInfo.EMPTY,
    public val hasEmbeddedArtwork: Boolean = false,
    public val artworkUri: String? = null,
    public val composer: String? = null,
    public val copyright: String? = null,
    public val source: LibrarySource = LibrarySource.MediaStore,
) {
    public val codec: Codec get() = format?.codec ?: Codec.UNKNOWN
    public val isLossless: Boolean get() = format?.isLossless == true
    public val isHighResolution: Boolean get() = format?.isHighResolution == true

    /** Folder path relative to the storage root, derived from the provider, not from the title. */
    public val folderPath: String
        get() {
            val rel = relativePath ?: return ""
            val trimmed = rel.trim('/')
            val idx = trimmed.lastIndexOf('/')
            return if (idx <= 0) "" else trimmed.substring(0, idx)
        }

    // --- Aliases required by the product spec, so call sites read naturally ---
    /** Path relative to the storage root as reported by the provider. */
    public val filePath: String get() = relativePath ?: ""
    public val sampleRateHz: Int? get() = format?.sampleRateHz
    public val bitDepth: Int? get() = format?.bitDepth
    public val channels: Int? get() = format?.channels
    public val bitrateKbps: Int? get() = format?.bitrateKbps
    public val replayGainTrack: Double? get() = replayGain.trackGainDb
    public val replayGainAlbum: Double? get() = replayGain.albumGainDb

    public val displayTitle: String get() = title.ifBlank { displayName }
    public val displayArtist: String get() = artist?.takeIf { it.isNotBlank() } ?: "Artista desconocido"
    public val displayAlbum: String get() = album?.takeIf { it.isNotBlank() } ?: "Álbum desconocido"

    /** Sort-friendly key so albums and multi-disc sets keep their order. */
    public val discTrackKey: Int get() = ((discNumber ?: 1) * 10_000) + (trackNumber ?: 0)

    public fun withFormat(format: AudioFormatSpec?): Track = copy(format = format)
    public fun withReplayGain(info: ReplayGainInfo): Track = copy(replayGain = info)
}

public data class Album(
    public val id: String,
    public val title: String,
    public val artist: String?,
    public val year: Int?,
    public val trackCount: Int,
    public val totalDurationMs: Long,
    public val coverTrackUri: String?,
    public val maxSampleRateHz: Int?,
    public val maxBitDepth: Int?,
    public val isLossless: Boolean,
    public val hasHighResolution: Boolean,
) {
    public val displayArtist: String get() = artist?.takeIf { it.isNotBlank() } ?: "Artista desconocido"
    public val formatSummary: String
        get() {
            val depth = maxBitDepth ?: return ""
            val rate = maxSampleRateHz ?: return "$depth-bit"
            return "$depth-bit / ${rate / 1000} kHz"
        }
}

public data class Artist(
    public val id: String,
    public val name: String,
    public val albumCount: Int,
    public val trackCount: Int,
)

public data class Genre(
    public val name: String,
    public val trackCount: Int,
)

public data class LibraryFolder(
    public val path: String,
    public val displayName: String,
    public val trackCount: Int,
    public val totalSizeBytes: Long,
    public val source: LibrarySource,
)

public data class LibraryStats(
    public val trackCount: Int,
    public val albumCount: Int,
    public val artistCount: Int,
    public val totalDurationMs: Long,
    public val totalSizeBytes: Long,
    public val losslessCount: Int,
    public val highResolutionCount: Int,
    public val unknownFormatCount: Int,
    public val codecBreakdown: Map<Codec, Int>,
)

/** Progress of a library scan/indexing pass, surfaced in the UI. */
public data class ScanProgress(
    public val isRunning: Boolean,
    public val processed: Int,
    public val total: Int,
    public val currentFile: String? = null,
    public val phase: ScanPhase = ScanPhase.IDLE,
    public val bytesAnalyzed: Long = 0L,
) {
    public val percent: Int get() = if (total <= 0) 0 else ((processed * 100) / total).coerceIn(0, 100)

    public companion object {
        public val IDLE: ScanProgress = ScanProgress(isRunning = false, processed = 0, total = 0)
    }
}

public enum class ScanPhase(public val displayName: String) {
    IDLE("En reposo"),
    QUERYING("Consultando la biblioteca"),
    ANALYZING("Analizando cabeceras de audio"),
    READING_TAGS("Leyendo etiquetas y ReplayGain"),
    DONE("Completado"),
    CANCELLED("Cancelado"),
    FAILED("Con errores"),
}

/** Sort orders the library offers. */
public enum class TrackSort(public val displayName: String) {
    TITLE("Título"),
    ARTIST("Artista"),
    ALBUM("Álbum"),
    DATE_ADDED("Añadido recientemente"),
    YEAR("Año"),
    DURATION("Duración"),
    FORMAT("Calidad (mayor primero)"),
    FILENAME("Nombre de archivo"),
}
