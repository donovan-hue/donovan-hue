package com.hifiplayer.domain.model.audio

/**
 * Requirement 14. Crossfeed mixes a controlled amount of each channel into the other to make
 * headphone listening less fatiguing – it is an *intentional* modification of the signal, and
 * the UI says so. It is always bypassed while bit-perfect is active.
 */
enum class CrossfeedMode(
    val displayName: String,
    val shortLabel: String,
    /** Amount of signal sent to the opposite channel (0..1). 0 means "off". */
    val mixAmount: Double,
    /** Inter-aural delay in samples at 44.1 kHz, scaled to the real sample rate at runtime. */
    val delaySamplesAt44k: Int,
    /** Optional bass compensation in dB applied when crossfeed is engaged. */
    val bassCompensationDb: Double,
) {
    OFF("Desactivado", "OFF", 0.0, 0, 0.0),
    LOW("Suave", "LOW", 0.15, 11, 0.5),
    MEDIUM("Medio", "MEDIUM", 0.30, 14, 1.0),
    HIGH("Fuerte", "HIGH", 0.45, 18, 1.5);

    val isEnabled: Boolean get() = this != OFF

    /** Shown next to the control so users are never surprised by the processing. */
    val explanation: String
        get() = if (isEnabled) {
            "El crossfeed modifica intencionalmente la señal: mezcla una parte de cada canal en el " +
                "otro para acercar la escucha con auriculares a la de unos altavoces. No es bit-perfect."
        } else {
            "Sin crossfeed: los canales izquierdo y derecho llegan intactos."
        }
}
