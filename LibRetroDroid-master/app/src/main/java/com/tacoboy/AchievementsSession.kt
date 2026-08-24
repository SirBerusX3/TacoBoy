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
 * submit. Softcore only: hardcore mode needs disabling save-states while tracking, which
 * conflicts with this app's existing save-state feature, so it's out of scope for this
 * pass.
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

    fun start(rom: RomLibrary.RomEntry, gameSystem: GameSystem) {
        lifecycleOwner.lifecycleScope.launch {
            val apiKeyUsername = TacoBoyPrefs.getRetroAchievementsUsername(context) ?: return@launch
            val apiKey = TacoBoyPrefs.getRetroAchievementsApiKey(context) ?: return@launch
            val sessionUsername = TacoBoyPrefs.getRetroAchievementsSessionUsername(context) ?: return@launch
            val sessionToken = TacoBoyPrefs.getRetroAchievementsSessionToken(context) ?: return@launch

            val activation = withContext(Dispatchers.IO) {
                val hash = RomHasher.raHash(context, rom) ?: return@withContext null
                val gameId = RetroAchievementsClient.identifyGameId(apiKeyUsername, apiKey, hash)
                if (gameId == null || gameId <= 0) return@withContext null
                val progress = RetroAchievementsClient.getGameProgress(apiKeyUsername, apiKey, gameId)
                    ?: return@withContext null
                val definitions = RetroAchievementsClient.getAchievementDefinitions(sessionUsername, sessionToken, gameId)
                    ?: return@withContext null
                Triple(hash, progress, definitions)
            }

            if (activation == null) {
                retroView.resetAchievements()
                return@launch
            }
            val (hash, progress, definitions) = activation

            gameHash = hash
            achievementsById = progress.achievements.associateBy { it.id }
            submittedThisSession.clear()

            val definitionsById = definitions.associateBy { it.id }
            val toActivate = progress.achievements
                .asSequence()
                .filter { !it.earned }
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

        withContext(Dispatchers.IO) {
            RetroAchievementsClient.awardAchievement(sessionUsername, sessionToken, achievementId, hash)
        }
    }

    private companion object {
        const val TAG = "TacoBoy.AchievementsSession"
    }
}
