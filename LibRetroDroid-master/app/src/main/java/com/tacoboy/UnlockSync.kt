package com.tacoboy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.android.libretrodroid.R
import com.tacoboy.RetroAchievementsClient.AwardOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends PendingUnlocks to RetroAchievements, and keeps trying while TacoBoy is running and
 * any are left.
 *
 * Retry timing is rcheevos' own (`rc_client_award_achievement_callback`): once immediately,
 * then waits doubling from 1s to a ceiling of two minutes, indefinitely. rcheevos keeps those
 * retries in memory, so its unlocks die with the process; these are on disk, and a pass also
 * runs whenever the app starts, a live-tracking login succeeds, or a new unlock is queued.
 *
 * There is deliberately no network-state listener to fire the moment a connection returns.
 * That needs ACCESS_NETWORK_STATE, and the permission list was cut to the bone for the public
 * release; a retry at most two minutes after reconnecting does the same job without it.
 */
internal object UnlockSync {
    private const val TAG = "TacoBoy.UnlockSync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val passLock = Mutex()
    private var retryLoop: Job? = null

    /** How one pass over the queue ended. */
    data class PassResult(val awarded: Int, val waitingToRetry: Boolean)

    /** Starts a retry loop over the queue, replacing any loop already waiting so its backoff
     *  starts over: a new unlock or a fresh login is a reason to try now, not in two minutes. */
    fun kick(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            retryLoop?.cancel()
            retryLoop = scope.launch {
                var retries = 0
                var awarded = 0
                while (true) {
                    val result = runPass(appContext)
                    awarded += result.awarded
                    if (!result.waitingToRetry) break
                    retries++
                    delay(retryDelaySeconds(retries) * 1000L)
                }
                if (awarded > 0) announceSent(appContext, awarded)
            }
        }
    }

    /**
     * One pass, oldest unlock first, for the account currently logged in to live tracking.
     * Stops at the first RETRY rather than trying the rest: no answer for one almost always
     * means no answer for all, and each would cost its own ten-second timeout. Stops at
     * AUTH_FAILED and does not ask to be retried, since only a new login can fix that.
     */
    suspend fun runPass(context: Context): PassResult = passLock.withLock {
        val username = TacoBoyPrefs.getRetroAchievementsSessionUsername(context)
            ?: return PassResult(0, waitingToRetry = false)
        val token = TacoBoyPrefs.getRetroAchievementsSessionToken(context)
            ?: return PassResult(0, waitingToRetry = false)

        val queue = PendingUnlocks.all(context)
            .filter { it.username.equals(username, ignoreCase = true) }
            .sortedBy { it.unlockedAtMs }
        var awarded = 0
        for (unlock in queue) {
            val outcome = RetroAchievementsClient.awardAchievement(
                username = username,
                sessionToken = token,
                achievementId = unlock.achievementId,
                gameHash = unlock.gameHash,
                secondsSinceUnlock = PendingUnlocks.secondsSinceUnlock(unlock.unlockedAtMs, System.currentTimeMillis()),
                core = unlock.core,
            )
            when (outcome) {
                AwardOutcome.AWARDED -> {
                    PendingUnlocks.remove(context, unlock)
                    awarded++
                }
                AwardOutcome.REJECTED -> {
                    TacoBoyLog.e(TAG, "RetroAchievements refused achievement ${unlock.achievementId}; dropped from the queue")
                    PendingUnlocks.remove(context, unlock)
                }
                AwardOutcome.RETRY -> return PassResult(awarded, waitingToRetry = true)
                AwardOutcome.AUTH_FAILED -> {
                    TacoBoyLog.e(TAG, "Live tracking login refused; ${queue.size - awarded} unlock(s) kept for the next login")
                    return PassResult(awarded, waitingToRetry = false)
                }
            }
        }
        PassResult(awarded, waitingToRetry = false)
    }

    /** rcheevos' schedule: [retries] counts retries so far, the first immediate, then
     *  1, 2, 4 ... 64 seconds, then 120 for every retry after. */
    fun retryDelaySeconds(retries: Int): Long = when {
        retries <= 1 -> 0L
        retries > 8 -> 120L
        else -> 1L shl (retries - 2)
    }

    private fun announceSent(context: Context, count: Int) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                context.resources.getQuantityString(R.plurals.achievements_queued_sent_toast, count, count),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
