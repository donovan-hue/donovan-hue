package com.hifiplayer.dsp.filter

import com.hifiplayer.dsp.DspConstants

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Normalised biquad coefficients (a0 == 1) in Direct Form I.
 *
 * Every filter in the parametric EQ is one of these, which keeps the implementation small,
 * stable at audio rates and identical across platforms.
 */
data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    val isBypass: Boolean
        get() = abs(b0 - 1.0) < 1e-12 && abs(b1) < 1e-12 && abs(b2) < 1e-12 && abs(a1) < 1e-12 && abs(a2) < 1e-12

    /**
     * Magnitude response in dB at [frequencyHz] for a given sample rate. Used to draw the EQ
     * curve in the UI directly from the coefficients that are actually running – the graph can
     * never disagree with the sound.
     */
    fun magnitudeDb(frequencyHz: Double, sampleRateHz: Int): Double {
        if (frequencyHz <= 0.0 || sampleRateHz <= 0) return 0.0
        val w = 2.0 * Math.PI * frequencyHz / sampleRateHz
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2 * w)
        val sin2W = sin(2 * w)

        val numeratorReal = b0 + b1 * cosW + b2 * cos2W
        val numeratorImag = -(b1 * sinW + b2 * sin2W)
        val denominatorReal = 1.0 + a1 * cosW + a2 * cos2W
        val denominatorImag = -(a1 * sinW + a2 * sin2W)

        val numerator = sqrt(numeratorReal * numeratorReal + numeratorImag * numeratorImag)
        val denominator = sqrt(denominatorReal * denominatorReal + denominatorImag * denominatorImag)
        if (denominator == 0.0) return 0.0
        return 20.0 * kotlin.math.log10(numerator / denominator)
    }

    companion object {
        val BYPASS: BiquadCoefficients = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Stateful biquad section. One instance per channel: filter state must never be shared between
 * channels or the stereo image smears.
 */
class Biquad(coefficients: BiquadCoefficients = BiquadCoefficients.BYPASS) {

    var coefficients: BiquadCoefficients = coefficients
        private set

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    val isBypassed: Boolean get() = coefficients.isBypass

    fun update(coefficients: BiquadCoefficients) {
        if (this.coefficients == coefficients) return
        this.coefficients = coefficients
    }

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }

    /** Direct Form I difference equation. */
    fun process(input: Double): Double {
        val c = coefficients
        val output = c.b0 * input + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
        x2 = x1; x1 = input
        y2 = y1; y1 = output
        return output
    }

    inline fun process(input: Float): Float = process(input.toDouble()).toFloat()
}

/** Design helpers (Audio EQ Cookbook, Robert Bristow-Johnson). */
object BiquadDesigner {

    private const val Q_SHELF = 0.707_106_781_186_547_5 // 1/sqrt(2), Butterworth shelf slope S = 1

    fun peaking(frequencyHz: Double, gainDb: Double, q: Double, sampleRateHz: Int): BiquadCoefficients {
        if (gainDb == 0.0) return BiquadCoefficients.BYPASS
        val a = 10.0.pow(gainDb / 40.0)
        val (w0, cosW, sinW) = omega(frequencyHz, sampleRateHz)
        val alpha = sinW / (2.0 * q)
        val a0 = 1.0 + alpha / a
        return BiquadCoefficients(
            b0 = (1.0 + alpha * a) / a0,
            b1 = (-2.0 * cosW) / a0,
            b2 = (1.0 - alpha * a) / a0,
            a1 = (-2.0 * cosW) / a0,
            a2 = (1.0 - alpha / a) / a0,
        )
    }

    fun lowShelf(frequencyHz: Double, gainDb: Double, q: Double, sampleRateHz: Int): BiquadCoefficients {
        if (gainDb == 0.0) return BiquadCoefficients.BYPASS
        val a = 10.0.pow(gainDb / 40.0)
        val (_, cosW, _) = omega(frequencyHz, sampleRateHz)
        val sinW = kotlin.math.sin(omega(frequencyHz, sampleRateHz).first)
        val alpha = sinW / 2.0 * sqrt((a + 1.0 / a) * (1.0 / q - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha
        return BiquadCoefficients(
            b0 = (a * ((a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha)) / a0,
            b1 = (2.0 * a * ((a - 1.0) - (a + 1.0) * cosW)) / a0,
            b2 = (a * ((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha)) / a0,
            a1 = (-2.0 * ((a - 1.0) + (a + 1.0) * cosW)) / a0,
            a2 = ((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha) / a0,
        )
    }

    fun highShelf(frequencyHz: Double, gainDb: Double, q: Double, sampleRateHz: Int): BiquadCoefficients {
        if (gainDb == 0.0) return BiquadCoefficients.BYPASS
        val a = 10.0.pow(gainDb / 40.0)
        val (w0, cosW, sinW) = omega(frequencyHz, sampleRateHz)
        val alpha = sinW / 2.0 * sqrt((a + 1.0 / a) * (1.0 / q - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha
        return BiquadCoefficients(
            b0 = (a * ((a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha)) / a0,
            b1 = (-2.0 * a * ((a - 1.0) + (a + 1.0) * cosW)) / a0,
            b2 = (a * ((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha)) / a0,
            a1 = (2.0 * ((a - 1.0) - (a + 1.0) * cosW)) / a0,
            a2 = ((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha) / a0,
        ).also { check(w0 > 0) }
    }

    fun lowPass(frequencyHz: Double, q: Double, sampleRateHz: Int): BiquadCoefficients {
        val (_, cosW, sinW) = omega(frequencyHz, sampleRateHz)
        val alpha = sinW / (2.0 * q)
        val a0 = 1.0 + alpha
        return BiquadCoefficients(
            b0 = ((1.0 - cosW) / 2.0) / a0,
            b1 = (1.0 - cosW) / a0,
            b2 = ((1.0 - cosW) / 2.0) / a0,
            a1 = (-2.0 * cosW) / a0,
            a2 = (1.0 - alpha) / a0,
        )
    }

    fun highPass(frequencyHz: Double, q: Double, sampleRateHz: Int): BiquadCoefficients {
        val (_, cosW, sinW) = omega(frequencyHz, sampleRateHz)
        val alpha = sinW / (2.0 * q)
        val a0 = 1.0 + alpha
        return BiquadCoefficients(
            b0 = ((1.0 + cosW) / 2.0) / a0,
            b1 = (-(1.0 + cosW)) / a0,
            b2 = ((1.0 + cosW) / 2.0) / a0,
            a1 = (-2.0 * cosW) / a0,
            a2 = (1.0 - alpha) / a0,
        )
    }

    fun notch(frequencyHz: Double, q: Double, sampleRateHz: Int): BiquadCoefficients {
        val (_, cosW, sinW) = omega(frequencyHz, sampleRateHz)
        val alpha = sinW / (2.0 * q)
        val a0 = 1.0 + alpha
        return BiquadCoefficients(
            b0 = 1.0 / a0,
            b1 = (-2.0 * cosW) / a0,
            b2 = 1.0 / a0,
            a1 = (-2.0 * cosW) / a0,
            a2 = (1.0 - alpha) / a0,
        )
    }

    fun bandPass(frequencyHz: Double, q: Double, sampleRateHz: Int): BiquadCoefficients {
        val (_, cosW, sinW) = omega(frequencyHz, sampleRateHz)
        val alpha = sinW / (2.0 * q)
        val a0 = 1.0 + alpha
        return BiquadCoefficients(
            b0 = alpha / a0,
            b1 = 0.0,
            b2 = (-alpha) / a0,
            a1 = (-2.0 * cosW) / a0,
            a2 = (1.0 - alpha) / a0,
        )
    }

    /** High-shelf used by the K-weighting stage of the loudness analyser. */
    fun highShelfBs1770(gainDb: Double, q: Double, sampleRateHz: Int): BiquadCoefficients =
        highShelf(DspConstantsRef.shelfHz, gainDb, q, sampleRateHz)

    private object DspConstantsRef {
        const val shelfHz: Double = 1_681.97
    }

    private fun omega(frequencyHz: Double, sampleRateHz: Int): Triple<Double, Double, Double> {
        val nyquist = sampleRateHz / 2.0
        val clamped = frequencyHz.coerceIn(1.0, nyquist * 0.999)
        val w0 = 2.0 * Math.PI * clamped / sampleRateHz
        return Triple(w0, cos(w0), sin(w0))
    }
}
