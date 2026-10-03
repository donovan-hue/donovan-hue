package com.hifiplayer.core.common.logging

/** Log levels (requirement 32). DEBUG is never emitted from a release build. */
enum class LogLevel(val label: String) {
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARNING("WARNING"),
    ERROR("ERROR"),
}

/**
 * Logging facade so no module is bound to Timber/Logcat/print. The app module installs the real
 * implementation during startup; everything else just asks [AppLogger].
 *
 * Privacy rule (requirement 32): never log paths, URIs, tag contents, account data or anything
 * that identifies the user's files. [LogRedaction] enforces this for the common cases.
 */
interface Logger {
    fun d(tag: String, message: String, throwable: Throwable? = null)
    fun i(tag: String, message: String, throwable: Throwable? = null)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)
}

/** Discards everything: safe default before installation and in unit tests. */
object NoOpLogger : Logger {
    override fun d(tag: String, message: String, throwable: Throwable?) = Unit
    override fun i(tag: String, message: String, throwable: Throwable?) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) = Unit
}

/** In-memory logger used by unit tests to assert that failures were logged with a reason. */
class RecordingLogger(private val minLevel: LogLevel = LogLevel.DEBUG) : Logger {
    data class Entry(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    private val entries = mutableListOf<Entry>()
    val recorded: List<Entry> get() = entries.toList()

    override fun d(tag: String, message: String, throwable: Throwable?) = add(LogLevel.DEBUG, tag, message, throwable)
    override fun i(tag: String, message: String, throwable: Throwable?) = add(LogLevel.INFO, tag, message, throwable)
    override fun w(tag: String, message: String, throwable: Throwable?) = add(LogLevel.WARNING, tag, message, throwable)
    override fun e(tag: String, message: String, throwable: Throwable?) = add(LogLevel.ERROR, tag, message, throwable)

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) = add(level, tag, message, throwable)

    private fun add(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (level.ordinal >= minLevel.ordinal) entries += Entry(level, tag, message, throwable)
    }

    fun messages(level: LogLevel): List<String> = entries.filter { it.level == level }.map { it.message }
}

/**
 * Process-wide logger holder. Installed by the application entry point:
 *
 * ```
 * AppLogger.install(if (BuildConfig.DEBUG) TimberLogger(LogLevel.DEBUG) else TimberLogger(LogLevel.WARNING))
 * ```
 */
object AppLogger {
    @Volatile
    private var delegate: Logger = NoOpLogger

    fun install(logger: Logger) { delegate = logger }

    fun uninstall() { delegate = NoOpLogger }

    fun logger(): Logger = delegate

    fun d(tag: String, message: String, throwable: Throwable? = null) = delegate.d(tag, message, throwable)
    fun i(tag: String, message: String, throwable: Throwable? = null) = delegate.i(tag, message, throwable)
    fun w(tag: String, message: String, throwable: Throwable? = null) = delegate.w(tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = delegate.e(tag, message, throwable)
}

/**
 * Strips anything that looks like a filesystem path or a content/file URI, keeping only the
 * extension so logs stay useful without exposing the user's library structure.
 */
object LogRedaction {
    private val PATH_LIKE = Regex("""(content://|file://|/storage/|/data/|/sdcard/|/mnt/|/MediaStore/)[^\s,)\]]*""")

    fun redact(message: String): String = PATH_LIKE.replace(message) { match ->
        val text = match.value
        val extension = text.substringAfterLast('.', "")
        when {
            extension.isNotEmpty() && extension.length <= 5 -> "<ruta:*.$extension>"
            else -> "<ruta>"
        }
    }
}
