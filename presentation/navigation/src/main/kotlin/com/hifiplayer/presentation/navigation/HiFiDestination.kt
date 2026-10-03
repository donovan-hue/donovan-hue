package com.hifiplayer.presentation.navigation

/**
 * Every place the user can be. Routes are strings because that is what the navigation library
 * stores in the back stack; the destinations themselves are typed so no screen has to guess a URL.
 */
sealed class HiFiDestination(val route: String) {

    data object Home : HiFiDestination("home")

    data object Library : HiFiDestination("library")

    data object Playlists : HiFiDestination("playlists")

    data object Settings : HiFiDestination("settings")

    data object NowPlaying : HiFiDestination("now_playing")

    data object Queue : HiFiDestination("queue")

    /** Tabs shown in the bottom bar, in order. Now Playing is not a tab: it opens over them. */
    companion object {
        val tabs: List<HiFiDestination> = listOf(Home, Library, Playlists, Settings)
    }
}
