package com.hifiplayer.data.audio.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.hifiplayer.core.common.logging.AppLogger
import com.hifiplayer.nativeaudio.engine.Media3PlayerHandle

/**
 * Foreground playback service (requirement 7/27).
 *
 * Playback has to survive the activity being closed, the screen being locked and the phone being
 * routed to a Bluetooth headset, so the session lives here and not in the UI. Everything the
 * notification and the lock screen can do (play, pause, previous, next, seek, shuffle, repeat,
 * queue) is delegated to the same [com.hifiplayer.nativeaudio.engine.AudioEngine] the UI drives —
 * there is no second playback path that could drift out of sync (requirement 46).
 *
 * The service owns no engine of its own: it attaches to the engine the application built, so the
 * queue and the DSP configuration are shared, not duplicated.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var listener: Player.Listener? = null

    override fun onCreate() {
        super.onCreate()
        val dependencies = PlaybackServiceLocator.dependencies(this)
        val handle = dependencies.engine as? Media3PlayerHandle
        if (handle == null) {
            // Honest failure instead of a silent no-op: without a Media3 player there is no session
            // to publish, and pretending otherwise would give the user a notification that does
            // nothing when tapped.
            AppLogger.e(TAG, "El motor activo no expone un reproductor Media3; no se publica la MediaSession")
            stopSelf()
            return
        }

        val player = handle.media3Player
        val activityIntent = packageManager.getLaunchIntentForPackage(packageName)
        val sessionActivity = activityIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                Intent(it).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        session = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .apply { sessionActivity?.let { setSessionActivity(it) } }
            .build()

        // Media3 keeps the foreground notification in sync with the player by itself; the listener
        // only exists to end the foreground state as soon as nothing is playing, so the service is
        // never left running "just in case" (battery, requirement 37).
        listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) stopForegroundIfIdle(player)
            }
        }
        player.addListener(listener!!)

        PlaybackServiceLocator.markSessionActive(true)
        AppLogger.i(TAG, "Servicio de reproducción iniciado")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * The user swiped the app away. If something is playing it keeps playing (that is what a music
     * player must do); if nothing is playing the service is torn down right away.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.playbackState == Player.STATE_IDLE) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val player = session?.player
        listener?.let { player?.removeListener(it) }
        listener = null
        session?.release()
        session = null
        PlaybackServiceLocator.markSessionActive(false)
        AppLogger.i(TAG, "Servicio de reproducción detenido")
        super.onDestroy()
    }

    private fun stopForegroundIfIdle(player: Player) {
        val idle = player.playbackState == Player.STATE_IDLE || !player.playWhenReady
        if (idle) {
            // `stopForeground` (not `stopSelf`) so the session stays reachable for a moment: a
            // paused player must still answer a media button or a headset unplug event.
            stopForeground(STOP_FOREGROUND_DETACH)
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        const val SESSION_ID = "hifi-player-session"
    }
}
