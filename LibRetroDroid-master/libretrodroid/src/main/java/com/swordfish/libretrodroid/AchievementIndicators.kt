package com.swordfish.libretrodroid

/** Raised by the achievements runtime as a game plays; see achievements.cpp's eventHandler. */
sealed class AchievementIndicatorEvent {
    abstract val achievementId: Int

    /** Measured progress changed. [progress] is rcheevos' own text, "37/100" or "42%";
     *  [fraction] is how close to done, 0 to 1. */
    data class Progress(override val achievementId: Int, val progress: String, val fraction: Float) :
        AchievementIndicatorEvent()

    /** A challenge started (every condition but the final trigger is true) or ended. */
    data class Challenge(override val achievementId: Int, val started: Boolean) : AchievementIndicatorEvent()
}

/** One active achievement's live state. [progress] is empty when it is not measured. */
data class AchievementSnapshot(val achievementId: Int, val progress: String, val challengeActive: Boolean)
