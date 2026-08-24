package com.tacoboy

import android.view.InputDevice
import android.view.KeyEvent

/**
 * Identifies the GameSir Pocket Taco among connected input devices. Confirmed via
 * `adb shell dumpsys input` against a real unit: it registers as
 * `Device N: GameSir-Pocket 1` (IsExternal: true) — the same name Android's Bluetooth
 * stack reports for the paired HID connection (`adb shell dumpsys bluetooth_manager`).
 * Matches by prefix, not exact string, since a firmware/unit revision could plausibly
 * change the trailing " 1".
 */
object PocketTacoDetector {
    private const val NAME_PREFIX = "GameSir-Pocket"

    fun isPocketTaco(device: InputDevice): Boolean {
        return device.name?.startsWith(NAME_PREFIX, ignoreCase = true) == true
    }

    /**
     * True if this event is the Pocket Taco's confirmed-spurious duplicate BACK signal
     * that rides along with its B button — confirmed via a live adb logcat capture
     * against the real device: every single B press delivers both KEYCODE_BUTTON_B
     * and this KEYCODE_BACK, same timestamp, same device (see CHANGELOG.md's "real
     * fix for wrong/broken button input" entry). Every screen that can receive a
     * physical key event from the Taco should swallow this — not just the game
     * screen — or an ordinary B press can silently back out of whatever's open.
     */
    fun isSpuriousBack(keyCode: Int, event: KeyEvent): Boolean {
        return keyCode == KeyEvent.KEYCODE_BACK && event.device?.let(::isPocketTaco) == true
    }
}
