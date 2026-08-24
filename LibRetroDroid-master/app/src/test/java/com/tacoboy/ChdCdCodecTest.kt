package com.tacoboy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies both CD-frontend codecs actually present in the user's real PS1 library (170 of
 * 209 files use `cdzs`/Zstandard, 39 use `cdlz`/LZMA -- confirmed by scanning the whole
 * library, see CHANGELOG.md) against real compressed hunk bytes pulled from the user's own
 * device this session. `cdlz` is additionally covered end-to-end by Ps1HasherTest; this
 * test covers `cdzs` directly since nothing else exercises it -- Alien Trilogy (USA).chd's
 * very first hunk, decompressed and checked against a Python zstandard-library reference
 * decode of the same real bytes.
 */
class ChdCdCodecTest {
    private val hunkBytes = 19584

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("chd_test_data/$name")!!.use { it.readBytes() }

    @Test
    fun `decompresses a real cdzs hunk to the expected CD frame layout`() {
        val src = resource("alien_trilogy_hunk0_cdzs.bin")
        val dest = ChdCdCodec.decompress("cdzs", src, hunkBytes)

        assertEquals(hunkBytes, dest.size)

        // Frame 0 = LBA 0. Mode byte (offset 15) and Mode 2 subheader (16-23),
        // cross-checked against a `zstandard`-library reference decode of these same bytes.
        assertEquals(2, dest[15].toInt() and 0xFF)
        assertArrayEquals(
            byteArrayOf(0, 0, 8, 0, 0, 0, 8, 0),
            dest.copyOfRange(16, 24),
        )
    }
}
