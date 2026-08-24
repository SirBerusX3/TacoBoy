package com.tacoboy

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import kotlin.system.exitProcess

/** How long the app has to stay up before a crash stops counting as a *startup* crash.
 *  Long enough to get through core load and a first frame on the slowest system (PS1 through
 *  the BIOS), short enough that a crash minutes into a game isn't blamed on startup. */
private const val STARTUP_WINDOW_MS = 30_000L

/**
 * Catches uncaught Kotlin/Java exceptions (NOT native crashes — a native
 * SIGSEGV/SIGABRT kills the process before this ever runs; see
 * TacoBoyPrefs.beginRomLoad for how those are handled instead) and
 * restarts cleanly into TacoBoyActivity instead of Android's default
 * "app has stopped" dialog dumping the user out to the home screen.
 *
 * That restart is the whole point, and it is also the risk: relaunching straight back into
 * the state that just crashed will crash again, forever, with the user unable to get a word
 * in. So each crash is recorded — to disk, since the process is about to end and
 * TacoBoyLog's buffer with it — and crashes that happen within [STARTUP_WINDOW_MS] of launch
 * increment a streak that TacoBoyActivity reads to come up in a safe state instead.
 */
class TacoBoyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        TacoBoyLog.setLevel(TacoBoyPrefs.getLogLevel(this))

        val startedAt = SystemClock.elapsedRealtime()

        // Surviving the startup window is what "healthy" means here: there is no single
        // event that says the app is fine (a game may never be loaded at all), but staying
        // up this long rules out the crash-on-launch loop the streak exists to break.
        Handler(Looper.getMainLooper()).postDelayed(
            { TacoBoyPrefs.clearCrashStreak(this) },
            STARTUP_WINDOW_MS
        )

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            TacoBoyLog.e("TacoBoy.Crash", "Uncaught exception on ${thread.name}, restarting", throwable)

            val uptimeMs = SystemClock.elapsedRealtime() - startedAt
            // Each step below is separately guarded: this handler runs while the app is
            // already failing, and losing the restart because the *reporting* threw would
            // turn a recoverable crash into the dead-app dialog this class exists to avoid.
            try {
                CrashLog.record(
                    context = this,
                    summary = "Uncaught ${throwable.javaClass.simpleName} on thread ${thread.name}",
                    detail = "App had been running ${uptimeMs / 1000}s",
                    throwable = throwable,
                )
            } catch (e: Throwable) {
                // Nothing further to try -- fall through to the restart, which matters more.
            }

            try {
                if (uptimeMs < STARTUP_WINDOW_MS) TacoBoyPrefs.recordStartupCrash(this)
            } catch (e: Throwable) {
            }

            try {
                startActivity(
                    Intent(this, TacoBoyActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            } catch (e: Exception) {
                TacoBoyLog.e("TacoBoy.Crash", "Failed to restart after crash", e)
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}
