package com.hifiplayer.domain.model.audio

/** Where the ReplayGain values came from, so users know how much to trust them. */
enum class ReplayGainSource(val displayName: String) {
    NONE("Sin datos"),
    VORBIS_COMMENT("Vorbis comment (FLAC/Ogg)"),
    ID3V2_TXXX("ID3v2 TXXX (MP3)"),
    ID3V2_RVA2("ID3v2 RVA2 (MP3)"),
    APE_TAG("APEv2 (MP3/APE)"),
    ITUNES("iTunNORM (M4A)"),
    LAME("Cabecera LAME (MP3)"),
    CALCULATED("Analizado por la app");

    val isEmpty: Boolean get() = this == NONE
}

/**
 * ReplayGain values *as stored in the file* (requirement 13). Applying them never rewrites the
 * file: it is playback-time processing only.
 */
data class ReplayGainInfo(
    val trackGainDb: Double? = null,
    val albumGainDb: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
    val trackLoudnessLufs: Double? = null,
    val albumLoudnessLufs: Double? = null,
    val source: ReplayGainSource = ReplayGainSource.NONE,
) {
    val hasTrackValues: Boolean get() = trackGainDb != null
    val hasAlbumValues: Boolean get() = albumGainDb != null
    val hasAnyValue: Boolean get() = hasTrackValues || hasAlbumValues
    val hasAnyPeak: Boolean get() = trackPeak != null || albumPeak != null

    fun gainFor(mode: ReplayGainMode): Double? = when (mode) {
        ReplayGainMode.OFF -> null
        ReplayGainMode.TRACK -> trackGainDb
        ReplayGainMode.ALBUM -> albumGainDb ?: trackGainDb
    }

    fun peakFor(mode: ReplayGainMode): Double? = when (mode) {
        ReplayGainMode.OFF -> null
        ReplayGainMode.TRACK -> trackPeak
        ReplayGainMode.ALBUM -> albumPeak ?: trackPeak
    }

    companion object {
        val EMPTY: ReplayGainInfo = ReplayGainInfo()
    }
}

/** Requirement 13: OFF / TRACK / ALBUM. */
enum class ReplayGainMode(val displayName: String, val shortLabel: String) {
    OFF("Desactivado", "OFF"),
    TRACK("Por pista", "TRACK"),
    ALBUM("Por álbum", "ALBUM");

    val isEnabled: Boolean get() = this != OFF
}
