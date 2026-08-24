package com.tacoboy

import org.junit.Assert.assertEquals
import org.junit.Test

/** raConsoleId values verified directly against the vendored rcheevos rc_consoles.h
 *  (RC_CONSOLE_* constants), not guessed -- a wrong ID would silently break any
 *  RetroAchievements API call keyed off console rather than game ID. */
class GameSystemTest {
    @Test
    fun `raConsoleId matches rc_consoles h`() {
        assertEquals(3, GameSystem.SNES.raConsoleId)
        assertEquals(4, GameSystem.GAME_BOY.raConsoleId)
        assertEquals(5, GameSystem.GBA.raConsoleId)
        assertEquals(6, GameSystem.GAME_BOY_COLOR.raConsoleId)
        assertEquals(12, GameSystem.PS1.raConsoleId)
    }
}
