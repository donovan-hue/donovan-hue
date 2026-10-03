package com.hifiplayer.domain.model.settings

import com.hifiplayer.domain.model.audio.CrossfeedMode
import com.hifiplayer.domain.model.audio.ReplayGainMode

/** Requirement 10/6: what to do when the device cannot output the file's native format. */
enum class ResamplePolicy(val displayName: String, val description: String) {
    NATIVE_ONLY(
        "Solo formato nativo",
        "Nunca se remuestrea en silencio. Si el dispositivo no admite el formato, se avisa y se indica el formato real de salida.",
    ),
    ALLOW_SYSTEM_RESAMPLE(
        "Permitir remuestreo del sistema",
        "Cuando el formato no es nativo, el sistema adapta la señal. El reproductor lo indica como Converted.",
    ),
}

/** Requirement 6: Bit-Perfect is a single switch that overrides the whole DSP chain. */
data class PlaybackSettings(
    val bitPerfectEnabled: Boolean = true,
    val gaplessEnabled: Boolean = true,
    val resamplePolicy: ResamplePolicy = ResamplePolicy.NATIVE_ONLY,
    val usbAutoRoute: Boolean = true,
    val pauseOnOutputDisconnect: Boolean = true,
    val resumeOnHeadphonesReconnect: Boolean = false,
    val resumePlaybackOnStart: Boolean = true,
    val autoPlayOnOpen: Boolean = false,
    val repeatMode: RepeatModeSetting = RepeatModeSetting.OFF,
    val shuffleEnabled: Boolean = false,
    val skipSilence: Boolean = false,
    val preferredOutputDeviceId: String? = null,
)

enum class RepeatModeSetting(val displayName: String) {
    OFF("Sin repetición"), ONE("Repetir pista"), ALL("Repetir cola")
}

/** Requirement 13. */
data class ReplayGainSettings(
    val mode: ReplayGainMode = ReplayGainMode.OFF,
    val preampDb: Double = 0.0,
    val preventClipping: Boolean = true,
    val fallbackGainDb: Double = 0.0,
    val preferAlbumGainInAlbumQueue: Boolean = true,
    val showSourceInPlayer: Boolean = true,
)

/** Requirement 12. */
enum class EqBandType(val displayName: String) {
    PEAKING("Peaking"),
    LOW_SHELF("Low Shelf"),
    HIGH_SHELF("High Shelf"),
    LOW_PASS("Low Pass"),
    HIGH_PASS("High Pass"),
    NOTCH("Notch"),
    BAND_PASS("Band Pass");
}

data class EqBand(
    val id: String,
    val type: EqBandType = EqBandType.PEAKING,
    val frequencyHz: Double,
    val gainDb: Double = 0.0,
    val q: Double = 0.7,
    val enabled: Boolean = true,
) {
    val isGainBand: Boolean
        get() = type == EqBandType.PEAKING || type == EqBandType.LOW_SHELF || type == EqBandType.HIGH_SHELF

    /** Only gain bands can introduce clipping; pass/notch filters cannot. */
    val canClip: Boolean get() = isGainBand && gainDb > 0.0
}

data class EqSettings(
    val enabled: Boolean = false,
    val presetId: String = EqPreset.FLAT_ID,
    val presetName: String = "Flat",
    val preampDb: Double = 0.0,
    val bands: List<EqBand> = EqPreset.FLAT_BANDS,
    val userPresets: List<EqPreset> = emptyList(),
) {
    val activeBandCount: Int get() = bands.count { it.enabled }

    val hasAnyGain: Boolean get() = bands.any { it.enabled && it.isGainBand && it.gainDb != 0.0 }

    val isEffectivelyFlat: Boolean get() = !hasAnyGain && preampDb == 0.0

    val maxBoostDb: Double get() = bands.filter { it.enabled && it.isGainBand }.maxOfOrNull { it.gainDb } ?: 0.0

    /** Requirement 15: warn the user before the chain can clip. */
    val clippingRisk: Boolean get() = maxBoostDb + preampDb > 0.0

    companion object {
        val DEFAULT: EqSettings = EqSettings()
    }
}

data class EqPreset(
    val id: String,
    val name: String,
    val bands: List<EqBand>,
    val isUserDefined: Boolean = false,
    val updatedAtEpochMs: Long = 0L,
) {
    companion object {
        const val FLAT_ID: String = "flat"
        const val BASS_BOOST_ID: String = "bass_boost"
        const val VOCAL_ID: String = "vocal"
        const val TREBLE_ID: String = "treble"
        const val CUSTOM_ID: String = "custom"

        /** 10 bands (requirement 12: minimum 10). */
        val FLAT_BANDS: List<EqBand> = listOf(
            EqBand("b01", EqBandType.HIGH_PASS, 30.0, 0.0, 0.7),
            EqBand("b02", EqBandType.LOW_SHELF, 60.0, 0.0, 0.7),
            EqBand("b03", EqBandType.PEAKING, 120.0, 0.0, 1.0),
            EqBand("b04", EqBandType.PEAKING, 250.0, 0.0, 1.0),
            EqBand("b05", EqBandType.PEAKING, 500.0, 0.0, 1.0),
            EqBand("b06", EqBandType.PEAKING, 1_000.0, 0.0, 1.0),
            EqBand("b07", EqBandType.PEAKING, 2_000.0, 0.0, 1.0),
            EqBand("b08", EqBandType.PEAKING, 4_000.0, 0.0, 1.0),
            EqBand("b09", EqBandType.PEAKING, 8_000.0, 0.0, 1.0),
            EqBand("b10", EqBandType.HIGH_SHELF, 12_000.0, 0.0, 0.7),
        )

        private fun shaped(vararg changes: Pair<String, Double>): List<EqBand> {
            val map = changes.toMap()
            return FLAT_BANDS.map { band -> map[band.id]?.let { band.copy(gainDb = it) } ?: band }
        }

        /** Requirement 12: Flat, Bass Boost, Vocal, Treble, Custom are shipped as starting points. */
        val BUILT_IN: List<EqPreset> = listOf(
            EqPreset(FLAT_ID, "Flat", FLAT_BANDS),
            EqPreset(BASS_BOOST_ID, "Bass Boost", shaped("b02" to 4.0, "b03" to 3.0, "b04" to 1.5, "b10" to -1.0)),
            EqPreset(VOCAL_ID, "Vocal", shaped("b05" to -1.5, "b06" to 1.5, "b07" to 2.5, "b08" to 1.5, "b02" to -2.0)),
            EqPreset(TREBLE_ID, "Treble", shaped("b09" to 2.5, "b10" to 3.5, "b03" to -1.0)),
            EqPreset(CUSTOM_ID, "Custom", FLAT_BANDS),
        )

        fun byId(id: String): EqPreset = BUILT_IN.firstOrNull { it.id == id } ?: BUILT_IN.first()
    }
}

/** Requirement 14 + 15: crossfeed and the volume/gain chain. */
data class DspSettings(
    val crossfeed: CrossfeedMode = CrossfeedMode.OFF,
    val preampDb: Double = 0.0,
    val balance: Double = 0.0,          // -1 = todo a la izquierda, +1 = todo a la derecha
    val appGainDb: Double = 0.0,        // ganancia de la app, separada del volumen del sistema
    val useSystemVolume: Boolean = true,
    val clippingProtectionEnabled: Boolean = true,
    val truePeakLimiterEnabled: Boolean = false,
) {
    val crossfeedActive: Boolean get() = crossfeed.isEnabled
    val anyGainAboveZero: Boolean get() = appGainDb > 0.0 || preampDb > 0.0
}

/** Requirement 17/28. */
data class LibrarySettings(
    val documentTreeUris: Set<String> = emptySet(),
    val includedFolders: Set<String> = emptySet(),
    val excludedFolders: Set<String> = emptySet(),
    val scanOnStartup: Boolean = true,
    val automaticScanning: Boolean = true,
    val analyzeFormatsOnScan: Boolean = true,
    val readReplayGainOnScan: Boolean = true,
    val readArtworkOnScan: Boolean = true,
    val minimumDurationSec: Int = 5,
    val minimumSizeBytes: Long = 16 * 1024L,
    val useMediaStore: Boolean = true,
) {
    companion object {
        val DEFAULT: LibrarySettings = LibrarySettings()
    }
}

/** Requirement 28 (Apariencia). */
enum class ThemeMode(val displayName: String) {
    PURE_DARK("Negro puro (OLED)"),
    DARK("Oscuro"),
    LIGHT("Claro"),
    SYSTEM("Seguir al sistema");

    companion object {
        val DEFAULT: ThemeMode = DARK
    }
}

enum class ArtworkSize(val displayName: String, val targetPx: Int) {
    COMPACT("Compacta", 128),
    NORMAL("Normal", 192),
    LARGE("Grande", 256);
}

data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
    val artworkSize: ArtworkSize = ArtworkSize.NORMAL,
    val animationsEnabled: Boolean = true,
    val showTechnicalInfoInLists: Boolean = true,
    val useDynamicColor: Boolean = false,
)

/** Whole-app settings snapshot, produced by SettingsRepository. */
data class AppSettings(
    val playback: PlaybackSettings = PlaybackSettings(),
    val replayGain: ReplayGainSettings = ReplayGainSettings(),
    val eq: EqSettings = EqSettings.DEFAULT,
    val dsp: DspSettings = DspSettings(),
    val library: LibrarySettings = LibrarySettings.DEFAULT,
    val appearance: AppearanceSettings = AppearanceSettings(),
) {
    companion object {
        val DEFAULT: AppSettings = AppSettings()
    }
}
