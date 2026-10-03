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

    /** Audio Information: SOURCE vs OUTPUT, decoders and the on-demand format check (phase 8). */
    data object AudioInfo : HiFiDestination("settings/audio/info")

    /** Output devices: speaker, headphones, Bluetooth and USB DAC (phase 9). */
    data object OutputDevices : HiFiDestination("settings/audio/outputs")

    /** Bit-perfect, with what the platform confirmed and why it is not active when it is not (phase 10). */
    data object BitPerfect : HiFiDestination("settings/audio/bit-perfect")

    /** Parametric EQ: 10 bands, presets, preamp (phase 12). */
    data object Eq : HiFiDestination("settings/audio/eq")

    /** ReplayGain: OFF / TRACK / ALBUM, applied at playback time only (phase 11). */
    data object ReplayGain : HiFiDestination("settings/audio/replay-gain")

    /** Crossfeed: OFF / LOW / MEDIUM / HIGH, plus balance and app gain (phase 13). */
    data object Crossfeed : HiFiDestination("settings/audio/crossfeed")

    /** Tabs shown in the bottom bar, in order. Now Playing is not a tab: it opens over them. */
    companion object {
        val tabs: List<HiFiDestination> = listOf(Home, Library, Playlists, Settings)

        /**
         * Screens that take over the whole window: no mini player, no bottom bar.
         *
         * The sound screens are in this list because they are reached from Now Playing and from
         * Settings and are always left with the back button — showing the mini player there would put
         * two transport controls on the same screen.
         */
        val fullScreen: Set<String> = setOf(
            NowPlaying.route,
            Queue.route,
            AudioInfo.route,
            OutputDevices.route,
            BitPerfect.route,
            Eq.route,
            ReplayGain.route,
            Crossfeed.route,
            Search.route,
            Collection.route,
            PlaylistDetail.route,
        )

        val tabRoutes: Set<String> = tabs.map { it.route }.toSet()
    }
}
