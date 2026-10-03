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

    /** Global search (requirement 26). Opened from the header, closed with back. */
    data object Search : HiFiDestination("search")

    /** Album, artist, genre or folder detail: one screen, one route pattern (see [CollectionArgs]). */
    data object Collection : HiFiDestination("collection/{kind}/{id}?title={title}&subtitle={subtitle}") {
        const val KIND = "kind"
        const val ID = "id"
        const val TITLE = "title"
        const val SUBTITLE = "subtitle"
    }

    /** One playlist's tracks (phase 6). */
    data object PlaylistDetail : HiFiDestination("playlist/{playlistId}") {
        const val PLAYLIST_ID = "playlistId"
    }

    /** Tabs shown in the bottom bar, in order. Now Playing is not a tab: it opens over them. */
    companion object {
        val tabs: List<HiFiDestination> = listOf(Home, Library, Playlists, Settings)

        /** Screens that take over the whole window: no mini player, no bottom bar. */
        val fullScreen: Set<String> = setOf(NowPlaying.route, Queue.route)

        val tabRoutes: Set<String> = tabs.map { it.route }.toSet()
    }
}
