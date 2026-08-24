package com.tacoboy

/**
 * How the bound turbo key behaves — deliberately the same three states as
 * [FastForwardMode], because it is the same question about the same kind of key and
 * answering it differently in two places would be gratuitous.
 *
 * Off means the key (if any is bound) does nothing, and TacoBoyActivity short-circuits
 * before even looking one up. Hold rapid-fires only while the key is held, which suits a
 * boss fight. Toggle latches on each press, which suits a shmup you are an hour into and
 * would rather not hold a second button down for.
 */
enum class TurboMode {
    OFF, HOLD, TOGGLE
}
