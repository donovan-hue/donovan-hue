package com.hifiplayer.dsp.pipeline

import com.hifiplayer.dsp.crossfeed.CrossfeedProcessor
import com.hifiplayer.dsp.eq.ParametricEq
import com.hifiplayer.dsp.gain.GainStage
import com.hifiplayer.dsp.replaygain.ReplayGainProcessor
import com.hifiplayer.domain.model.audio.CrossfeedMode
import com.hifiplayer.domain.model.audio.ReplayGainInfo
import com.hifiplayer.domain.model.audio.ReplayGainMode
import com.hifiplayer.domain.model.settings.AppSettings
import com.hifiplayer.domain.model.settings.EqSettings

/**
 * Requirement 11: the processing chain is an explicit object.
 *
 * ```
 * Source → Decode → ReplayGain → EQ → Crossfeed → Gain → Output     (DSP active)
 * Source → Decode → Output                                           (bit-perfect)
 * ```
 *
 * Every stage can be individually disabled, and [configure] honours the bit-perfect switch by
 * bypassing the whole chain: if bit-perfect is requested, nothing here touches the samples and
 * [snapshot] reports exactly that.
 */
class AudioProcessingPipeline {

    private val eq = ParametricEq()
    private val crossfeed = CrossfeedProcessor()
    private val gainStage = GainStage()
    private val replayGainProcessor = ReplayGainProcessor()

    private var sampleRateHz: Int = 48_000
    private var channels: Int = 2
    private var bitPerfectRequested: Boolean = true
    private var eqSettings: EqSettings = EqSettings.DEFAULT
    private var crossfeedMode: CrossfeedMode = CrossfeedMode.OFF
    private var replayGain = ReplayGainInfo.EMPTY
    private var replayGainMode: ReplayGainMode = ReplayGainMode.OFF
    private var replayGainDecision = ReplayGainProcessor.Decision(
        appliedDb = 0.0, requestedDb = 0.0, mode = ReplayGainMode.OFF, source = "n/d",
        clippingReductionDb = 0.0, usedFallback = false, explanation = "",
    )
    private var preampDb: Double = 0.0
    private var appGainDb: Double = 0.0
    private var balance: Double = 0.0
    private var clippingProtection: Boolean = true
    private var gainHeadroomLinear: Double = 1.0

    /** Last processed snapshot, so the UI reflects the real chain rather than the settings. */
    var snapshot: PipelineSnapshot = PipelineSnapshot.bypassed(sampleRateHz, channels)
        private set

    fun configure(
        settings: AppSettings,
        replayGainInfo: ReplayGainInfo,
        sampleRateHz: Int,
        channels: Int,
        bitPerfectRequested: Boolean,
        expectedTrackPeak: Double? = null,
    ) {
        this.sampleRateHz = sampleRateHz
        this.channels = channels
        this.bitPerfectRequested = bitPerfectRequested
        this.eqSettings = settings.eq
        this.crossfeedMode = settings.dsp.crossfeed
        this.replayGain = replayGainInfo
        this.replayGainMode = settings.replayGain.mode
        this.preampDb = settings.dsp.preampDb + settings.eq.preampDb
        this.appGainDb = settings.dsp.appGainDb
        this.balance = settings.dsp.balance
        this.clippingProtection = settings.dsp.clippingProtectionEnabled

        if (bitPerfectRequested) {
            // Bit-perfect: bypass everything, no exceptions (requirement 6).
            eq.configure(eqSettings.copy(enabled = false), sampleRateHz, channels)
            crossfeed.configure(CrossfeedMode.OFF, sampleRateHz, channels)
            gainStage.configure(0.0, 0.0, sampleRateHz, clippingProtection = false)
            gainStage.snapToTarget()
            snapshot = PipelineSnapshot.bypassed(sampleRateHz, channels)
            return
        }

        eq.configure(eqSettings, sampleRateHz, channels)
        crossfeed.configure(crossfeedMode, sampleRateHz, channels)

        replayGainDecision = replayGainProcessor.decide(
            info = replayGainInfo,
            mode = replayGainMode,
            preampDb = settings.replayGain.preampDb,
            preventClipping = settings.replayGain.preventClipping,
            fallbackGainDb = settings.replayGain.fallbackGainDb,
        )

        // Headroom guard: the EP chain can never exceed 0 dBFS including the digital gain.
        val totalGain = replayGainDecision.appliedDb + preampDb + appGainDb
        val peak = expectedTrackPeak ?: replayGain.peakFor(replayGainMode)
        // With measured peak data we compute exactly how much gain fits below full scale.
        // Without it we allow the user's gain (up to +12 dB) and rely on sample-level limiting,
        // reporting honestly that limiting happened.
        val headroomLinear = if (peak != null && peak > 0.0) {
            minOf(GainStage.MAX_LINEAR_GAIN, ReplayGainProcessor.HEADROOM_LINEAR / peak)
        } else {
            GainStage.MAX_LINEAR_GAIN
        }
        gainHeadroomLinear = headroomLinear
        gainStage.configure(
            gainDb = totalGain,
            balance = balance,
            sampleRateHz = sampleRateHz,
            clippingProtection = clippingProtection,
            gainLinearHeadroom = headroomLinear,
        )

        snapshot = PipelineSnapshot(
            sampleRateHz = sampleRateHz,
            channels = channels,
            bitPerfect = false,
            eqEnabled = eqSettings.enabled,
            eqActiveBands = eq.activeBandCount,
            eqPreampDb = eqSettings.preampDb,
            crossfeedEnabled = crossfeed.isEnabled,
            crossfeedLabel = crossfeed.description,
            replayGainMode = replayGainMode,
            replayGainAppliedDb = replayGainDecision.appliedDb,
            replayGainExplanation = replayGainDecision.explanation,
            replayGainClippingReductionDb = replayGainDecision.clippingReductionDb,
            preampDb = preampDb,
            appGainDb = appGainDb,
            balance = balance,
            requestedGainDb = totalGain,
            effectiveGainDb = gainStage.lastAppliedGainDb,
            clippingProtection = clippingProtection,
        )
    }

    /**
     * Processes interleaved float frames in place.
     * Returns the stage results so callers can log or meter without re-deriving anything.
     */
    fun process(buffer: FloatArray, frames: Int, channels: Int): ProcessResult {
        if (bitPerfectRequested) return ProcessResult(processed = false, clipped = false, stagesApplied = emptyList())

        val stages = ArrayList<String>(4)
        if (replayGainDecision.appliedDb != 0.0) stages += "ReplayGain"
        if (eq.process(buffer, frames, channels)) stages += "EQ"
        if (crossfeed.process(buffer, frames, channels)) stages += "Crossfeed"
        val clipped = gainStage.process(buffer, frames, channels)
        if (snapshot.requestedGainDb != 0.0 || snapshot.balance != 0.0) stages += "Gain"
        return ProcessResult(processed = stages.isNotEmpty() || clipped, clipped = clipped, stagesApplied = stages)
    }

    fun meters(): MeterSnapshot = MeterSnapshot(
        peak = gainStage.peakMagnitude,
        peakDb = GainStage.peakToDb(gainStage.peakMagnitude),
        clippedSamples = gainStage.clippedSampleCount,
        clippingPrevented = gainStage.clippingPrevented,
        ramping = gainStage.isRamping,
    )

    fun resetMeters() = gainStage.resetMeters()

    /** Response of the running EQ, for the curve shown in the settings screen. */
    fun eqResponseDb(frequenciesHz: List<Double>): List<Double> = eq.magnitudeDb(frequenciesHz)

    fun reset() {
        eq.reset()
        crossfeed.reset()
        gainStage.snapToTarget()
    }

    /** True when any stage would modify the signal – drives the "DSP ACTIVE" banner. */
    val anyStageActive: Boolean
        get() = !bitPerfectRequested && (
            snapshot.eqEnabled && snapshot.eqActiveBands > 0 ||
                snapshot.crossfeedEnabled ||
                snapshot.replayGainMode != ReplayGainMode.OFF ||
                snapshot.requestedGainDb != 0.0 ||
                snapshot.balance != 0.0
            )

    data class ProcessResult(
        val processed: Boolean,
        val clipped: Boolean,
        val stagesApplied: List<String>,
    )

    data class MeterSnapshot(
        val peak: Float,
        val peakDb: Double,
        val clippedSamples: Long,
        val clippingPrevented: Boolean,
        val ramping: Boolean,
    )

    data class PipelineSnapshot(
        val sampleRateHz: Int,
        val channels: Int,
        val bitPerfect: Boolean,
        val eqEnabled: Boolean,
        val eqActiveBands: Int,
        val eqPreampDb: Double,
        val crossfeedEnabled: Boolean,
        val crossfeedLabel: String,
        val replayGainMode: ReplayGainMode,
        val replayGainAppliedDb: Double,
        val replayGainExplanation: String,
        val replayGainClippingReductionDb: Double,
        val preampDb: Double,
        val appGainDb: Double,
        val balance: Double,
        val requestedGainDb: Double,
        val effectiveGainDb: Double,
        val clippingProtection: Boolean,
    ) {
        /** Human-readable chain description for the bit-perfect / DSP banner. */
        val chainDescription: String
            get() = if (bitPerfect) {
                "Source → Decode → Output"
            } else {
                buildList {
                    add("Source")
                    add("Decode")
                    if (replayGainMode != ReplayGainMode.OFF) add("ReplayGain")
                    if (eqEnabled) add("EQ(${eqActiveBands}b)")
                    if (crossfeedEnabled) add("Crossfeed")
                    if (requestedGainDb != 0.0) add("Gain")
                    add("Output")
                }.joinToString(" → ")
            }

        companion object {
            fun bypassed(sampleRateHz: Int, channels: Int): PipelineSnapshot = PipelineSnapshot(
                sampleRateHz = sampleRateHz,
                channels = channels,
                bitPerfect = true,
                eqEnabled = false,
                eqActiveBands = 0,
                eqPreampDb = 0.0,
                crossfeedEnabled = false,
                crossfeedLabel = "Crossfeed desactivado",
                replayGainMode = ReplayGainMode.OFF,
                replayGainAppliedDb = 0.0,
                replayGainExplanation = "Bit-perfect activo: la señal pasa sin procesamiento.",
                replayGainClippingReductionDb = 0.0,
                preampDb = 0.0,
                appGainDb = 0.0,
                balance = 0.0,
                requestedGainDb = 0.0,
                effectiveGainDb = 0.0,
                clippingProtection = false,
            )
        }
    }
}
