package com.tacoboy

/**
 * How the bound fast-forward key behaves. Off means the key (if any is bound) does
 * nothing -- TacoBoyActivity.handleFastForwardKey short-circuits before even checking
 * for a bound key when this is selected. Hold speeds up only while held, Toggle flips
 * a persistent on/off state on each press. See GLRetroView.frameSpeed for the actual
 * speed-up mechanism (already existed in the library, unused until this feature).
 */
enum class FastForwardMode {
    OFF, HOLD, TOGGLE
}
