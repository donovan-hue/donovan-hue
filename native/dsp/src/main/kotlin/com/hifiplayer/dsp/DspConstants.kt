package com.hifiplayer.dsp

/**
 * Constants that belong to the DSP algorithms themselves. User-facing defaults live in
 * `core:common/config/AppConfig` (requirement 43: no magic numbers scattered around).
 */
object DspConstants {
    /** Reference loudness used by ReplayGain 2.0 / EBU R128 tagging. */
    const val REPLAY_GAIN_REFERENCE_LUFS: Double = -18.0

    /** Absolute gate for loudness measurement (BS.1770-4). */
    const val LOUDNESS_ABSOLUTE_GATE_LUFS: Double = -70.0

    /** Relative gate: 10 LU below the ungated loudness. */
    const val LOUDNESS_RELATIVE_GATE_LU: Double = 10.0

    /** Filter used for the K-weighting stage 2 (high shelf) of BS.1770. */
    const val K_WEIGHTING_SHELF_HZ: Double = 1_681.97
    const val K_WEIGHTING_SHELF_GAIN_DB: Double = 3.999_843_853_973_368
    const val K_WEIGHTING_SHELF_Q: Double = 0.707_175_237_562_320_4

    /** Filter used for the K-weighting stage 1 (high pass) of BS.1770. */
    const val K_WEIGHTING_HIGHPASS_HZ: Double = 38.135_470_876_024_44
    const val K_WEIGHTING_HIGHPASS_Q: Double = 0.500_327_037_323_877_3

    /** Blocks of 400 ms with 75 % overlap, as specified for gated loudness. */
    const val LOUDNESS_BLOCK_MS: Double = 400.0
    const val LOUDNESS_BLOCK_OVERLAP: Double = 0.75

    /** Bauer-style crossfeed: the low-pass corner of the mixed signal. */
    const val CROSSFEED_LOWPASS_HZ: Double = 700.0

    /** Ramp used when a gain changes between tracks, to avoid clicks. */
    const val GAIN_RAMP_MS: Double = 40.0

    /** Anything at or above this magnitude is considered a clipped sample. */
    const val CLIP_THRESHOLD: Float = 0.999_9f

    /** Biquad coefficients are recomputed when the sample rate changes by more than this. */
    const val SAMPLE_RATE_EPSILON_HZ: Int = 0
}
