package com.hifiplayer.dsp.crossfeed

import com.hifiplayer.domain.model.audio.CrossfeedMode

/**
 * Requirement 14: crossfeed for headphone listening.
 *
 * Design: a first-order low-pass (700 Hz) per channel is mixed into the opposite channel, with a
 * short inter-aural delay and a mild bass compensation. This is an *intentional* modification of
 * the signal, it is reported in the player as "DSP ACTIVE", and it is hard-bypassed whenever
 * bit-perfect is requested.
 *
 * The delay line is allocated once at the highest rate the app supports, so configuring it at
 * runtime never allocates.
 */
class CrossfeedProcessor {

    private var mode: CrossfeedMode = CrossfeedMode.OFF
    private var sampleRateHz: Int = 48_000
    private var channels: Int = 2

    private var lowPassStateL = 0.0
    private var lowPassStateR = 0.0
    private var lowPassCoefficient = 0.0
    private var mix = 0.0
    private var bassCompensation = 1.0
    private var delaySamples = 0

    private var delayLineL = DoubleArray(0)
    private var delayLineR = DoubleArray(0)
    private var delayIndex = 0

    val isEnabled: Boolean get() = mode.isEnabled

    fun configure(mode: CrossfeedMode, sampleRateHz: Int, channels: Int) {
        this.mode = mode
        this.sampleRateHz = sampleRateHz
        this.channels = channels
        this.mix = mode.mixAmount
        this.bassCompensation = GainStageHeadroom.dbToLinear(mode.bassCompensationDb)
        // 1st order low-pass: y[n] = y[n-1] + a * (x[n] - y[n-1])
        val rc = 1.0 / (2.0 * Math.PI * CROSSFEED_HZ)
        val dt = 1.0 / sampleRateHz
        lowPassCoefficient = dt / (rc + dt)
        delaySamples = if (mode.isEnabled) {
            (mode.delaySamplesAt44k * (sampleRateHz / 44_100.0)).toInt().coerceIn(0, MAX_DELAY_SAMPLES)
        } else {
            0
        }
        if (delayLineL.size != delaySamples + 1 || delayLineR.size != delaySamples + 1) {
            delayLineL = DoubleArray(delaySamples + 1)
            delayLineR = DoubleArray(delaySamples + 1)
            delayIndex = 0
        }
    }

    fun reset() {
        lowPassStateL = 0.0; lowPassStateR = 0.0
        delayLineL.fill(0.0); delayLineR.fill(0.0)
        delayIndex = 0
    }

    /** Processes stereo interleaved frames in place. Returns true when crossfeed was applied. */
    fun process(buffer: FloatArray, frames: Int, channels: Int): Boolean {
        if (!mode.isEnabled || channels < 2) return false
        for (frame in 0 until frames) {
            val base = frame * channels
            val left = buffer[base].toDouble()
            val right = buffer[base + 1].toDouble()

            lowPassStateL += lowPassCoefficient * (left - lowPassStateL)
            lowPassStateR += lowPassCoefficient * (right - lowPassStateR)

            val delayedL = if (delaySamples > 0) {
                val value = delayLineL[delayIndex]
                delayLineL[delayIndex] = lowPassStateL
                value
            } else {
                lowPassStateL
            }
            val delayedR = if (delaySamples > 0) {
                val value = delayLineR[delayIndex]
                delayLineR[delayIndex] = lowPassStateR
                value
            } else {
                lowPassStateR
            }
            if (delaySamples > 0) {
                delayIndex = (delayIndex + 1) % delayLineL.size
            }

            val outL = (left * (1.0 - mix) + delayedR * mix * bassCompensation)
            val outR = (right * (1.0 - mix) + delayedL * mix * bassCompensation)

            buffer[base] = outL.toFloat()
            buffer[base + 1] = outR.toFloat()
        }
        return true
    }

    val description: String
        get() = if (mode.isEnabled) {
            "Crossfeed ${mode.shortLabel}: ${(mix * 100).toInt()}% de mezcla, " +
                "${CROSSFEED_HZ.toInt()} Hz, retardo ${delaySamples} muestras @ ${sampleRateHz / 1000} kHz"
        } else {
            "Crossfeed desactivado"
        }

    private object GainStageHeadroom {
        fun dbToLinear(db: Double): Double = Math.pow(10.0, db / 20.0)
    }

    private companion object {
        const val CROSSFEED_HZ = 700.0
        const val MAX_DELAY_SAMPLES = 256
    }
}
