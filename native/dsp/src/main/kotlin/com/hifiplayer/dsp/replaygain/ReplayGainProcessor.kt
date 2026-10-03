package com.hifiplayer.dsp.replaygain

import com.hifiplayer.domain.model.audio.ReplayGainInfo
import com.hifiplayer.domain.model.audio.ReplayGainMode
import kotlin.math.pow

/**
 * Requirement 13: ReplayGain applied at playback time only – the audio file is never modified.
 *
 * Responsibilities:
 *  - choose track vs album gain (and fall back sensibly when one of the two is missing),
 *  - add the user preamp,
 *  - clamp to sane limits,
 *  - prevent clipping by inspecting the stored peaks before applying a boost.
 *
 * The result is a single, explainable number of dB plus the reason it was reduced, which the
 * AudioInfoCard shows verbatim.
 */
class ReplayGainProcessor {

    data class Decision(
        val appliedDb: Double,
        val requestedDb: Double,
        val mode: ReplayGainMode,
        val source: String,
        val clippingReductionDb: Double,
        val usedFallback: Boolean,
        val explanation: String,
    ) {
        val wasReduced: Boolean get() = clippingReductionDb > 0.0
    }

    fun decide(
        info: ReplayGainInfo,
        mode: ReplayGainMode,
        preampDb: Double,
        preventClipping: Boolean,
        fallbackGainDb: Double,
        maxBoostDb: Double = MAX_BOOST_DB,
        maxCutDb: Double = MAX_CUT_DB,
        headroomDb: Double = HEADROOM_DB,
    ): Decision {
        if (mode == ReplayGainMode.OFF) {
            return Decision(
                appliedDb = 0.0,
                requestedDb = 0.0,
                mode = mode,
                source = "ReplayGain desactivado",
                clippingReductionDb = 0.0,
                usedFallback = false,
                explanation = "Sin normalización: la señal llega tal como está en el archivo.",
            )
        }

        val tagValue = info.gainFor(mode)
        val usedFallback = tagValue == null
        val requested = (tagValue ?: fallbackGainDb) + preampDb
        val clamped = requested.coerceIn(maxCutDb, maxBoostDb)

        val peak = info.peakFor(mode)
        var reduction = 0.0
        if (preventClipping && peak != null && peak > 0.0) {
            // Maximum gain that keeps the measured peak below full scale minus headroom.
            val peakDb = 20.0 * kotlin.math.log10(peak)
            val allowedDb = -peakDb + headroomDb
            if (clamped > allowedDb) {
                reduction = clamped - allowedDb
            }
        }

        val applied = clamped - reduction
        val modeLabel = if (mode == ReplayGainMode.TRACK) "Gain de pista" else "Gain de álbum"
        val explanation = buildString {
            append(modeLabel)
            append(": ").append(String.format(java.util.Locale.US, "%+.2f dB", clamped))
            if (preampDb != 0.0) append(" (preamp ").append(String.format(java.util.Locale.US, "%+.2f dB", preampDb)).append(")")
            when {
                usedFallback -> append(" · sin etiqueta en el archivo, se usa el valor por defecto")
                peak == null -> append(" · sin dato de pico, no se pudo verificar clipping")
                reduction > 0.0 -> append(" · reducido ").append(String.format(java.util.Locale.US, "%.2f dB", reduction))
                    .append(" para evitar clipping (pico ").append(String.format(java.util.Locale.US, "%.2f", peak)).append(")")
            }
            append(" · fuente: ").append(info.source.displayName)
        }

        return Decision(
            appliedDb = applied,
            requestedDb = clamped,
            mode = mode,
            source = info.source.displayName,
            clippingReductionDb = reduction,
            usedFallback = usedFallback,
            explanation = explanation,
        )
    }

    /** Converts dB to a linear multiplier. */
    fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    companion object {
        const val MAX_BOOST_DB: Double = 12.0
        const val MAX_CUT_DB: Double = -24.0
        /** Safety margin below full scale (requirement 15). */
        const val HEADROOM_DB: Double = -0.1

        /** Linear equivalent of [HEADROOM_DB]: the maximum magnitude the chain may output. */
        val HEADROOM_LINEAR: Double = 10.0.pow(HEADROOM_DB / 20.0)
    }
}
