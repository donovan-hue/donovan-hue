package com.hifiplayer.domain.model.device

import com.hifiplayer.domain.model.audio.AudioFormatSpec

/**
 * The single source of truth for the "BIT-PERFECT / DSP ACTIVE / no disponible" banner.
 *
 * [isActive] may only be true when the platform confirmed the non-mixing path for the exact
 * format being played. Everything else is reported honestly, including *why*.
 */
data class BitPerfectState(
    val requested: Boolean = false,
    val isActive: Boolean = false,
    val support: BitPerfectSupport = BitPerfectSupport.UNKNOWN,
    val routeId: String? = null,
    val routeName: String? = null,
    val sourceFormat: AudioFormatSpec? = null,
    val mixerFormat: AudioFormatSpec? = null,
    val deliveredFormat: AudioFormatSpec? = null,
    val dspActive: Boolean = false,
    val dspChainDescription: String = "",
    val blockers: List<BitPerfectBlocker> = emptyList(),
    val evidence: List<String> = emptyList(),
) {
    /** Text for the Now Playing banner. Never claims more than what was verified. */
    val bannerTitle: String
        get() = when {
            isActive -> "BIT-PERFECT"
            dspActive && requested -> "DSP ACTIVE"
            !requested -> "DSP DESACTIVADO (bit-perfect apagado)"
            else -> "BIT-PERFECT NO DISPONIBLE"
        }

    val bannerDetail: String
        get() = when {
            isActive -> deliveredFormat?.let { "${it.label} · ${it.codec.displayName} · ${routeName ?: "salida activa"}" }
                ?: "salida verificada"
            dspActive && requested -> dspChainDescription
            else -> blockers.firstOrNull()?.explanation ?: "La ruta actual no puede verificarse como bit-perfect."
        }
}

/** A concrete reason why the bit-perfect path is unavailable right now. */
enum class BitPerfectBlocker(val explanation: String) {
    ANDROID_TOO_OLD("Este dispositivo usa Android 13 o inferior: el sistema siempre mezcla y remuestrea la salida."),
    NO_WIRED_OR_USB_OUTPUT("Solo las salidas USB o cableadas pueden entregar la señal sin remuestreo; hay una salida inalámbrica activa."),
    MIXER_ATTRIBUTES_REJECTED("El sistema rechazó la solicitud de mezclador sin mezcla para este formato."),
    FORMAT_NOT_OFFERED_BY_DAC("El DAC no anuncia este sample rate/profundidad; se mantiene el formato del sistema."),
    DSP_CHAIN_ACTIVE("Hay procesamiento activo (EQ, ReplayGain, crossfeed o ganancia). La señal se modifica intencionalmente."),
    LOSSY_SOURCE("El archivo es comprimido con pérdida: no hay señal original que preservar, solo se evita reprocesar."),
    SYSTEM_VOLUME_ACTIVE("El volumen del sistema aplica atenuación digital sobre la señal."),
    EXCLUSIVE_MODE_UNAVAILABLE("La app no puede tomar control exclusivo del DAC en este dispositivo."),
}
