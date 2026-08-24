package com.tacoboy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.android.libretrodroid.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Read-only achievement list for one game — reached from RomLibraryActivity's
 * "Check RetroAchievements" long-press action once a ROM resolves to a real
 * RA game ID. Clamp-aware like every other non-gameplay screen (see
 * BoundaryController). Always fetches fresh from RetroAchievementsClient on
 * open rather than caching: earned status changes as the user plays, and
 * this screen has no independent way to know when a cached copy went stale.
 * Login/hash-identification is the prerequisite (see SettingsActivity /
 * RomLibraryActivity.checkAchievements) — this screen assumes both already
 * succeeded and doesn't re-check credentials itself.
 */
class AchievementsActivity : AppCompatActivity() {

    private lateinit var boundaryController: BoundaryController
    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyState: TextView
    private lateinit var gameIcon: ImageView
    private lateinit var summaryText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_achievements)

        boundaryController = BoundaryController(
            this,
            findViewById(R.id.boundary_guideline),
            findViewById(R.id.occlusion_zone),
            findViewById(R.id.boundary_handle),
            findViewById(R.id.boundary_hint)
        )
        findViewById<View>(R.id.close_button).setOnClickListener { finish() }

        recyclerView = findViewById(R.id.achievements_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        loadingIndicator = findViewById(R.id.loading_indicator)
        emptyState = findViewById(R.id.empty_state)
        gameIcon = findViewById(R.id.game_icon)
        summaryText = findViewById(R.id.achievements_summary)

        val gameId = intent.getIntExtra(EXTRA_GAME_ID, 0)
        // Known immediately from the ROM entry, shown before the network call resolves
        // rather than leaving the toolbar blank while it's in flight.
        findViewById<TextView>(R.id.achievements_toolbar_title).text =
            intent.getStringExtra(EXTRA_GAME_TITLE) ?: getString(R.string.game_menu_check_achievements)

        if (gameId <= 0) {
            showEmpty(getString(R.string.achievements_list_load_failed))
        } else {
            loadProgress(gameId)
        }
    }

    override fun onResume() {
        super.onResume()
        boundaryController.refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Without this, a Pocket Taco B press (see PocketTacoDetector.isSpuriousBack)
     *  would silently back out of this screen too. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (PocketTacoDetector.isSpuriousBack(event.keyCode, event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun loadProgress(gameId: Int) {
        val username = TacoBoyPrefs.getRetroAchievementsUsername(this)
        val apiKey = TacoBoyPrefs.getRetroAchievementsApiKey(this)
        if (username == null || apiKey == null) {
            showEmpty(getString(R.string.achievements_check_not_logged_in))
            return
        }

        recyclerView.visibility = View.GONE
        emptyState.visibility = View.GONE
        loadingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            val progress = withContext(Dispatchers.IO) {
                RetroAchievementsClient.getGameProgress(username, apiKey, gameId)
            }
            loadingIndicator.visibility = View.GONE

            if (progress == null) {
                showEmpty(getString(R.string.achievements_list_load_failed))
                return@launch
            }

            findViewById<TextView>(R.id.achievements_toolbar_title).text = progress.gameTitle
            if (progress.iconUrl != null) {
                gameIcon.visibility = View.VISIBLE
                gameIcon.load(progress.iconUrl)
            }

            if (progress.achievements.isEmpty()) {
                showEmpty(getString(R.string.achievements_list_empty))
                return@launch
            }

            val earned = progress.achievements.count { it.earned }
            val totalPoints = progress.achievements.sumOf { it.points }
            summaryText.text = getString(
                R.string.achievements_list_summary, earned, progress.achievements.size, totalPoints
            )
            summaryText.visibility = View.VISIBLE

            recyclerView.adapter = AchievementsAdapter(progress.achievements)
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun showEmpty(message: String) {
        loadingIndicator.visibility = View.GONE
        recyclerView.visibility = View.GONE
        emptyState.text = message
        emptyState.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_GAME_ID = "game_id"
        const val EXTRA_GAME_TITLE = "game_title"

        fun start(context: Context, gameId: Int, gameTitle: String) {
            context.startActivity(
                Intent(context, AchievementsActivity::class.java)
                    .putExtra(EXTRA_GAME_ID, gameId)
                    .putExtra(EXTRA_GAME_TITLE, gameTitle)
            )
        }
    }
}
