package com.tacoboy

/**
 * How hard the on-screen pad buzzes on a press. Replaces the plain on/off toggle the pad
 * shipped with — "on" turned out to be the wrong question, since what people actually
 * disagree about is intensity rather than existence.
 *
 * Two values per level because devices vary: [amplitude] is used where the vibrator supports
 * amplitude control (API 26+ hardware that reports `hasAmplitudeControl`), and [durationMs]
 * carries the difference everywhere else — on an older or simpler motor the levels differ
 * only by how long the pulse lasts, which is coarser but still distinguishable.
 *
 * Durations are deliberately short. This fires on every button press and every change of
 * D-pad direction, so anything longer than a tick becomes a continuous rumble during normal
 * play rather than feedback.
 */
enum class HapticStrength(
    val prefValue: String,
    /** 1..255 where the hardware supports it; ignored at OFF. */
    val amplitude: Int,
    val durationMs: Long,
) {
    OFF("off", 0, 0),
    LIGHT("light", 70, 8),
    MEDIUM("medium", 140, 14),
    STRONG("strong", 255, 22);

    companion object {
        val DEFAULT = MEDIUM

        fun fromPrefValue(value: String?): HapticStrength =
            entries.firstOrNull { it.prefValue == value } ?: DEFAULT
    }
}
