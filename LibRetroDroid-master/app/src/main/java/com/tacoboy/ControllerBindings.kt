package com.tacoboy

import android.content.Context
import android.view.KeyEvent

/**
 * User-configurable physical-button -> RetroPad-function mapping, per GameSystem.
 *
 * Supersedes LibretroDroid's own GamepadsManager swap (see GLRetroView.kt /
 * GamepadsManager.kt) — that mapping is hardcoded and only ever runs if GLRetroView
 * itself holds view focus, which nothing in this app grants it. TacoBoyActivity
 * applies this table itself instead, so it stays fully user-configurable and doesn't
 * depend on GLRetroView's internal focus/consume behavior at all.
 *
 * `Target.keyCode` is one of the Android KEYCODE_BUTTON_ / KEYCODE_DPAD_ constants
 * LibretroDroid's native side already understands as a RetroPad slot (see GamepadsManager.GAMEPAD_KEYS) —
 * reusing those exact values means no new native-side concept is needed, just a
 * configurable "which physical keycode should produce this one" lookup.
 */
object ControllerBindings {

    enum class Target(val keyCode: Int, val label: String) {
        A(KeyEvent.KEYCODE_BUTTON_A, "A"),
        B(KeyEvent.KEYCODE_BUTTON_B, "B"),
        X(KeyEvent.KEYCODE_BUTTON_X, "X"),
        Y(KeyEvent.KEYCODE_BUTTON_Y, "Y"),
        L1(KeyEvent.KEYCODE_BUTTON_L1, "L1"),
        R1(KeyEvent.KEYCODE_BUTTON_R1, "R1"),
        L2(KeyEvent.KEYCODE_BUTTON_L2, "L2"),
        R2(KeyEvent.KEYCODE_BUTTON_R2, "R2"),
        START(KeyEvent.KEYCODE_BUTTON_START, "Start"),
        SELECT(KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
        L3(KeyEvent.KEYCODE_BUTTON_THUMBL, "L3"),
        R3(KeyEvent.KEYCODE_BUTTON_THUMBR, "R3"),
        DPAD_UP(KeyEvent.KEYCODE_DPAD_UP, "D-Pad Up"),
        DPAD_DOWN(KeyEvent.KEYCODE_DPAD_DOWN, "D-Pad Down"),
        DPAD_LEFT(KeyEvent.KEYCODE_DPAD_LEFT, "D-Pad Left"),
        DPAD_RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT, "D-Pad Right"),
    }

    /**
     * Identity — each target defaults to the physical button of the same name.
     * Deliberately NOT GamepadsManager's A/B and X/Y swap: that swap exists to
     * compensate for controllers that report button *position* using Xbox-style
     * convention (bottom=A, right=B, ...) regardless of what's printed on the
     * button, which would misidentify a Nintendo-labeled pad's B (bottom-left) as
     * position-A. Confirmed on the real Pocket Taco via three separate live
     * `adb shell getevent`/keylayout captures (not assumed): its firmware reports
     * each physical button's own printed label directly as the matching Android
     * keycode (physical B -> KEYCODE_BUTTON_B, A -> BUTTON_A, X -> BUTTON_X,
     * Y -> BUTTON_Y) — no positional relabeling happens at the driver level for
     * this device, so applying the Xbox-position swap here was actively wrong,
     * not just unverified. See CHANGELOG.md's entry on this finding for the
     * capture data.
     */
    private val DEFAULT_SOURCE: Map<Target, Int> = Target.entries.associateWith { it.keyCode }

    fun defaultSource(target: Target): Int = DEFAULT_SOURCE.getValue(target)

    fun getSource(context: Context, system: GameSystem, target: Target): Int {
        return TacoBoyPrefs.getButtonBindingSource(context, system, target) ?: defaultSource(target)
    }

    /**
     * Gives whichever other target currently resolves to `sourceKeyCode` this target's
     * *current* source instead, so the table stays a bijection (one physical button
     * per target) no matter what. Found the hard way, confirmed on real hardware:
     * merely *clearing* a conflicting target's override is a no-op when that target
     * was still on its DEFAULT_SOURCE value, since it has no override to clear. A/B
     * default to each other's physical button (the documented swap), so assigning A
     * to its own physical button while B silently kept defaulting to that same
     * button left both resolving to one physical press — resolveTarget's firstOrNull
     * just picked A every time, and B's row kept showing a binding that could never
     * actually fire. Shared by setSource and clearSource (reset-to-default is just
     * "assign the default value"), since resetting one half of a swapped pair has
     * exactly the same collision risk as assigning did.
     */
    private fun resolveConflicts(context: Context, system: GameSystem, target: Target, sourceKeyCode: Int) {
        val previousSourceForTarget = getSource(context, system, target)
        Target.entries
            .filter { it != target && getSource(context, system, it) == sourceKeyCode }
            .forEach { TacoBoyPrefs.setButtonBindingSource(context, system, it, previousSourceForTarget) }
    }

    fun setSource(context: Context, system: GameSystem, target: Target, sourceKeyCode: Int) {
        resolveConflicts(context, system, target, sourceKeyCode)
        TacoBoyPrefs.setButtonBindingSource(context, system, target, sourceKeyCode)
    }

    fun clearSource(context: Context, system: GameSystem, target: Target) {
        resolveConflicts(context, system, target, defaultSource(target))
        TacoBoyPrefs.clearButtonBindingSource(context, system, target)
    }

    fun isCustomized(context: Context, system: GameSystem, target: Target): Boolean {
        return TacoBoyPrefs.getButtonBindingSource(context, system, target) != null
    }

    /** Resets every system's bindings back to DEFAULT_SOURCE — used when the Pocket Taco
     *  connects (see TacoBoyActivity.inputDeviceListener), since that default is already
     *  the confirmed-correct mapping for this exact hardware. Not scoped to one system
     *  since a stale customization (e.g. left over from a different controller) could
     *  exist for any of them. */
    fun resetAllToDefaults(context: Context) {
        GameSystem.entries.forEach { system ->
            Target.entries.forEach { target -> clearSource(context, system, target) }
        }
    }

    /** Physical keycode -> RetroPad target keycode to send to the core, or null if
     *  `sourceKeyCode` isn't currently bound to anything for this system. */
    fun resolveTarget(context: Context, system: GameSystem, sourceKeyCode: Int): Int? {
        return Target.entries.firstOrNull { getSource(context, system, it) == sourceKeyCode }?.keyCode
    }

    /** Human-readable label for a physical keycode, for the binding editor's "currently
     *  assigned" display — falls back to Android's own key name for anything unusual. */
    fun describeSource(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> "Button A"
            KeyEvent.KEYCODE_BUTTON_B -> "Button B"
            KeyEvent.KEYCODE_BUTTON_X -> "Button X"
            KeyEvent.KEYCODE_BUTTON_Y -> "Button Y"
            KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
            KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
            KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
            KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
            KeyEvent.KEYCODE_BUTTON_START -> "Start"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
            KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
            KeyEvent.KEYCODE_DPAD_UP -> "D-Pad Up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "D-Pad Down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "D-Pad Left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "D-Pad Right"
            else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        }
    }
}
