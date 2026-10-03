package com.hifiplayer.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout constants (requirement 34: no magic numbers scattered around). Touch targets follow the
 * accessibility floor of 48 dp because the transport controls are the most used part of the app.
 */
data class HiFiDimens(
    val touchTarget: Dp = 48.dp,
    val transportButton: Dp = 56.dp,
    val playButton: Dp = 72.dp,
    val artworkCorner: Dp = 12.dp,
    val artworkLarge: Dp = 320.dp,
    val artworkMini: Dp = 44.dp,
    val artworkList: Dp = 52.dp,
    val screenPadding: Dp = 16.dp,
    val sectionSpacing: Dp = 24.dp,
    val rowSpacing: Dp = 8.dp,
    val progressThickness: Dp = 4.dp,
    /**
     * Fixed row height of the queue list. It is fixed on purpose: drag-to-reorder computes the
     * target position from the finger offset, so a constant height keeps that math exact.
     */
    val queueRowHeight: Dp = 64.dp,
)

val LocalHiFiDimens = staticCompositionLocalOf { HiFiDimens() }

/** Extra colours that M3's scheme has no slot for, so they are named instead of hardcoded. */
data class HiFiExtraColors(
    val warning: Color,
    val unknown: Color,
    val success: Color,
)

val LocalHiFiExtraColors = staticCompositionLocalOf {
    HiFiExtraColors(
        warning = HiFiPalette.WarningDark,
        unknown = HiFiPalette.UnknownDark,
        success = Color(0xFF6FBF73),
    )
}

/**
 * The single theme entry point.
 *
 * `darkTheme` is passed in from the Appearance settings (which default to dark); the app never
 * follows the system automatically because "Dark Hi-Fi" is the product's own identity
 * (requirement 28).
 */
@Composable
fun HiFiTheme(
    darkTheme: Boolean = true,
    systemInDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val useDark = darkTheme || systemInDarkTheme
    val colors = if (useDark) HiFiDarkColors else HiFiLightColors
    val extras = if (useDark) {
        HiFiExtraColors(
            warning = HiFiPalette.WarningDark,
            unknown = HiFiPalette.UnknownDark,
            success = Color(0xFF6FBF73),
        )
    } else {
        HiFiExtraColors(
            warning = HiFiPalette.WarningLight,
            unknown = HiFiPalette.UnknownLight,
            success = Color(0xFF2E7D32),
        )
    }

    CompositionLocalProvider(
        LocalHiFiDimens provides HiFiDimens(),
        LocalHiFiExtraColors provides extras,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = HiFiTypography,
            content = content,
        )
    }
}
