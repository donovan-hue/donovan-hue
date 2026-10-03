package com.hifiplayer.app.logging

import com.hifiplayer.core.common.logging.LogLevel
import com.hifiplayer.core.common.logging.LogRedaction
import com.hifiplayer.core.common.logging.Logger
import timber.log.Timber

/**
 * [Logger] backed by Timber.
 *
 * - Messages go through [LogRedaction] first, so no file path or content URI is ever printed
 *   (requirement 32: "nunca registrar información privada innecesaria").
 * - The app installs it with [LogLevel.WARNING] in release, which drops every DEBUG/INFO entry
 *   at the source (requirement 42: "desactivar logs DEBUG").
 */
class TimberLogger(
    private val minLevel: LogLevel = LogLevel.DEBUG,
) : Logger {

    override fun d(tag: String, message: String, throwable: Throwable?) {
        if (minLevel > LogLevel.DEBUG) return
        if (throwable != null) Timber.tag(tag).d(throwable, safe(message)) else Timber.tag(tag).d(safe(message))
    }

    override fun i(tag: String, message: String, throwable: Throwable?) {
        if (minLevel > LogLevel.INFO) return
        if (throwable != null) Timber.tag(tag).i(throwable, safe(message)) else Timber.tag(tag).i(safe(message))
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (minLevel > LogLevel.WARNING) return
        if (throwable != null) Timber.tag(tag).w(throwable, safe(message)) else Timber.tag(tag).w(safe(message))
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Timber.tag(tag).e(throwable, safe(message)) else Timber.tag(tag).e(safe(message))
    }

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            LogLevel.DEBUG -> d(tag, message, throwable)
            LogLevel.INFO -> i(tag, message, throwable)
            LogLevel.WARNING -> w(tag, message, throwable)
            LogLevel.ERROR -> e(tag, message, throwable)
        }
    }

    private fun safe(message: String): String = LogRedaction.redact(message)
}
