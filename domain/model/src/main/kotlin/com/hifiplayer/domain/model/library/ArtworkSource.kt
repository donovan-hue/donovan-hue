package com.hifiplayer.domain.model.library

/**
 * Where a cover image came from. The priority order is fixed by the product spec (requirement 19)
 * and is visible in diagnostics, so nobody has to guess which file was used.
 */
data class ArtworkSource(
    val key: String,
    val priority: Priority,
    /** For embedded art: the track it belongs to. For sidecar files: their URI. */
    val ownerUri: String,
    val fileName: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
) {
    enum class Priority(val order: Int, val displayName: String) {
        EMBEDDED(1, "Incrustada en el archivo"),
        COVER_JPG(2, "cover.jpg"),
        FOLDER_JPG(3, "folder.jpg"),
        ARTWORK_JPG(4, "artwork.jpg"),
        GENERATED(5, "Generada por la app");
    }

    val isEmbedded: Boolean get() = priority == Priority.EMBEDDED
}

/** Sidecar file names accepted, in priority order (requirement 19). */
object ArtworkFileNames {
    val ACCEPTED: List<Pair<String, ArtworkSource.Priority>> = listOf(
        "cover.jpg" to ArtworkSource.Priority.COVER_JPG,
        "cover.jpeg" to ArtworkSource.Priority.COVER_JPG,
        "cover.png" to ArtworkSource.Priority.COVER_JPG,
        "folder.jpg" to ArtworkSource.Priority.FOLDER_JPG,
        "folder.jpeg" to ArtworkSource.Priority.FOLDER_JPG,
        "folder.png" to ArtworkSource.Priority.FOLDER_JPG,
        "artwork.jpg" to ArtworkSource.Priority.ARTWORK_JPG,
        "artwork.jpeg" to ArtworkSource.Priority.ARTWORK_JPG,
        "artwork.png" to ArtworkSource.Priority.ARTWORK_JPG,
    )
}
