package com.hifiplayer.core.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.domain.model.audio.Codec
import com.hifiplayer.domain.model.library.LibrarySource

/**
 * Walks a folder the user granted through the Storage Access Framework (requirement 17), e.g. a
 * microSD card or a USB OTG drive.
 *
 * Traversal is iterative and depth-limited; every failure on a subtree is logged and skipped so
 * one unreadable folder cannot abort the whole scan.
 */
class SafFolderSource(private val context: Context) {

    /**
     * Enumerates audio files under [treeUri].
     *
     * @param extensions lowercase extensions without dot; defaults to every format the app knows.
     * @return number of subtree errors that were tolerated.
     */
    fun walk(
        treeUri: Uri,
        extensions: Set<String> = DEFAULT_EXTENSIONS,
        excludedFolders: Set<String> = emptySet(),
        maxDepth: Int = MAX_DEPTH,
        onFile: (AudioFileCandidate) -> Unit,
    ): Int {
        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null || !root.exists() || !root.isDirectory) {
            AppLogger.w(TAG, "Árbol SAF inaccesible: falta permiso o el volumen no está montado")
            return 1
        }

        var errors = 0
        val stack = ArrayDeque<Pair<DocumentFile, Int>>()
        stack.addLast(root to 0)

        while (stack.isNotEmpty()) {
            val (directory, depth) = stack.removeLast()
            if (depth > maxDepth) continue
            val children = try {
                directory.listFiles()
            } catch (security: SecurityException) {
                AppLogger.w(TAG, "Sin permiso para listar una carpeta SAF (se omite)")
                errors++
                continue
            } catch (io: Exception) {
                AppLogger.w(TAG, "Error al listar carpeta SAF: ${io.javaClass.simpleName}")
                errors++
                continue
            }

            for (child in children) {
                when {
                    child.isDirectory -> {
                        val relative = relativePath(root, child)
                        if (excludedFolders.any { relative.isNotBlank() && relative.startsWith(it.trim('/')) }) continue
                        stack.addLast(child to depth + 1)
                    }

                    child.isFile -> {
                        val name = child.name ?: continue
                        val extension = name.substringAfterLast('.', "").lowercase()
                        if (extension !in extensions) continue
                        if (Codec.fromExtension(extension) == Codec.UNKNOWN) continue
                        val relative = relativePath(root, child)
                        onFile(
                            AudioFileCandidate(
                                uri = child.uri,
                                displayName = name,
                                sizeBytes = child.length(),
                                lastModifiedEpochSec = child.lastModified() / 1000L,
                                mimeType = child.type,
                                relativePath = relative,
                                folderPath = relative.substringBeforeLast('/', missingDelimiterValue = ""),
                                source = LibrarySource.DocumentTree(treeUri.toString()),
                            ),
                        )
                    }
                }
            }
        }
        return errors
    }

    /**
     * Finds a sidecar cover in the folder that contains [fileUri], honouring the priority order
     * of requirement 19 (cover.jpg → folder.jpg → artwork.jpg).
     */
    fun findSidecarArtwork(fileUri: Uri): Pair<Uri, String>? {
        val file = DocumentFile.fromSingleUri(context, fileUri) ?: return null
        val parent = file.parentFile ?: return null
        val children = try {
            parent.listFiles()
        } catch (security: SecurityException) {
            return null
        } catch (io: Exception) {
            return null
        }
        val byName = children.associateBy { it.name?.lowercase() ?: "" }
        for ((candidate, _) in SIDECAR_PRIORITY) {
            val match = byName[candidate] ?: continue
            if (match.isFile) return match.uri to candidate
        }
        return null
    }

    private fun relativePath(root: DocumentFile, file: DocumentFile): String {
        val rootName = root.name ?: return file.name ?: ""
        val fileName = file.name ?: return rootName
        return "$rootName/$fileName"
    }

    private companion object {
        const val TAG = "SafFolderSource"
        const val MAX_DEPTH = 12

        val DEFAULT_EXTENSIONS: Set<String> = setOf(
            "flac", "wav", "wave", "aif", "aiff", "aifc", "m4a", "mp4", "mp3", "aac", "ogg", "oga", "opus",
        )

        /** Requirement 19: cover.jpg → folder.jpg → artwork.jpg. */
        val SIDECAR_PRIORITY: List<Pair<String, Int>> = listOf(
            "cover.jpg" to 1, "cover.jpeg" to 1, "cover.png" to 1,
            "folder.jpg" to 2, "folder.jpeg" to 2, "folder.png" to 2,
            "artwork.jpg" to 3, "artwork.jpeg" to 3, "artwork.png" to 3,
        )
    }
}
