package com.tacoboy

import android.util.Log

/**
 * Chokepoint every d/e/i/w call in this app should route through instead of calling
 * android.util.Log directly, so Settings > Advanced's Log level filter and Export Logs
 * button have something real to act on. Always forwards to android.util.Log regardless
 * of currentLevel first, so plain `adb logcat` keeps showing everything during
 * development -- currentLevel only gates what makes it into the exportable buffer.
 */
object TacoBoyLog {
    private const val BUFFER_CAPACITY = 500
    private val buffer = ArrayDeque<String>()
    private val bufferLock = Any()

    @Volatile
    var currentLevel: LogLevel = LogLevel.ERROR
        private set

    /** Called from TacoBoyApplication.onCreate and TacoBoyPrefs.setLogLevel so the
     *  in-memory filter always matches the saved preference, without threading a
     *  Context through every single log call site. */
    fun setLevel(level: LogLevel) {
        currentLevel = level
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.ERROR, tag, message, throwable)

    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
            LogLevel.ERROR -> if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        }
        if (level.severity < currentLevel.severity) return
        val line = buildString {
            append(level.name).append('/').append(tag).append(": ").append(message)
            if (throwable != null) {
                append('\n').append(Log.getStackTraceString(throwable))
            }
        }
        synchronized(bufferLock) {
            if (buffer.size >= BUFFER_CAPACITY) buffer.removeFirst()
            buffer.addLast(line)
        }
    }

    /** Settings > Advanced's Export Logs button reads this -- just the buffer captured
     *  since process start, not true system logcat (not readable by a third-party app
     *  on modern Android without a signature/system permission). */
    fun exportText(): String = synchronized(bufferLock) { buffer.joinToString("\n") }
}
