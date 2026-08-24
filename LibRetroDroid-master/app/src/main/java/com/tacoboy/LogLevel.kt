package com.tacoboy

/**
 * Settings > Advanced's log verbosity filter (see TacoBoyLog). severity is the minimum
 * level a message needs to reach the in-memory export buffer -- ERROR is most
 * restrictive, DEBUG captures everything. Every message is still forwarded to
 * android.util.Log unconditionally regardless of this filter, so `adb logcat` during
 * development isn't affected by what the user picked in-app.
 */
enum class LogLevel(val severity: Int) {
    ERROR(3), WARN(2), INFO(1), DEBUG(0)
}
