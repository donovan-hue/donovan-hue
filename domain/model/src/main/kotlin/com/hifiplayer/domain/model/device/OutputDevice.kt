package com.hifiplayer.domain.model.device

import com.hifiplayer.domain.model.audio.AudioFormatSpec

/**
 * A selectable audio output. Filled from AudioManager/AudioDeviceInfo plus USB descriptor probing
 * when the route is a USB audio device.
 */
public data class OutputDevice(
    public val id: String,
    public val name: String,
    public val type: OutputRouteType,
    public val isActive: Boolean,
    public val isSelectedByUser: Boolean = false,
    public val productName: String? = null,
    public val manufacturer: String? = null,
    public val vendorId: Int? = null,
    public val productId: Int? = null,
    public val capabilities: DeviceCapabilities? = null,
    public val requiresPermission: Boolean = false,
    public val hasPermission: Boolean = true,
) {
    public val isUsb: Boolean get() = type.isUsb
    public val displaySubtitle: String
        get() = buildString {
            append(type.displayName)
            productName?.takeIf { it.isNotBlank() && !name.contains(it, ignoreCase = true) }?.let { append(" · ").append(it) }
        }
}

/** A verified answer to "will this exact format come out of this exact route untouched?". */
public data class ResolvedOutput(
    public val requested: AudioFormatSpec,
    public val delivered: AudioFormatSpec,
    public val bitPerfectAchieved: Boolean,
    public val resampled: Boolean,
    public val downmixed: Boolean,
    public val reason: OutputReason,
    public val message: String,
) {
    public val exactMatch: Boolean get() = requested.matchesExactly(delivered)
}

public enum class OutputReason(public val displayName: String) {
    NATIVE_MATCH("Ruta nativa exacta"),
    BIT_PERFECT_MIXER_ACTIVE("Mezclador bit-perfect solicitado"),
    SYSTEM_RESAMPLING("El sistema remuestrea"),
    SYSTEM_RESAMPLING_BLOCKED("Rechazado: el sistema remuestrearía"),
    BIT_DEPTH_INCREASED("Profundidad ampliada sin pérdida (padding)"),
    BIT_DEPTH_TRUNCATED("Profundidad reducida (pérdida de bits)"),
    CHANNEL_DOWNMIX("Mezcla de canales"),
    DECODED_FROM_LOSSY("Fuente con pérdida (decodificada)"),
    NOT_VERIFIED("Sin verificar"),
    UNSUPPORTED_BY_ROUTE("La salida no admite este formato"),
}
