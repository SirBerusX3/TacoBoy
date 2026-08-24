package com.tacoboy

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash history that outlives the process that crashed.
 *
 * TacoBoyLog's buffer is in memory, and a crash is precisely the case where that memory is
 * about to be thrown away -- so Export Logs could never show the one thing worth exporting.
 * This writes a short record to disk instead, at the two moments a crash is actually known
 * about:
 *
 *  - a Kotlin/Java exception reaching TacoBoyApplication's uncaught handler, which still has
 *    a live process and a stack trace to write;
 *  - a *native* crash, which kills the process outright with no handler running and so can
 *    only be recorded after the fact, on the next launch, by TacoBoyActivity noticing that a
 *    ROM began loading and never reached its first frame (see TacoBoyPrefs.beginRomLoad).
 *
 * Deliberately plain text and deliberately small. Nothing here is sent anywhere -- it exists
 * so that "it crashed on this game yesterday" can be answered with a file rather than a
 * memory, whether that is the user reading it or a bug report they attach it to.
 */
object CrashLog {
    private const val TAG = "TacoBoy.CrashLog"
    private const val FILE_NAME = "crash_log.txt"

    /** Enough to see a pattern across a few sessions; small enough that the file stays
     *  readable and bounded without needing real log rotation. */
    private const val MAX_RECORDS = 20

    private const val SEPARATOR = "\n----- crash -----\n"

    /**
     * Appends one record. Never throws: every caller is either mid-crash or mid-recovery,
     * and a failure to write the report must not become a second failure.
     */
    fun record(context: Context, summary: String, detail: String? = null, throwable: Throwable? = null) {
        try {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val record = buildString {
                append(stamp).append(' ').append(summary)
                if (detail != null) append('\n').append(detail)
                if (throwable != null) {
                    append('\n').append(android.util.Log.getStackTraceString(throwable))
                }
            }
            val file = file(context)
            val existing = if (file.isFile) file.readText() else ""
            val records = (existing.split(SEPARATOR) + record)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .takeLast(MAX_RECORDS)
            file.writeText(records.joinToString(SEPARATOR))
        } catch (e: Exception) {
            // Logging only -- there is nothing useful to do about a failed crash report, and
            // whatever is already going wrong is the more important problem.
            TacoBoyLog.e(TAG, "Failed to write crash record", e)
        }
    }

    /** Empty string when nothing has ever crashed, which is the common case. */
    fun readAll(context: Context): String {
        return try {
            val file = file(context)
            if (file.isFile) file.readText() else ""
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to read crash log", e)
            ""
        }
    }

    fun hasRecords(context: Context): Boolean = readAll(context).isNotEmpty()

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to clear crash log", e)
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
