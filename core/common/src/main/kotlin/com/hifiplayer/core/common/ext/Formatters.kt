package com.hifiplayer.core.common.ext

import java.util.Locale
import kotlin.math.abs

/** "3:45" / "1:02:11" – used by the player, lists and the notification. */
fun formatDuration(positionMs: Long): String {
    if (positionMs <= 0L) return "0:00"
    val totalSeconds = positionMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** Human readable size, e.g. "1,4 GB". */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return String.format(Locale.getDefault(), if (value >= 100) "%.0f %s" else "%.1f %s", value, units[unitIndex])
}

/** "+3.2 dB" / "-1.5 dB" / "0.0 dB". */
fun formatGainDb(db: Double): String = String.format(Locale.US, "%+.1f dB", db)

fun formatGainDb(db: Float): String = formatGainDb(db.toDouble())

/** Frequency label that stays readable from 20 Hz to 20 kHz. */
fun formatFrequency(hz: Double): String = when {
    hz >= 1_000.0 -> {
        val khz = hz / 1_000.0
        if (abs(khz - khz.toInt()) < 0.05) "${khz.toInt()} kHz" else String.format(Locale.US, "%.1f kHz", khz)
    }
    else -> "${hz.toInt()} Hz"
}

fun formatQ(q: Double): String = String.format(Locale.US, "Q %.2f", q)

fun formatPercent(fraction: Float): String = String.format(Locale.US, "%d%%", (fraction * 100f).toInt())

fun formatLufs(value: Double): String = String.format(Locale.US, "%.1f LUFS", value)
