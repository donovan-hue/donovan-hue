package com.hifiplayer.dsp.eq

import com.hifiplayer.dsp.filter.Biquad
import com.hifiplayer.dsp.filter.BiquadCoefficients
import com.hifiplayer.dsp.filter.BiquadDesigner
import com.hifiplayer.domain.model.settings.EqBand
import com.hifiplayer.domain.model.settings.EqBandType
import com.hifiplayer.domain.model.settings.EqSettings
import kotlin.math.pow

/**
 * Requirement 12: parametric EQ with at least 10 bands, each with frequency, gain, Q, filter
 * type and an enable flag.
 *
 * Implementation notes:
 *  - One [Biquad] per band per channel (state is never shared between channels).
 *  - Coefficients are recomputed only when the band, the enabled set or the sample rate change.
 *  - [magnitudeDb] gives the exact response of the running filters so the UI curve is truthful.
 */
class ParametricEq {

    private var sampleRateHz: Int = 48_000
    private var channels: Int = 2
    private var bands: List<EqBand> = emptyList()
    private var sections: Array<Array<Biquad>> = emptyArray()
    private var preampLinear: Double = 1.0

    var enabled: Boolean = false
        private set

    val activeBandCount: Int get() = if (enabled) bands.count { it.enabled } else 0

    fun configure(settings: EqSettings, sampleRateHz: Int, channels: Int) {
        val rateChanged = this.sampleRateHz != sampleRateHz
        val channelChanged = this.channels != channels
        val bandsChanged = this.bands != settings.bands
        this.enabled = settings.enabled
        this.sampleRateHz = sampleRateHz
        this.channels = channels
        this.bands = settings.bands
        this.preampLinear = 10.0.pow(settings.preampDb / 20.0)

        if (bandsChanged || rateChanged || channelChanged) {
            rebuild()
        }
    }

    private fun rebuild() {
        val activeBands = bands.filter { it.enabled }
        sections = Array(activeBands.size) { bandIndex ->
            val coefficients = coefficientsFor(activeBands[bandIndex])
            Array(channels) { Biquad(coefficients) }
        }
    }

    private fun coefficientsFor(band: EqBand): BiquadCoefficients = when (band.type) {
        EqBandType.PEAKING -> BiquadDesigner.peaking(band.frequencyHz, band.gainDb, band.q, sampleRateHz)
        EqBandType.LOW_SHELF -> BiquadDesigner.lowShelf(band.frequencyHz, band.gainDb, band.q, sampleRateHz)
        EqBandType.HIGH_SHELF -> BiquadDesigner.highShelf(band.frequencyHz, band.gainDb, band.q, sampleRateHz)
        EqBandType.LOW_PASS -> BiquadDesigner.lowPass(band.frequencyHz, band.q, sampleRateHz)
        EqBandType.HIGH_PASS -> BiquadDesigner.highPass(band.frequencyHz, band.q, sampleRateHz)
        EqBandType.NOTCH -> BiquadDesigner.notch(band.frequencyHz, band.q, sampleRateHz)
        EqBandType.BAND_PASS -> BiquadDesigner.bandPass(band.frequencyHz, band.q, sampleRateHz)
    }

    /**
     * Processes interleaved float frames in place. [buffer] holds `frames * channels` samples.
     * Returns false when the EQ is bypassed so callers can skip bookkeeping.
     */
    fun process(buffer: FloatArray, frames: Int, channels: Int): Boolean {
        if (!enabled || sections.isEmpty()) return false
        val preamp = preampLinear
        for (frame in 0 until frames) {
            val base = frame * channels
            for (channel in 0 until channels) {
                var sample = buffer[base + channel].toDouble() * preamp
                for (section in sections) {
                    sample = section[channel].process(sample)
                }
                buffer[base + channel] = sample.toFloat()
            }
        }
        return true
    }

    /** Exact magnitude response of the configured bands at [frequenciesHz]. */
    fun magnitudeDb(frequenciesHz: List<Double>): List<Double> {
        val activeBands = if (enabled) bands.filter { it.enabled } else emptyList()
        return frequenciesHz.map { frequency ->
            var sum = 0.0
            activeBands.forEach { band ->
                sum += coefficientsFor(band).magnitudeDb(frequency, sampleRateHz)
            }
            if (enabled) sum += 20.0 * kotlin.math.log10(preampLinear)
            sum
        }
    }

    fun reset() {
        sections.forEach { channelSections -> channelSections.forEach { it.reset() } }
    }
}
