package com.hifiplayer.core.storage

import android.net.Uri
import com.hifiplayer.domain.model.library.LibrarySource

/**
 * A file the scanner found, before any expensive work has been done on it.
 *
 * Nothing here is assumed: [mimeType] comes from the provider, and the real codec/rate/depth are
 * determined later by `core:metadata` probing the file header.
 */
data class AudioFileCandidate(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedEpochSec: Long,
    val mimeType: String?,
    val relativePath: String?,
    val folderPath: String,
    val source: LibrarySource,
    val dateAddedEpochSec: Long = lastModifiedEpochSec,
    val mediaStoreId: Long? = null,
    /** Album id from MediaStore, when available: free grouping without parsing tags. */
    val mediaStoreAlbumId: String? = null,
) {
    val extension: String get() = displayName.substringAfterLast('.', "").lowercase()
}

/** Result of validating a candidate/URI before it is used. */
sealed interface FileValidation {
    data object Valid : FileValidation
    data class Missing(val reason: String) : FileValidation
    data class NotReadable(val reason: String) : FileValidation
    data class TooSmall(val sizeBytes: Long) : FileValidation
    data class UnsupportedContainer(val extension: String) : FileValidation

    val isValid: Boolean get() = this is Valid
}
