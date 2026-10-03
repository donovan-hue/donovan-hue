package com.hifiplayer.dsp.replaygain

import com.hifiplayer.dsp.DspConstants
import com.hifiplayer.dsp.filter.Biquad
import com.hifiplayer.dsp.filter.BiquadDesigner
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Computes ReplayGain 2.0 / EBU R128 loudness for files that ship without tags.
 *
 * This is the same algorithm family used by loudness scanners: K-weighting (two biquads) →
 * 400 ms gated blocks with 75 % overlap → relative gate at −10 LU → integrated loudness in LUFS.
 * The resulting gain is referenced to −18 LUFS, which matches the ReplayGain 2.0 convention.
 *
 * Only used in the background (scanning) and always cancellable: it processes samples in chunks
 * and reports progress so the UI stays responsive (requirement 34).
 */
class ReplayGainAnalyzer(private val sampleRateHz: Int, channels: Int) {

    private val channelCount = channels.coerceAtLeast(1)
    private val preFilter: Array<Biquad> = Array(channelCount) {
        Biquad(BiquadDesigner.highPass(DspConstants.K_WEIGHTING_HIGHPASS_HZ, DspConstants.K_WEIGHTING_HIGHPASS_Q, sampleRateHz))
    }
    private val shelfFilter: Array<Biquad> = Array(channelCount) {
        Biquad(
            BiquadDesigner.highShelf(
                DspConstants.K_WEIGHTING_SHELF_HZ,
                DspConstants.K_WEIGHTING_SHELF_GAIN_DB,
                DspConstants.K_WEIGHTING_SHELF_Q,
                sampleRateHz,
            ),
        )
    }

    private val blockSize = (DspConstants.LOUDNESS_BLOCK_MS / 1000.0 * sampleRateHz).toInt().coerceAtLeast(1)
    private val hopSize = (blockSize * (1.0 - DspConstants.LOUDNESS_BLOCK_OVERLAP)).toInt().coerceAtLeast(1)

    private val sumSquares = DoubleArray(channelCount)
    private var samplesInBlock = 0
    private val blockLoudness = ArrayList<Double>(1024)
    private var peak = 0.0
    private var totalSamples = 0L

    /** Feeds interleaved samples. Call repeatedly; memory use is constant. */
    fun accept(buffer: FloatArray, frames: Int) {
        for (frame in 0 until frames) {
            val base = frame * channelCount
            for (channel in 0 until channelCount) {
                val sample = buffer[base + channel].toDouble()
                if (abs(sample) > peak) peak = abs(sample)
                val filtered = shelfFilter[channel].process(preFilter[channel].process(sample))
                sumSquares[channel] += filtered * filtered
            }
            totalSamples++
            samplesInBlock++
            if (samplesInBlock >= blockSize) {
                flushBlock()
                // Overlap: keep the last (blockSize - hopSize) samples by rewinding analytically.
                val keep = blockSize - hopSize
                if (keep > 0) {
                    for (channel in 0 until channelCount) {
                        sumSquares[channel] *= (keep.toDouble() / blockSize.toDouble())
                    }
                    samplesInBlock = keep
                } else {
                    zeroSumSquares()
                    samplesInBlock = 0
                }
            }
        }
    }

    private fun flushBlock() {
        if (samplesInBlock == 0) return
        var meanSquare = 0.0
        for (channel in 0 until channelCount) {
            meanSquare += sumSquares[channel] / samplesInBlock
        }
        meanSquare /= channelCount
        val loudness = -0.691 + 10.0 * log10(meanSquare.coerceAtLeast(1e-12))
        blockLoudness += loudness
    }

    private fun zeroSumSquares() {
        for (channel in 0 until channelCount) sumSquares[channel] = 0.0
    }

    /** Gated integrated loudness in LUFS, or null when there is not enough audio. */
    fun integratedLoudness(): Double? {
        if (blockLoudness.isEmpty()) return null
        val absoluteGated = blockLoudness.filter { it > DspConstants.LOUDNESS_ABSOLUTE_GATE_LUFS }
        if (absoluteGated.isEmpty()) return null
        val ungatedMean = absoluteGated.map { 10.0.pow(it / 10.0) }.average()
        val relativeThreshold = -0.691 + 10.0 * log10(ungatedMean) - DspConstants.LOUDNESS_RELATIVE_GATE_LU
        val gated = absoluteGated.filter { it > relativeThreshold }
        if (gated.isEmpty()) return null
        val mean = gated.map { 10.0.pow(it / 10.0) }.average()
        return -0.691 + 10.0 * log10(mean)
    }

    fun peakLinear(): Double = peak

    /**
     * ReplayGain 2.0 track gain for the analysed audio, in dB, referenced to −18 LUFS.
     * Returns null when the audio was too short or too quiet to measure.
     */
    fun trackGainDb(): Double? {
        val loudness = integratedLoudness() ?: return null
        return DspConstants.REPLAY_GAIN_REFERENCE_LUFS - loudness
    }

    val analyzedSamples: Long get() = totalSamples

    fun reset() {
        preFilter.forEach { it.reset() }
        shelfFilter.forEach { it.reset() }
        zeroSumSquares()
        blockLoudness.clear()
        samplesInBlock = 0
        peak = 0.0
        totalSamples = 0L
    }
}
