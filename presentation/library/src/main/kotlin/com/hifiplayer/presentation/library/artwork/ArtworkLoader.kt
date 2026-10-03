package com.hifiplayer.presentation.library.artwork

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hifiplayer.domain.model.library.Track

/**
 * Loads cover art for the UI.
 *
 * It is an interface so the presentation layer never reaches for a repository: the app module wires
 * an implementation on top of the artwork use cases (resolve → load already-scaled bytes → decode),
 * and the cache lives in that implementation because caching is where the measurements are
 * (requirement 37: no main-thread decode, small images in lists, real full art only in the player).
 */
interface ArtworkLoader {
    /** Returns the artwork for [track] scaled to [targetPx] on the longest side, or null if none. */
    suspend fun load(track: Track, targetPx: Int): ImageBitmap?

    /**
     * Same, for a cover that is not tied to a single track (album grids, where the album carries a
     * representative file). [cacheKey] only has to be stable per artwork source.
     */
    suspend fun loadUri(uri: String, cacheKey: String, targetPx: Int): ImageBitmap?
}

val LocalArtworkLoader = staticCompositionLocalOf<ArtworkLoader?> { null }

/**
 * Cover art for a list or a detail header.
 *
 * While loading, and when there is no artwork at all, it draws the same flat placeholder: a list
 * that jumps when covers arrive is worse than a list that shows a neutral tile. Nothing is faked —
 * the placeholder is a music glyph, never a made-up cover.
 */
@Composable
fun TrackArtwork(
    track: Track?,
    size: Dp,
    modifier: Modifier = Modifier,
    targetPx: Int = ArtworkSizes.LIST_PX,
    fallbackIcon: Boolean = true,
) {
    val loader = LocalArtworkLoader.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, track?.id, targetPx, loader) {
        value = if (loader == null || track == null) null else loader.load(track, targetPx)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                // Decorative: the track title right next to it already describes the row.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (fallbackIcon) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}

/** Album cover placeholder, which uses the album glyph instead of the note. */
@Composable
fun AlbumCover(
    coverUri: String?,
    cacheKey: String,
    size: Dp,
    modifier: Modifier = Modifier,
    targetPx: Int = ArtworkSizes.GRID_PX,
) {
    val loader = LocalArtworkLoader.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, cacheKey, coverUri, targetPx, loader) {
        value = if (loader == null || coverUri == null) null else loader.loadUri(coverUri, cacheKey, targetPx)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Album,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.3f),
            )
        }
    }
}

/** Cover sizes the UI asks for, in pixels, so nothing is ever decoded bigger than it is drawn. */
object ArtworkSizes {
    /** Row thumbnails: 44 dp at 3x is 132 px. */
    const val LIST_PX = 160

    /** Album grids and detail headers. */
    const val GRID_PX = 320

    /** Now Playing, which is the only place that gets a large decode. */
    const val DETAIL_PX = 640
}
