package com.hifiplayer.nativeaudio.engine

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.hifiplayer.domain.model.library.Track

/**
 * Track → MediaItem in one place (requirement 27).
 *
 * The notification, the lock screen, the Bluetooth display and Android Auto all read what is inside
 * the [MediaMetadata] of the item that is playing. Building that metadata anywhere else — or nowhere,
 * which is what happened until now — is how a Hi-Fi player ends up showing "Unknown artist" and no
 * artwork on the lock screen while claiming to know the sample rate of the file.
 *
 * It is public because the library browsing session builds items too, and both must describe the same
 * track in the same way.
 */
public fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setUri(uri)
    .setMediaId(id)
    // MIME hint helps ExoPlayer pick the right extractor for extension-less content URIs.
    .apply { mimeType?.let { setMimeType(it) } }
    .setMediaMetadata(toMediaMetadata())
    .build()

public fun Track.toMediaMetadata(): MediaMetadata {
    val builder = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setAlbumArtist(albumArtist)
        .setGenre(genre)
        // The artwork the user sees in the notification is the same one the library resolved
        // (embedded → cover.jpg → folder.jpg → artwork.jpg). Nothing here invents an image.
        .apply { artworkUri?.takeIf { it.isNotBlank() }?.let { setArtworkUri(it.toUri()) } }
        .apply { trackNumber?.let { setTrackNumber(it) } }
        .apply { year?.takeIf { it > 0 }?.let { setReleaseYear(it) } }
        .apply {
            // `isPlayable` has to be stated for items served to other apps (Android Auto): a browsable
            // folder that is not playable must not offer a play button.
            setIsBrowsable(false)
            setIsPlayable(true)
        }
    format?.let { spec ->
        builder.setExtras(
            android.os.Bundle().apply {
                putString(EXTRA_FORMAT_LABEL, spec.label)
                putString(EXTRA_CODEC, spec.codec.displayName)
                putInt(EXTRA_SAMPLE_RATE, spec.sampleRateHz)
                putInt(EXTRA_BIT_DEPTH, spec.bitDepth)
                putInt(EXTRA_CHANNELS, spec.channels)
                putBoolean(EXTRA_LOSSLESS, spec.isLossless)
            },
        )
    }
    return builder.build()
}

/** Keys for the technical extras, so a controller can read them without guessing strings. */
public const val EXTRA_FORMAT_LABEL: String = "com.hifiplayer.extra.FORMAT_LABEL"
public const val EXTRA_CODEC: String = "com.hifiplayer.extra.CODEC"
public const val EXTRA_SAMPLE_RATE: String = "com.hifiplayer.extra.SAMPLE_RATE_HZ"
public const val EXTRA_BIT_DEPTH: String = "com.hifiplayer.extra.BIT_DEPTH"
public const val EXTRA_CHANNELS: String = "com.hifiplayer.extra.CHANNELS"
public const val EXTRA_LOSSLESS: String = "com.hifiplayer.extra.LOSSLESS"
