package com.hifiplayer.presentation.settings

import com.hifiplayer.domain.model.settings.ThemeMode

/**
 * What the appearance setting means, resolved **once**.
 *
 * This lives outside the activity so it can be tested without a device: the bug that made "Claro" do
 * nothing on a phone set to dark mode was one `||` in the theme, and a test like this is what
 * catches it.
 */
data class ThemeAppearance(
    val darkTheme: Boolean,
    val pureBlack: Boolean,
)

/**
 * Resolves the setting. [systemInDarkTheme] is only consulted for [ThemeMode.SYSTEM]: an explicit
 * choice always wins over the phone's setting, which is what "Claro" and "Oscuro" mean.
 */
fun ThemeMode.toThemeAppearance(systemInDarkTheme: Boolean): ThemeAppearance = when (this) {
    ThemeMode.PURE_DARK -> ThemeAppearance(darkTheme = true, pureBlack = true)
    ThemeMode.DARK -> ThemeAppearance(darkTheme = true, pureBlack = false)
    ThemeMode.LIGHT -> ThemeAppearance(darkTheme = false, pureBlack = false)
    ThemeMode.SYSTEM -> ThemeAppearance(darkTheme = systemInDarkTheme, pureBlack = false)
}
