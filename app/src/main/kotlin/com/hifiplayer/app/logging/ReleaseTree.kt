package com.hifiplayer.app.logging

import android.util.Log
import timber.log.Timber

/**
 * Release logging: WARNING and ERROR only, never DEBUG or INFO.
 *
 * The level filter lives in two places on purpose — [TimberLogger] drops the call before it is even
 * formatted, and this tree refuses anything below WARN, so a direct `Timber.d` somewhere else can
 * not leak debug output into a release build either (requirement 42).
 */
class ReleaseTree : Timber.Tree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val safeTag = tag ?: "HiFiPlayer"
        val safeMessage = com.hifiplayer.core.common.logging.LogRedaction.redact(message)
        if (t != null) {
            Log.println(priority, safeTag, "$safeMessage\n${Log.getStackTraceString(t)}")
        } else {
            Log.println(priority, safeTag, safeMessage)
        }
    }
}
