package com.hifiplayer.presentation.settings

import com.google.common.truth.Truth.assertThat
import com.hifiplayer.domain.model.settings.ThemeMode
import org.junit.Test

/**
 * The appearance setting, fixed by test.
 *
 * The reported bug: on a phone set to dark mode, choosing "Claro" left the app dark, because the
 * theme OR-ed the user's choice with the system. An explicit choice must win, always.
 */
class ThemeAppearanceTest {

    @Test
    fun `claro stays light even when the phone is in dark mode`() {
        val appearance = ThemeMode.LIGHT.toThemeAppearance(systemInDarkTheme = true)

        assertThat(appearance.darkTheme).isFalse()
        assertThat(appearance.pureBlack).isFalse()
    }

    @Test
    fun `oscuro stays dark even when the phone is in light mode`() {
        val appearance = ThemeMode.DARK.toThemeAppearance(systemInDarkTheme = false)

        assertThat(appearance.darkTheme).isTrue()
        assertThat(appearance.pureBlack).isFalse()
    }

    @Test
    fun `negro puro is dark, and different from oscuro`() {
        val pure = ThemeMode.PURE_DARK.toThemeAppearance(systemInDarkTheme = false)
        val dark = ThemeMode.DARK.toThemeAppearance(systemInDarkTheme = false)

        assertThat(pure.darkTheme).isTrue()
        // The whole point: it is not the same as "Oscuro". An option that changes nothing is a lie.
        assertThat(pure.pureBlack).isTrue()
        assertThat(dark.pureBlack).isFalse()
    }

    @Test
    fun `seguir al sistema is the only mode that follows the phone`() {
        assertThat(ThemeMode.SYSTEM.toThemeAppearance(systemInDarkTheme = true).darkTheme).isTrue()
        assertThat(ThemeMode.SYSTEM.toThemeAppearance(systemInDarkTheme = false).darkTheme).isFalse()
    }
}
