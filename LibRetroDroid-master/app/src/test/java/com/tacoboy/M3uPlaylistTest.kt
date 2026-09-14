package com.tacoboy

import org.junit.Assert.assertEquals
import org.junit.Test

/** Byte-for-byte shapes of the user's own playlists, read off the phone. */
class M3uPlaylistTest {
    /** The user's files end lines "\r\r\n", which left a stray \r on every name in Genesis Plus GX. */
    @Test
    fun `double carriage returns are all removed`() {
        val text = "Night Trap (USA) (Disc 1).chd\r\r\nNight Trap (USA) (Disc 2).chd"
        assertEquals(listOf("Night Trap (USA) (Disc 1).chd", "Night Trap (USA) (Disc 2).chd"), M3uPlaylist.parse(text))
    }

    @Test
    fun `comments, blank lines and trailing spaces are ignored`() {
        val text = "#EXTM3U\n\nGame (Disc 1).chd   \n  \n#note\nGame (Disc 2).chd\n"
        assertEquals(listOf("Game (Disc 1).chd", "Game (Disc 2).chd"), M3uPlaylist.parse(text))
    }

    @Test
    fun `a rendered playlist is plain lines that parse back unchanged`() {
        val entries = listOf("Final Fantasy VII (USA) (Disc 1).chd", "Final Fantasy VII (USA) (Disc 2).chd")
        assertEquals("Final Fantasy VII (USA) (Disc 1).chd\nFinal Fantasy VII (USA) (Disc 2).chd\n", M3uPlaylist.render(entries))
        assertEquals(entries, M3uPlaylist.parse(M3uPlaylist.render(entries)))
    }

    @Test
    fun `disc labels come from the filename, keeping tags after the disc number`() {
        assertEquals("Disc 2", M3uPlaylist.discLabel("Final Fantasy VII (USA) (Disc 2).chd", 1))
        assertEquals("Disc 1 (Allies)", M3uPlaylist.discLabel("Command & Conquer - Red Alert (USA) (Disc 1) (Allies).chd", 0))
        assertEquals("Disc 2", M3uPlaylist.discLabel("Driver 2 (USA) (Rev 1) (Disc 2).chd", 1))
        assertEquals("Disc 3: side-b", M3uPlaylist.discLabel("side-b.chd", 2))
    }
}
