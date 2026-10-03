package com.hifiplayer.domain.model.audio

/**
 * Nominal limits of the formats the app understands.
 *
 * These are *format* limits, not device limits. Whether 24-bit/192 kHz can actually be played
 * is decided by `DeviceCapabilities` and verified at runtime (requirement 10). No UI string may
 * be derived from this object alone.
 */
object AudioLimits {
    val SUPPORTED_SAMPLE_RATES: List<Int> = listOf(
        8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000,
        64_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000,
    )

    val SUPPORTED_BIT_DEPTHS: List<Int> = listOf(16, 24, 32)

    /** Headline product target: 24-bit / 192 kHz *where the device supports it*. */
    const val TARGET_BIT_DEPTH: Int = 24
    const val TARGET_SAMPLE_RATE_HZ: Int = 192_000

    val DSD_RATES_HZ: List<Int> = listOf(2_822_400, 5_644_800, 11_289_600)

    const val MAX_CHANNELS: Int = 8

    fun isSupportedSampleRate(hz: Int): Boolean = hz in SUPPORTED_SAMPLE_RATES
    fun isSupportedBitDepth(bits: Int): Boolean = bits in SUPPORTED_BIT_DEPTHS
}
