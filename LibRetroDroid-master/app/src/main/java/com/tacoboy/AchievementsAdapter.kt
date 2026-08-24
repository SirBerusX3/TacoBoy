package com.tacoboy

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.android.libretrodroid.R

/** Rows are pre-sorted by GameProgress.achievements' own order (RA's DisplayOrder) —
 *  nothing here reorders them. Earned vs. not is conveyed by the badge itself (RA
 *  serves a greyscale "_lock" variant for unearned — see AchievementInfo.badgeUrl)
 *  plus the points color, rather than a separate icon/label competing for space in
 *  an already dense row. */
class AchievementsAdapter(
    private val achievements: List<RetroAchievementsClient.AchievementInfo>
) : RecyclerView.Adapter<AchievementsAdapter.ViewHolder>() {

    class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val badge: ImageView = root.findViewById(R.id.achievement_badge)
        val title: TextView = root.findViewById(R.id.achievement_title)
        val description: TextView = root.findViewById(R.id.achievement_description)
        val points: TextView = root.findViewById(R.id.achievement_points)
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
        holder.points.setTextColor(if (achievement.earned) 0xFFFFD54F.toInt() else 0x88FFFFFF.toInt())
        holder.badge.alpha = if (achievement.earned) 1f else 0.6f
        holder.badge.load(achievement.badgeUrl)
    }

    override fun getItemCount() = achievements.size
}
