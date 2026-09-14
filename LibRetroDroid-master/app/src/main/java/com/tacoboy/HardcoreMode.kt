package com.tacoboy

/**
 * RetroAchievements' two mid-session hardcore rules (compliance audit B7, and section G's
 * auto-fail "the ability to switch to hardcore mode without a reset of the game"):
 * casual -> hardcore must fully reset the game, hardcore -> casual may happen immediately.
 *
 * The Hardcore Mode preference alone cannot express that, because it can change while a game
 * is running -- Settings is reachable through the library with the game still underneath. So
 * a running game has its own mode, fixed when it loads, and this decides what a changed
 * preference does to it.
 */
internal enum class HardcoreTransition {
    /** Preference and running game agree. */
    NONE,

    /** Hardcore was turned off: allowed mid-session, takes effect at once. */
    DROP_TO_CASUAL,

    /** Hardcore was turned on: the game must be reloaded before it counts as hardcore. */
    RESET_INTO_HARDCORE,
}

internal fun hardcoreTransition(sessionHardcore: Boolean, preferenceHardcore: Boolean): HardcoreTransition =
    when {
        sessionHardcore == preferenceHardcore -> HardcoreTransition.NONE
        preferenceHardcore -> HardcoreTransition.RESET_INTO_HARDCORE
        else -> HardcoreTransition.DROP_TO_CASUAL
    }
