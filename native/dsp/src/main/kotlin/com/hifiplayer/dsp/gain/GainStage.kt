package com.hifiplayer.dsp.gain

import com.hifiplayer.dsp.DspConstants
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Requirement 15: one place that applies gain, balance and clipping protection, so the app can
 * never "double amplify" (app gain + preamp + ReplayGain stacking silently).
 *
 * The stage keeps its own metering so the UI can show a truthful peak/clip readout.
 */
class GainStage {

    private var sampleRateHz: Int = 48_000
    private var targetLinear: Double = 1.0
    private var currentLinear: Double = 1.0
    private var rampStep: Double = 0.0
    private var balanceLeft: Double = 1.0
    private var balanceRight: Double = 1.0

    var clippingProtectionEnabled: Boolean = true
    var lastAppliedGainDb: Double = 0.0
        private set
    var clippingPrevented: Boolean = false
        private set

    /** Peak magnitude seen since the last [resetMeters] (0..1+). */
    var peakMagnitude: Float = 0f
        private set
    var clippedSampleCount: Long = 0L
        private set

    fun configure(
        gainDb: Double,
        balance: Double,
        sampleRateHz: Int,
        clippingProtection: Boolean,
        /** Highest linear gain the chain may apply, whatever the user asked for. */
        gainLinearHeadroom: Double = MAX_LINEAR_GAIN,
    ) {
        this.sampleRateHz = sampleRateHz
        this.clippingProtectionEnabled = clippingProtection
        val requested = 10.0.pow(gainDb / 20.0)
        // Hard ceiling: the configured headroom caps the linear gain so the chain cannot clip
        // by accident when the user raises the preamp.
        val limited = minOf(requested, gainLinearHeadroom.coerceAtLeast(0.0))
        this.targetLinear = limited
        this.lastAppliedGainDb = 20.0 * kotlin.math.log10(limited)
        val rampSamples = (DspConstants.GAIN_RAMP_MS / 1000.0 * sampleRateHz).toInt().coerceAtLeast(1)
        rampStep = (limited - currentLinear) / rampSamples
        if (abs(limited - currentLinear) < 1e-9) {
            currentLinear = limited
            rampStep = 0.0
        }
        val clampedBalance = balance.coerceIn(-1.0, 1.0)
        balanceLeft = if (clampedBalance > 0) 1.0 - clampedBalance else 1.0
        balanceRight = if (clampedBalance < 0) 1.0 + clampedBalance else 1.0
    }

    /** Snaps the ramp to its target (used when the track changes while paused). */
    fun snapToTarget() {
        currentLinear = targetLinear
        rampStep = 0.0
    }

    fun resetMeters() {
        peakMagnitude = 0f
        clippedSampleCount = 0L
    }

    /**
     * Applies gain + balance in place. Returns true when any sample was clipped (and therefore
     * hard-limited), so the caller can surface a warning instead of silently distorting.
     */
    fun process(buffer: FloatArray, frames: Int, channels: Int): Boolean {
        var clipped = false
        for (frame in 0 until frames) {
            if (rampStep != 0.0) {
                currentLinear += rampStep
                if ((rampStep > 0 && currentLinear >= targetLinear) || (rampStep < 0 && currentLinear <= targetLinear)) {
                    currentLinear = targetLinear
                    rampStep = 0.0
                }
            }
            val base = frame * channels
            for (channel in 0 until channels) {
                val balance = when {
                    channels == 1 -> 1.0
                    channel == 0 -> balanceLeft
                    channel == 1 -> balanceRight
                    else -> 1.0
                }
                val value = buffer[base + channel] * currentLinear * balance
                val overFullScale = abs(value) > DspConstants.CLIP_THRESHOLD.toDouble()
                val output = when {
                    !overFullScale -> value
                    clippingProtectionEnabled -> {
                        clipped = true
                        value.coerceIn(-1.0, 1.0)
                    }
                    else -> value
                }
                if (overFullScale) clippedSampleCount++
                // Meters describe what actually leaves the stage, never the pre-limiter value.
                val magnitude = abs(output)
                if (magnitude > peakMagnitude.toDouble()) peakMagnitude = magnitude.toFloat()
                buffer[base + channel] = output.toFloat()
            }
        }
        if (clipped) clippingPrevented = true
        return clipped
    }

    val isRamping: Boolean get() = rampStep != 0.0

    companion object {
        /** +12 dB, the same ceiling as AudioConfig.MAX_PREAMP_DB. */
        const val MAX_LINEAR_GAIN: Double = 3.981_071_705_363_958_3

        fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)
        fun linearToDb(linear: Double): Double = if (linear <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * kotlin.math.log10(linear)
        fun peakToDb(peak: Float): Double = if (peak <= 0f) -120.0 else max(-120.0, 20.0 * kotlin.math.log10(peak.toDouble()))
    }
}
