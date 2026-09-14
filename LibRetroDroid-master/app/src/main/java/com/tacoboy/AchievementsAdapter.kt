package com.tacoboy

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.android.libretrodroid.R
import com.swordfish.libretrodroid.AchievementSnapshot

/** Rows are pre-sorted by GameProgress.achievements' own order (RA's DisplayOrder) —
 *  nothing here reorders them. Earned vs. not is conveyed by the badge itself (RA
 *  serves a greyscale "_lock" variant for unearned — see AchievementInfo.badgeUrl)
 *  plus the points color, rather than a separate icon/label competing for space in
 *  an already dense row.
 *
 *  [runtime] and [unlockedThisSession] are only non-empty when the game is running: a status
 *  line then shows an active challenge and measured progress ("37/100"), and an achievement
 *  unlocked this session reads as earned even before RA has been told. */
class AchievementsAdapter(
    private val achievements: List<RetroAchievementsClient.AchievementInfo>,
    private val runtime: Map<Int, AchievementSnapshot> = emptyMap(),
    private val unlockedThisSession: Set<Int> = emptySet(),
) : RecyclerView.Adapter<AchievementsAdapter.ViewHolder>() {

    class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val badge: ImageView = root.findViewById(R.id.achievement_badge)
        val title: TextView = root.findViewById(R.id.achievement_title)
        val description: TextView = root.findViewById(R.id.achievement_description)
        val points: TextView = root.findViewById(R.id.achievement_points)
        val status: TextView = root.findViewById(R.id.achievement_status)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_achievement, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val achievement = achievements[position]
        holder.title.text = achievement.title
        holder.description.text = achievement.description
        holder.points.text = achievement.points.toString()
        val unlockedNow = achievement.id in unlockedThisSession
        val earned = achievement.earned || unlockedNow
        holder.points.setTextColor(if (earned) 0xFFFFD54F.toInt() else 0x88FFFFFF.toInt())
        holder.badge.alpha = if (earned) 1f else 0.6f
        holder.badge.load(if (unlockedNow) achievement.copy(earned = true).badgeUrl else achievement.badgeUrl)

        val context = holder.itemView.context
        val live = runtime[achievement.id]
        val status = when {
            unlockedNow -> context.getString(R.string.achievement_list_unlocked_this_session)
            earned || live == null -> ""
            live.challengeActive && live.progress.isNotEmpty() ->
                context.getString(R.string.achievement_list_challenge_active) + " · " + live.progress
            live.challengeActive -> context.getString(R.string.achievement_list_challenge_active)
            else -> live.progress
        }
        holder.status.text = status
        holder.status.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun getItemCount() = achievements.size
}
