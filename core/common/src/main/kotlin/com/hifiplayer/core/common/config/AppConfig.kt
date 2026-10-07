package com.hifiplayer.core.common.config

/**
 * Central configuration hub. No magic numbers scattered around the codebase: every tunable
 * default lives here (or in the module that owns the algorithm) and is passed down.
 */
object AppConfig {
    /** Version of the audio engine, surfaced in Settings > Acerca de and in bug reports. */
    const val AUDIO_ENGINE_VERSION: String = "1.0.0"

    /** Internal identifier used for cache directories and database names. */
    const val INTERNAL_NAME: String = "hifi_player"

    const val DATABASE_NAME: String = "hifi_player.db"
    const val DATABASE_VERSION: Int = 2

    /** Search debounce (requirement: never query on every keystroke). */
    const val SEARCH_DEBOUNCE_MS: Long = 300L

    /** Library scan: how many files we analyse in parallel. Keeps IO busy without thrashing. */
    const val SCAN_CONCURRENCY: Int = 4

    /** How many rows we hand to the UI per page. */
    const val LIBRARY_PAGE_SIZE: Int = 100

    /** Queue is persisted at most every N ms while playing, to avoid hammering the disk. */
    const val QUEUE_PERSIST_INTERVAL_MS: Long = 2_000L

    /** Debounce before a save-settings write is flushed to DataStore. */
    const val SETTINGS_DEBOUNCE_MS: Long = 120L

    /** After this many days without playback, transient caches may be trimmed. */
    const val CACHE_PRUNE_AGE_DAYS: Int = 30
}

/** Audio-related constants referenced by the DSP chain, the engine and the UI. */
object AudioConfig {
    const val DEFAULT_PREAMP_DB: Float = 0f
    const val MIN_PREAMP_DB: Float = -24f
    const val MAX_PREAMP_DB: Float = 12f
    const val DEFAULT_CROSSFEED = false

    /** Parametric EQ capabilities. */
    const val MIN_EQ_BANDS: Int = 10
    const val MAX_EQ_BANDS: Int = 10
    const val EQ_MIN_FREQ_HZ: Double = 20.0
    const val EQ_MAX_FREQ_HZ: Double = 20_000.0
    const val EQ_MIN_GAIN_DB: Double = -12.0
    const val EQ_MAX_GAIN_DB: Double = 12.0
    const val EQ_MIN_Q: Double = 0.1
    const val EQ_MAX_Q: Double = 10.0
    const val EQ_DEFAULT_Q: Double = 0.7

    /** Digital gain headroom: never let the chain clip silently. */
    const val GAIN_WARNING_THRESHOLD_DB: Float = 0f
    const val TRUE_PEAK_HEADROOM_DB: Float = -0.1f

    /** ReplayGain defaults (EBU R128 / ReplayGain 2.0 reference loudness). */
    const val REPLAY_GAIN_REFERENCE_LUFS: Double = -18.0
    const val REPLAY_GAIN_FALLBACK_PREAMP_DB: Double = 0.0
    const val REPLAY_GAIN_MAX_BOOST_DB: Double = 12.0
    const val REPLAY_GAIN_MAX_CUT_DB: Double = -24.0

    /** Analyser: how many samples per processing callback, used for peak/clip metering. */
    const val METER_WINDOW_MS: Int = 300

    /** How long a capability probe stays valid before it is re-queried. */
    const val CAPABILITY_CACHE_TTL_MS: Long = 5 * 60 * 1_000L

    /** Format probe: bytes read from the head of the file to classify it without decoding. */
    const val FORMAT_PROBE_BYTES: Int = 64 * 1024
}

/** UI / presentation constants. */
object UiConfig {
    const val ARTWORK_LIST_SIZE_PX: Int = 160
    const val ARTWORK_CARD_SIZE_PX: Int = 320
    const val ARTWORK_NOW_PLAYING_SIZE_PX: Int = 1024
    const val ARTWORK_CACHE_SIZE_BYTES: Long = 64L * 1024 * 1024
    const val SEARCH_MIN_CHARS: Int = 2
    const val POSITION_UPDATE_INTERVAL_MS: Long = 250L
    const val ANIMATIONS_ENABLED_BY_DEFAULT: Boolean = true
}
