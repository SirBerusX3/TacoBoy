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
        assertEquals(7, GameSystem.NES.raConsoleId)
        assertEquals(9, GameSystem.SEGA_CD.raConsoleId)
    }

    @Test
    fun `chd is shared by PS1 and Sega CD, and nothing else is shared`() {
        assertEquals(listOf(GameSystem.PS1, GameSystem.SEGA_CD), GameSystem.candidatesForFileName("Night Trap (USA).CHD"))
        assertEquals(listOf(GameSystem.NES), GameSystem.candidatesForFileName("10-Yard Fight.nes"))
        assertEquals(emptyList<GameSystem>(), GameSystem.candidatesForFileName("notes.txt"))
    }

    private val both = listOf(GameSystem.PS1, GameSystem.SEGA_CD)

    @Test
    fun `a disc belongs to the system whose folder holds it`() {
        val folders = mapOf(GameSystem.PS1 to "primary:Emulation/PS1", GameSystem.SEGA_CD to "primary:Emulation/SEGACD")
        assertEquals(GameSystem.SEGA_CD, GameSystem.systemForDocument("primary:Emulation/SEGACD/Dragon's Lair (USA).chd", both, folders))
        assertEquals(GameSystem.PS1, GameSystem.systemForDocument("primary:Emulation/PS1/007 (USA).chd", both, folders))
        // Subfolders count, as the library scan is recursive.
        assertEquals(GameSystem.SEGA_CD, GameSystem.systemForDocument("primary:Emulation/SEGACD/Night Trap (USA)/Disc 1.chd", both, folders))
    }

    /** "PS1" must not claim "PS10": containment only at a path boundary. */
    @Test
    fun `a folder name that is only a prefix does not claim a file`() {
        val folders = mapOf(GameSystem.PS1 to "primary:Emulation/PS1")
        assertEquals(null, GameSystem.systemForDocument("primary:Emulation/PS10/x.chd", both, folders))
    }

    @Test
    fun `the deepest folder wins, and a whole-volume folder counts`() {
        val nested = mapOf(GameSystem.PS1 to "primary:", GameSystem.SEGA_CD to "primary:Emulation/SEGACD")
        assertEquals(GameSystem.SEGA_CD, GameSystem.systemForDocument("primary:Emulation/SEGACD/x.chd", both, nested))
        assertEquals(GameSystem.PS1, GameSystem.systemForDocument("primary:Emulation/Other/x.chd", both, nested))
    }

    @Test
    fun `no folder set means no answer from folders`() {
        assertEquals(null, GameSystem.systemForDocument("primary:Emulation/SEGACD/x.chd", both, emptyMap()))
    }

    /** The library's only way to switch system is the picker, so a system missing from its
     *  order would be unreachable, and one listed twice would show twice. */
    @Test
    fun `picker lists every system exactly once`() {
        assertEquals(GameSystem.entries.toSet(), GameSystem.PICKER_ORDER.toSet())
        assertEquals(GameSystem.entries.size, GameSystem.PICKER_ORDER.size)
    }
}
