package com.hifiplayer

import android.app.Application
import com.hifiplayer.app.AppGraph
import com.hifiplayer.app.BuildConfig
import com.hifiplayer.app.logging.CrashReporter
import com.hifiplayer.app.logging.ReleaseTree
import com.hifiplayer.app.logging.TimberLogger
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.core.common.logging.LogLevel
import com.hifiplayer.data.audio.service.PlaybackServiceLocator
import timber.log.Timber

/**
 * Application entry point.
 *
 * Order matters and is deliberate: logging first (so any failure below is visible), then the object
 * graph, then the playback service wiring — the service may have been started by the system before
 * any activity exists, and it must never find the graph missing.
 */
class HiFiPlayerApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Lo primero de todo: si algo de lo que viene ahora falla, el usuario podrá enviarnos el error
        // (la app se cerraba al abrir en un móvil real y no había forma de ver por qué).
        CrashReporter.install(this)
        installLogging()
        CrashReporter.stage("Application: registro listo")
        graph = AppGraph(this)
        CrashReporter.stage("Application: grafo construido")
        PlaybackServiceLocator.install { graph.serviceDependencies }
        CrashReporter.stage("Application: servicio enlazado")
        AppLogger.i(TAG, "HiFi Player iniciado")
    }

    /**
     * Requirement 42: DEBUG logging is on in debug builds and off in release builds.
     *
     * [AppLogger] is the only logger the rest of the app knows about, and it is installed with a
     * level filter that drops everything below WARNING in release *at the source*. The Timber tree
     * is planted for the same reason a player keeps a level meter: so the remaining warnings have
     * somewhere to go.
     */
    private fun installLogging() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            AppLogger.install(TimberLogger(LogLevel.DEBUG))
        } else {
            Timber.plant(ReleaseTree())
            AppLogger.install(TimberLogger(LogLevel.WARNING))
        }
    }

    private companion object {
        const val TAG = "App"
    }
}
