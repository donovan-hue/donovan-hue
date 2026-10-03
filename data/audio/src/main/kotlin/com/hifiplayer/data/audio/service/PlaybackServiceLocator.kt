package com.hifiplayer.data.audio.service

import android.content.Context
import com.hifiplayer.domain.repository.MusicRepository
import com.hifiplayer.domain.repository.PlaybackRepository
import com.hifiplayer.domain.repository.PlaylistRepository
import com.hifiplayer.nativeaudio.engine.AudioEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the playback service needs in order to exist, supplied by the app at startup.
 *
 * The service cannot build an engine by itself: the engine needs the black-box audio stack and a
 * settings snapshot, both of which the application owns. Passing them in through this locator keeps
 * the service free of construction logic and keeps the object graph in one place.
 */
interface PlaybackServiceDependencies {
    val engine: AudioEngine
    val playbackRepository: PlaybackRepository

    /** The library the car and any other media browser may read (requirement 27). */
    val musicRepository: MusicRepository
    val playlistRepository: PlaylistRepository
}

/**
 * Single instance holder for the playback service.
 *
 * `PlaybackServiceLocator.install(...)` is called once from `Application.onCreate()`. The service
 * may outlive the activity, and the process may be recreated by the system just to serve a media
 * button press — in that case the application class runs first, so the dependencies are always
 * installed before [PlaybackService] asks for them.
 */
object PlaybackServiceLocator {

    private val lock = Any()

    @Volatile
    private var provider: ((Context) -> PlaybackServiceDependencies)? = null

    private val _active = MutableStateFlow(false)

    /** True while a `MediaSession` is alive; the UI uses it to decide between controller and repo. */
    val isSessionActive: StateFlow<Boolean> = _active.asStateFlow()

    fun install(provider: (Context) -> PlaybackServiceDependencies) {
        synchronized(lock) { this.provider = provider }
    }

    fun isInstalled(): Boolean = provider != null

    fun dependencies(context: Context): PlaybackServiceDependencies {
        val current = provider
        check(current != null) {
            "PlaybackServiceLocator.install() no fue llamado: la Application debe registrar las dependencias del servicio de reproducción"
        }
        return current.invoke(context.applicationContext)
    }

    internal fun markSessionActive(active: Boolean) {
        _active.value = active
    }
}
