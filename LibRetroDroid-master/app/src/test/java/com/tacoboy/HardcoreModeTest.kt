package com.tacoboy

import org.junit.Assert.assertEquals
import org.junit.Test

/** The direction matters: RA allows dropping out of hardcore mid-session and auto-fails
 *  entering it without a reset, so the two changes must never be handled alike. */
class HardcoreModeTest {
    @Test
    fun `turning hardcore on under a running casual game resets it`() {
        assertEquals(HardcoreTransition.RESET_INTO_HARDCORE, hardcoreTransition(sessionHardcore = false, preferenceHardcore = true))
    }

    @Test
    fun `turning hardcore off under a running hardcore game needs no reset`() {
        assertEquals(HardcoreTransition.DROP_TO_CASUAL, hardcoreTransition(sessionHardcore = true, preferenceHardcore = false))
    }

    @Test
    fun `an unchanged preference does nothing`() {
        assertEquals(HardcoreTransition.NONE, hardcoreTransition(sessionHardcore = true, preferenceHardcore = true))
        assertEquals(HardcoreTransition.NONE, hardcoreTransition(sessionHardcore = false, preferenceHardcore = false))
    }
}
