package com.hifiplayer.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * "Dark Hi-Fi" palette (requirement 28): dark background, high contrast, no gradients and no
 * decorative colour. Every tone here is a flat, opaque value on purpose — the artwork is the only
 * thing allowed to be colourful in this app.
 */
internal object HiFiPalette {
    // Dark — the default, because a player that sits next to a DAC should not light up the room.
    val BackgroundDark = Color(0xFF0A0A0B)
    val SurfaceDark = Color(0xFF121316)
    val SurfaceContainerDark = Color(0xFF1A1C1F)
    val SurfaceVariantDark = Color(0xFF23262A)
    val OnBackgroundDark = Color(0xFFF2F3F5)
    val OnSurfaceVariantDark = Color(0xFFB4B9C0)
    val OutlineDark = Color(0xFF3A3E44)

    // Light — offered in Appearance, same restrained palette.
    val BackgroundLight = Color(0xFFF7F7F8)
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceContainerLight = Color(0xFFEFEFF1)
    val SurfaceVariantLight = Color(0xFFE3E4E7)
    val OnBackgroundLight = Color(0xFF16181A)
    val OnSurfaceVariantLight = Color(0xFF4A4F55)
    val OutlineLight = Color(0xFFC6C8CC)

    /** Warm gold: the single accent. Used for the play control, active states and the quality badge. */
    val AccentDark = Color(0xFFD9B36C)
    val AccentOnDark = Color(0xFF1A1408)
    val AccentLight = Color(0xFF8A6A1F)
    val AccentOnLight = Color(0xFFFFFFFF)

    /** Secondary is deliberately desaturated: it marks "informational", never "press me". */
    val SecondaryDark = Color(0xFF8FB6B8)
    val SecondaryOnDark = Color(0xFF0C1717)
    val SecondaryLight = Color(0xFF3F6668)
    val SecondaryOnLight = Color(0xFFFFFFFF)

    val ErrorDark = Color(0xFFE5484D)
    val ErrorLight = Color(0xFFB3261E)
    val WarningDark = Color(0xFFE0A458)
    val WarningLight = Color(0xFF8A5A12)

    /** Muted colour for "no detectado": never paint an unknown value as if it were data. */
    val UnknownDark = Color(0xFF7A8089)
    val UnknownLight = Color(0xFF6B7078)
}

internal val HiFiDarkColors = darkColorScheme(
    primary = HiFiPalette.AccentDark,
    onPrimary = HiFiPalette.AccentOnDark,
    primaryContainer = Color(0xFF3A2F14),
    onPrimaryContainer = Color(0xFFF6E2B6),
    secondary = HiFiPalette.SecondaryDark,
    onSecondary = HiFiPalette.SecondaryOnDark,
    secondaryContainer = Color(0xFF1E3233),
    onSecondaryContainer = Color(0xFFCDE7E8),
    tertiary = Color(0xFFB9A9F0),
    onTertiary = Color(0xFF1B1533),
    background = HiFiPalette.BackgroundDark,
    onBackground = HiFiPalette.OnBackgroundDark,
    surface = HiFiPalette.SurfaceDark,
    onSurface = HiFiPalette.OnBackgroundDark,
    surfaceVariant = HiFiPalette.SurfaceVariantDark,
    onSurfaceVariant = HiFiPalette.OnSurfaceVariantDark,
    surfaceContainerLowest = Color(0xFF070708),
    surfaceContainerLow = HiFiPalette.SurfaceDark,
    surfaceContainer = HiFiPalette.SurfaceContainerDark,
    surfaceContainerHigh = Color(0xFF1F2226),
    surfaceContainerHighest = Color(0xFF262A2E),
    outline = HiFiPalette.OutlineDark,
    outlineVariant = Color(0xFF2A2E33),
    error = HiFiPalette.ErrorDark,
    onError = Color(0xFF1C0001),
    scrim = Color(0xCC000000),
)

/**
 * Pure black variant ("Negro puro (OLED)", requirement 28).
 *
 * Same scheme as [HiFiDarkColors] with the two surfaces the user actually looks at set to real
 * black, so an OLED panel can switch those pixels off. It exists because the Appearance screen
 * offers that option: an option that changes nothing is a lie (requirement 46).
 */
internal val HiFiPureBlackColors = HiFiDarkColors.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0B),
    surfaceContainer = Color(0xFF111214),
    surfaceVariant = Color(0xFF1B1E21),
)

internal val HiFiLightColors = lightColorScheme(
    primary = HiFiPalette.AccentLight,
    onPrimary = HiFiPalette.AccentOnLight,
    primaryContainer = Color(0xFFF2E3C0),
    onPrimaryContainer = Color(0xFF2C2208),
    secondary = HiFiPalette.SecondaryLight,
    onSecondary = HiFiPalette.SecondaryOnLight,
    secondaryContainer = Color(0xFFCFE7E8),
    onSecondaryContainer = Color(0xFF10282A),
    tertiary = Color(0xFF5A4BA0),
    onTertiary = Color(0xFFFFFFFF),
    background = HiFiPalette.BackgroundLight,
    onBackground = HiFiPalette.OnBackgroundLight,
    surface = HiFiPalette.SurfaceLight,
    onSurface = HiFiPalette.OnBackgroundLight,
    surfaceVariant = HiFiPalette.SurfaceVariantLight,
    onSurfaceVariant = HiFiPalette.OnSurfaceVariantLight,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F4F6),
    surfaceContainer = HiFiPalette.SurfaceContainerLight,
    surfaceContainerHigh = Color(0xFFE8E9EC),
    surfaceContainerHighest = Color(0xFFE0E1E5),
    outline = HiFiPalette.OutlineLight,
    outlineVariant = Color(0xFFD8DADE),
    error = HiFiPalette.ErrorLight,
    onError = Color(0xFFFFFFFF),
    scrim = Color(0x99000000),
)
