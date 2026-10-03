package com.hifiplayer.data.local.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.hifiplayer.domain.model.audio.CrossfeedMode
import com.hifiplayer.domain.model.audio.ReplayGainMode
import com.hifiplayer.domain.model.settings.AppSettings
import com.hifiplayer.domain.model.settings.AppearanceSettings
import com.hifiplayer.domain.model.settings.ArtworkSize
import com.hifiplayer.domain.model.settings.DspSettings
import com.hifiplayer.domain.model.settings.EqBand
import com.hifiplayer.domain.model.settings.EqBandType
import com.hifiplayer.domain.model.settings.EqPreset
import com.hifiplayer.domain.model.settings.EqSettings
import com.hifiplayer.domain.model.settings.LibrarySettings
import com.hifiplayer.domain.model.settings.PlaybackSettings
import com.hifiplayer.domain.model.settings.ReplayGainSettings
import com.hifiplayer.domain.model.settings.RepeatModeSetting
import com.hifiplayer.domain.model.settings.ResamplePolicy
import com.hifiplayer.domain.model.settings.ThemeMode

/**
 * Text codec for [AppSettings] on top of DataStore preferences.
 *
 * One key per field (so a future change to a single setting does not invalidate the others) and a
 * tolerant parser: an unknown enum name or a corrupted band string falls back to the default value
 * instead of crashing the app on startup.
 */
internal object SettingsCodec {

    // ---- playback ----
    private val bitPerfect = booleanPreferencesKey("playback_bit_perfect")
    private val gapless = booleanPreferencesKey("playback_gapless")
    private val resamplePolicy = stringPreferencesKey("playback_resample_policy")
    private val usbAutoRoute = booleanPreferencesKey("playback_usb_auto_route")
    private val pauseOnDisconnect = booleanPreferencesKey("playback_pause_on_disconnect")
    private val resumeOnHeadphones = booleanPreferencesKey("playback_resume_on_headphones")
    private val resumeOnStart = booleanPreferencesKey("playback_resume_on_start")
    private val autoPlayOnOpen = booleanPreferencesKey("playback_autoplay_on_open")
    private val repeatMode = stringPreferencesKey("playback_repeat_mode")
    private val shuffle = booleanPreferencesKey("playback_shuffle")
    private val skipSilence = booleanPreferencesKey("playback_skip_silence")
    private val preferredOutput = stringPreferencesKey("playback_preferred_output")

    // ---- replay gain ----
    private val rgMode = stringPreferencesKey("replaygain_mode")
    private val rgPreamp = doublePreferencesKey("replaygain_preamp_db")
    private val rgPreventClipping = booleanPreferencesKey("replaygain_prevent_clipping")
    private val rgFallback = doublePreferencesKey("replaygain_fallback_db")
    private val rgPreferAlbum = booleanPreferencesKey("replaygain_prefer_album")
    private val rgShowSource = booleanPreferencesKey("replaygain_show_source")

    // ---- eq ----
    private val eqEnabled = booleanPreferencesKey("eq_enabled")
    private val eqPresetId = stringPreferencesKey("eq_preset_id")
    private val eqPresetName = stringPreferencesKey("eq_preset_name")
    private val eqPreamp = doublePreferencesKey("eq_preamp_db")
    private val eqBands = stringPreferencesKey("eq_bands")
    private val eqUserPresets = stringPreferencesKey("eq_user_presets")

    // ---- dsp ----
    private val dspCrossfeed = stringPreferencesKey("dsp_crossfeed")
    private val dspPreamp = doublePreferencesKey("dsp_preamp_db")
    private val dspBalance = doublePreferencesKey("dsp_balance")
    private val dspAppGain = doublePreferencesKey("dsp_app_gain_db")
    private val dspUseSystemVolume = booleanPreferencesKey("dsp_use_system_volume")
    private val dspClippingProtection = booleanPreferencesKey("dsp_clipping_protection")
    private val dspTruePeakLimiter = booleanPreferencesKey("dsp_true_peak_limiter")

    // ---- library ----
    private val libTrees = stringSetPreferencesKey("library_tree_uris")
    private val libIncluded = stringSetPreferencesKey("library_included_folders")
    private val libExcluded = stringSetPreferencesKey("library_excluded_folders")
    private val libScanOnStartup = booleanPreferencesKey("library_scan_on_startup")
    private val libAutomaticScan = booleanPreferencesKey("library_automatic_scan")
    private val libAnalyzeOnScan = booleanPreferencesKey("library_analyze_on_scan")
    private val libReadReplayGain = booleanPreferencesKey("library_read_replaygain_on_scan")
    private val libReadArtwork = booleanPreferencesKey("library_read_artwork_on_scan")
    private val libMinDuration = intPreferencesKey("library_min_duration_sec")
    private val libMinSize = longPreferencesKey("library_min_size_bytes")
    private val libUseMediaStore = booleanPreferencesKey("library_use_mediastore")

    // ---- appearance ----
    private val appearanceTheme = stringPreferencesKey("appearance_theme")
    private val appearanceArtworkSize = stringPreferencesKey("appearance_artwork_size")
    private val appearanceAnimations = booleanPreferencesKey("appearance_animations")
    private val appearanceShowTech = booleanPreferencesKey("appearance_show_technical")
    private val appearanceDynamicColor = booleanPreferencesKey("appearance_dynamic_color")

    // ------------------------------------------------------------------ decode

    fun decode(preferences: Preferences): AppSettings {
        val defaults = AppSettings.DEFAULT
        return AppSettings(
            playback = PlaybackSettings(
                bitPerfectEnabled = preferences[bitPerfect] ?: defaults.playback.bitPerfectEnabled,
                gaplessEnabled = preferences[gapless] ?: defaults.playback.gaplessEnabled,
                resamplePolicy = preferences[resamplePolicy].toEnum(defaults.playback.resamplePolicy),
                usbAutoRoute = preferences[usbAutoRoute] ?: defaults.playback.usbAutoRoute,
                pauseOnOutputDisconnect = preferences[pauseOnDisconnect] ?: defaults.playback.pauseOnOutputDisconnect,
                resumeOnHeadphonesReconnect = preferences[resumeOnHeadphones] ?: defaults.playback.resumeOnHeadphonesReconnect,
                resumePlaybackOnStart = preferences[resumeOnStart] ?: defaults.playback.resumePlaybackOnStart,
                autoPlayOnOpen = preferences[autoPlayOnOpen] ?: defaults.playback.autoPlayOnOpen,
                repeatMode = preferences[repeatMode].toEnum(defaults.playback.repeatMode),
                shuffleEnabled = preferences[shuffle] ?: defaults.playback.shuffleEnabled,
                skipSilence = preferences[skipSilence] ?: defaults.playback.skipSilence,
                preferredOutputDeviceId = preferences[preferredOutput]?.takeIf { it.isNotBlank() },
            ),
            replayGain = ReplayGainSettings(
                mode = preferences[rgMode].toEnum(defaults.replayGain.mode),
                preampDb = preferences[rgPreamp] ?: defaults.replayGain.preampDb,
                preventClipping = preferences[rgPreventClipping] ?: defaults.replayGain.preventClipping,
                fallbackGainDb = preferences[rgFallback] ?: defaults.replayGain.fallbackGainDb,
                preferAlbumGainInAlbumQueue = preferences[rgPreferAlbum] ?: defaults.replayGain.preferAlbumGainInAlbumQueue,
                showSourceInPlayer = preferences[rgShowSource] ?: defaults.replayGain.showSourceInPlayer,
            ),
            eq = EqSettings(
                enabled = preferences[eqEnabled] ?: defaults.eq.enabled,
                presetId = preferences[eqPresetId] ?: defaults.eq.presetId,
                presetName = preferences[eqPresetName] ?: defaults.eq.presetName,
                preampDb = preferences[eqPreamp] ?: defaults.eq.preampDb,
                bands = decodeBands(preferences[eqBands]),
                userPresets = decodePresets(preferences[eqUserPresets]),
            ),
            dsp = DspSettings(
                crossfeed = preferences[dspCrossfeed].toEnum(defaults.dsp.crossfeed),
                preampDb = preferences[dspPreamp] ?: defaults.dsp.preampDb,
                balance = preferences[dspBalance] ?: defaults.dsp.balance,
                appGainDb = preferences[dspAppGain] ?: defaults.dsp.appGainDb,
                useSystemVolume = preferences[dspUseSystemVolume] ?: defaults.dsp.useSystemVolume,
                clippingProtectionEnabled = preferences[dspClippingProtection] ?: defaults.dsp.clippingProtectionEnabled,
                truePeakLimiterEnabled = preferences[dspTruePeakLimiter] ?: defaults.dsp.truePeakLimiterEnabled,
            ),
            library = LibrarySettings(
                documentTreeUris = preferences[libTrees] ?: defaults.library.documentTreeUris,
                includedFolders = preferences[libIncluded] ?: defaults.library.includedFolders,
                excludedFolders = preferences[libExcluded] ?: defaults.library.excludedFolders,
                scanOnStartup = preferences[libScanOnStartup] ?: defaults.library.scanOnStartup,
                automaticScanning = preferences[libAutomaticScan] ?: defaults.library.automaticScanning,
                analyzeFormatsOnScan = preferences[libAnalyzeOnScan] ?: defaults.library.analyzeFormatsOnScan,
                readReplayGainOnScan = preferences[libReadReplayGain] ?: defaults.library.readReplayGainOnScan,
                readArtworkOnScan = preferences[libReadArtwork] ?: defaults.library.readArtworkOnScan,
                minimumDurationSec = preferences[libMinDuration] ?: defaults.library.minimumDurationSec,
                minimumSizeBytes = preferences[libMinSize] ?: defaults.library.minimumSizeBytes,
                useMediaStore = preferences[libUseMediaStore] ?: defaults.library.useMediaStore,
            ),
            appearance = AppearanceSettings(
                themeMode = preferences[appearanceTheme].toEnum(defaults.appearance.themeMode),
                artworkSize = preferences[appearanceArtworkSize].toEnum(defaults.appearance.artworkSize),
                animationsEnabled = preferences[appearanceAnimations] ?: defaults.appearance.animationsEnabled,
                showTechnicalInfoInLists = preferences[appearanceShowTech] ?: defaults.appearance.showTechnicalInfoInLists,
                useDynamicColor = preferences[appearanceDynamicColor] ?: defaults.appearance.useDynamicColor,
            ),
        )
    }

    // ------------------------------------------------------------------ encode

    fun write(preferences: MutablePreferences, settings: AppSettings) {
        preferences[bitPerfect] = settings.playback.bitPerfectEnabled
        preferences[gapless] = settings.playback.gaplessEnabled
        preferences[resamplePolicy] = settings.playback.resamplePolicy.name
        preferences[usbAutoRoute] = settings.playback.usbAutoRoute
        preferences[pauseOnDisconnect] = settings.playback.pauseOnOutputDisconnect
        preferences[resumeOnHeadphones] = settings.playback.resumeOnHeadphonesReconnect
        preferences[resumeOnStart] = settings.playback.resumePlaybackOnStart
        preferences[autoPlayOnOpen] = settings.playback.autoPlayOnOpen
        preferences[repeatMode] = settings.playback.repeatMode.name
        preferences[shuffle] = settings.playback.shuffleEnabled
        preferences[skipSilence] = settings.playback.skipSilence
        preferences[preferredOutput] = settings.playback.preferredOutputDeviceId ?: ""

        preferences[rgMode] = settings.replayGain.mode.name
        preferences[rgPreamp] = settings.replayGain.preampDb
        preferences[rgPreventClipping] = settings.replayGain.preventClipping
        preferences[rgFallback] = settings.replayGain.fallbackGainDb
        preferences[rgPreferAlbum] = settings.replayGain.preferAlbumGainInAlbumQueue
        preferences[rgShowSource] = settings.replayGain.showSourceInPlayer

        preferences[eqEnabled] = settings.eq.enabled
        preferences[eqPresetId] = settings.eq.presetId
        preferences[eqPresetName] = settings.eq.presetName
        preferences[eqPreamp] = settings.eq.preampDb
        preferences[eqBands] = encodeBands(settings.eq.bands)
        preferences[eqUserPresets] = encodePresets(settings.eq.userPresets)

        preferences[dspCrossfeed] = settings.dsp.crossfeed.name
        preferences[dspPreamp] = settings.dsp.preampDb
        preferences[dspBalance] = settings.dsp.balance
        preferences[dspAppGain] = settings.dsp.appGainDb
        preferences[dspUseSystemVolume] = settings.dsp.useSystemVolume
        preferences[dspClippingProtection] = settings.dsp.clippingProtectionEnabled
        preferences[dspTruePeakLimiter] = settings.dsp.truePeakLimiterEnabled

        preferences[libTrees] = settings.library.documentTreeUris
        preferences[libIncluded] = settings.library.includedFolders
        preferences[libExcluded] = settings.library.excludedFolders
        preferences[libScanOnStartup] = settings.library.scanOnStartup
        preferences[libAutomaticScan] = settings.library.automaticScanning
        preferences[libAnalyzeOnScan] = settings.library.analyzeFormatsOnScan
        preferences[libReadReplayGain] = settings.library.readReplayGainOnScan
        preferences[libReadArtwork] = settings.library.readArtworkOnScan
        preferences[libMinDuration] = settings.library.minimumDurationSec
        preferences[libMinSize] = settings.library.minimumSizeBytes
        preferences[libUseMediaStore] = settings.library.useMediaStore

        preferences[appearanceTheme] = settings.appearance.themeMode.name
        preferences[appearanceArtworkSize] = settings.appearance.artworkSize.name
        preferences[appearanceAnimations] = settings.appearance.animationsEnabled
        preferences[appearanceShowTech] = settings.appearance.showTechnicalInfoInLists
        preferences[appearanceDynamicColor] = settings.appearance.useDynamicColor
    }

    // ------------------------------------------------------------------ bands & presets

    fun encodeBands(bands: List<EqBand>): String = bands.joinToString(";") { band ->
        listOf(
            sanitize(band.id),
            band.type.name,
            band.frequencyHz.toString(),
            band.gainDb.toString(),
            band.q.toString(),
            if (band.enabled) "1" else "0",
        ).joinToString("|")
    }

    fun decodeBands(raw: String?): List<EqBand> {
        if (raw.isNullOrBlank()) return EqPreset.FLAT_BANDS
        val parsed = raw.split(';').mapNotNull { entry ->
            val parts = entry.split('|')
            if (parts.size != 6) return@mapNotNull null
            val type = parts[1].toEnum(EqBandType.PEAKING)
            val frequency = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            EqBand(
                id = parts[0],
                type = type,
                frequencyHz = frequency,
                gainDb = parts[3].toDoubleOrNull() ?: 0.0,
                q = parts[4].toDoubleOrNull() ?: 0.7,
                enabled = parts[5] == "1",
            )
        }
        // A band list that lost entries is worse than the default one: it would silently change
        // the sound. Either the full set is recovered or the flat preset is used.
        return if (parsed.size == EqPreset.FLAT_BANDS.size) parsed else EqPreset.FLAT_BANDS
    }

    private fun encodePresets(presets: List<EqPreset>): String = presets.joinToString(";;") { preset ->
        listOf(
            sanitize(preset.id),
            sanitize(preset.name),
            preset.updatedAtEpochMs.toString(),
            encodeBands(preset.bands),
        ).joinToString("|")
    }

    private fun decodePresets(raw: String?): List<EqPreset> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(";;").mapNotNull { entry ->
            val parts = entry.split('|')
            if (parts.size != 4) return@mapNotNull null
            EqPreset(
                id = parts[0],
                name = parts[1],
                bands = decodeBands(parts[3]),
                isUserDefined = true,
                updatedAtEpochMs = parts[2].toLongOrNull() ?: 0L,
            )
        }
    }

    /** Separators are replaced so a name containing them cannot corrupt the encoding. */
    private fun sanitize(value: String): String = value.replace('|', '/').replace(';', ',')

    private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T {
        if (this.isNullOrBlank()) return fallback
        return try {
            enumValueOf<T>(this)
        } catch (exception: IllegalArgumentException) {
            fallback
        }
    }
}
