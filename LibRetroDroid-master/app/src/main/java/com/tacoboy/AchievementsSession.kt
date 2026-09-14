package com.tacoboy

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.android.libretrodroid.R
import com.swordfish.libretrodroid.GLRetroView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates live RetroAchievements tracking for one running game: identifies it (the
 * same hash + identifyGameId path as RomLibraryActivity's manual "Check RetroAchievements"
 * long-press), fetches achievement trigger definitions, activates them in the native
 * rc_runtime_t runtime (GLRetroView.loadAchievements -> LibretroDroid, see
 * libretrodroid.cpp/achievements.cpp), then listens for unlock events to announce and
 * submit. Softcore only: Hardcore Mode now enforces RA's rules on save-state loading and
 * mode switching (TacoBoyActivity.sessionHardcore), but unlocks are still submitted with
 * hardcore = 0 -- see RETROACHIEVEMENTS-COMPLIANCE.md for what remains before that changes.
 *
 * Gated on a *second*, separate login beyond the identification-only username+API-key
 * pair every other RA feature in this app needs (Settings' "Live Tracking" section) --
 * fetching trigger definitions and submitting unlocks turned out to need a real login
 * session token, not just the permanent API key (see RetroAchievementsClient's class doc
 * comment for the live-confirmed reason). No further settings toggle beyond that login
 * itself: tracking starts automatically on every game load once it's set up.
 */
class AchievementsSession(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val retroView: GLRetroView,
) {
    private var gameHash: String? = null
    private var achievementsById: Map<Int, RetroAchievementsClient.AchievementInfo> = emptyMap()
    private val submittedThisSession = mutableSetOf<Int>()
    // One "saved for later" toast per session is enough to say the connection is down; a
    // toast for every unlock after that would only repeat it.
    private var toldQueuedOffline = false
    // The user agent's core segment (RetroAchievementsClient.coreClause), fixed for the
    // session: the core cannot change without a new game load, which makes a new session.
    private var coreClause: String? = null

    // Lightweight per-session pause (quick menu's "Achievement Tracking" toggle) --
    // distinct from Settings > Achievements' login/logout, which tears down live
    // tracking's session token entirely. Doesn't touch the native rc_runtime_t at all;
    // the runtime keeps evaluating conditions and firing trigger events exactly as
    // before, this just skips announcing/submitting them while paused. One real
    // consequence worth knowing: since rc_runtime_t never re-fires an achievement it's
    // already triggered once, an achievement that triggers *while* paused is gone for
    // the rest of this session even after resuming -- there's no native "replay" of it.
    private var trackingEnabled = true

    // TacoBoyActivity constructs and holds an AchievementsSession unconditionally on every
    // game load (see setupRetroView), before start()'s async activation work has even run --
    // it doesn't yet know whether live tracking is logged in, whether the game was
    // recognized, or whether it has any unearned achievements to track. The quick menu's
    // toggle needs to hide itself when there's genuinely nothing active to pause, so this
    // tracks that separately from mere object existence -- set true only once
    // loadAchievements() actually activates something in the native runtime.
    private var active = false

    fun isActive(): Boolean = active

    fun isTrackingEnabled(): Boolean = trackingEnabled

    fun setTrackingEnabled(enabled: Boolean) {
        trackingEnabled = enabled
    }

    fun start(rom: RomLibrary.RomEntry, gameSystem: GameSystem, core: CoreDefinition) {
        lifecycleOwner.lifecycleScope.launch {
            val apiKeyUsername = TacoBoyPrefs.getRetroAchievementsUsername(context) ?: return@launch
            val apiKey = TacoBoyPrefs.getRetroAchievementsApiKey(context) ?: return@launch
            val sessionUsername = TacoBoyPrefs.getRetroAchievementsSessionUsername(context) ?: return@launch
            val sessionToken = TacoBoyPrefs.getRetroAchievementsSessionToken(context) ?: return@launch

            val activation = withContext(Dispatchers.IO) {
                // Read off the main thread: the native getter waits on coreLock, which
                // retro_run holds for the length of a frame.
                val clause = RetroAchievementsClient.coreClause(core.fileName, retroView.getLibraryVersion())
                coreClause = clause
                TacoBoyLog.d(TAG, "user agent core segment: $clause")
                val hash = RomHasher.raHash(context, rom) ?: return@withContext null
                fetchOrLoadCached(hash, apiKeyUsername, apiKey, sessionUsername, sessionToken, clause)
            }

            if (activation == null) {
                retroView.resetAchievements()
                return@launch
            }
            val hash = activation.hash
            val progress = activation.progress
            val definitions = activation.definitions
            if (activation.fromCache) {
                // The cached-data toast already says unlocks will be sent later.
                toldQueuedOffline = true
                Toast.makeText(context, R.string.achievements_tracking_from_cache_toast, Toast.LENGTH_LONG).show()
            }

            gameHash = hash
            achievementsById = progress.achievements.associateBy { it.id }
            submittedThisSession.clear()

            val definitionsById = definitions.associateBy { it.id }
            // Earned but not yet sent reads as unearned to RA, so without this a queued unlock
            // would be armed again, and re-earned, before the first one ever arrived.
            val pendingIds = PendingUnlocks.idsFor(context, sessionUsername)
            val toActivate = progress.achievements
                .asSequence()
                .filter { !it.earned && it.id !in pendingIds }
                .mapNotNull { info ->
                    val definition = definitionsById[info.id] ?: return@mapNotNull null
                    info.id to definition.memAddr
                }
                .toList()

            if (toActivate.isEmpty()) {
                retroView.resetAchievements()
                return@launch
            }

            TacoBoyLog.d(TAG, "tracking ${progress.gameTitle}: ${toActivate.size} of ${progress.achievements.size} achievements active")

            active = true
            retroView.loadAchievements(
                gameSystem.raConsoleId,
                toActivate.map { it.first }.toIntArray(),
                toActivate.map { it.second }.toTypedArray(),
            )

            retroView.getAchievementTriggeredEvents().collect { achievementId ->
                onAchievementTriggered(sessionUsername, sessionToken, achievementId)
            }
        }
    }

    private class Activation(
        val hash: String,
        val progress: RetroAchievementsClient.GameProgress,
        val definitions: List<RetroAchievementsClient.AchievementDefinition>,
        val fromCache: Boolean,
    )

    /**
     * What a session needs to start, from RA when it answers and from AchievementCache when it
     * cannot be reached. Every successful fetch refreshes the cache, so it is as current as the
     * last online start.
     *
     * RA answering "not recognised" (game id 0) is a real answer, so the cache is not consulted:
     * only a failed request falls back. Must run off the main thread.
     */
    private fun fetchOrLoadCached(
        hash: String,
        apiKeyUsername: String,
        apiKey: String,
        sessionUsername: String,
        sessionToken: String,
        clause: String,
    ): Activation? {
        val gameId = RetroAchievementsClient.identifyGameId(apiKeyUsername, apiKey, hash, clause)
        if (gameId != null && gameId <= 0) return null
        val fetched = gameId?.let {
            AchievementCache.refresh(context, hash, it, apiKeyUsername, apiKey, sessionUsername, sessionToken, clause)
        }
        if (fetched != null) return Activation(hash, fetched.progress, fetched.definitions, fromCache = false)

        val cached = AchievementCache.load(context, hash, apiKeyUsername)
        if (cached == null) {
            TacoBoyLog.d(TAG, "RetroAchievements unreachable and nothing cached for this game: not tracking")
            return null
        }
        TacoBoyLog.d(TAG, "RetroAchievements unreachable: tracking from data cached at ${cached.cachedAtMs}")
        return Activation(hash, cached.progress, cached.definitions, fromCache = true)
    }

    private suspend fun onAchievementTriggered(sessionUsername: String, sessionToken: String, achievementId: Int) {
        // rc_runtime_t itself won't re-fire an already-triggered achievement within the same
        // runtime instance, but this guards against any edge case in the JNI event drain.
        if (!submittedThisSession.add(achievementId)) return
        if (!trackingEnabled) return
        val info = achievementsById[achievementId] ?: return
        val hash = gameHash ?: return

        TacoBoyLog.d(TAG, "achievement triggered: ${info.title} ($achievementId)")

        Toast.makeText(
            context,
            context.getString(R.string.achievement_unlocked_toast, info.title, info.points),
            Toast.LENGTH_LONG,
        ).show()

        // Queued before the first attempt, not after a failed one, so nothing between the
        // trigger and RA's answer -- the process being killed included -- can lose it.
        val unlock = PendingUnlock(
            username = sessionUsername,
            achievementId = achievementId,
            gameHash = hash,
            hardcore = 0,
            unlockedAtMs = System.currentTimeMillis(),
            core = coreClause,
        )
        val sentNow = withContext(Dispatchers.IO) {
            PendingUnlocks.add(context, unlock)
            UnlockSync.runPass(context)
            unlock.achievementId !in PendingUnlocks.idsFor(context, sessionUsername)
        }
        if (!sentNow) {
            if (!toldQueuedOffline) {
                toldQueuedOffline = true
                Toast.makeText(context, R.string.achievement_queued_offline_toast, Toast.LENGTH_LONG).show()
            }
            UnlockSync.kick(context)
        }
    }

    private companion object {
        const val TAG = "TacoBoy.AchievementsSession"
    }
}
