package com.tacoboy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64

/**
 * Verifies ChdHeader.parse against the actual first 124 bytes of a real PS1 CHD file
 * pulled from the user's device this session (Crash Bandicoot (USA).chd), independently
 * cross-checked in Python before this test was written -- not synthetic/hand-built bytes.
 * See CHANGELOG.md's 2026-08-15 PS1 entry for the full verification trail.
 */
class ChdHeaderTest {

    private val realHeaderBase64 =
        "TUNvbXBySEQAAAB8AAAABWNkbHpjZHpsY2RmbAAAAAAAAAAAJzaIgAAAAAAbp1fQAAAAAAAAAHwAAEyAAAAJkCdu8pCdt1IRmakriQLFPEqzPrqsc8fNiUInpUo2oWXHRjccxjKDEDsAAAAAAAAAAAAAAAAAAAAAAAAAAA=="

    private fun realHeaderBytes() = Base64.getDecoder().decode(realHeaderBase64)

    @Test
    fun `parses a real v5 CHD header correctly`() {
        val header = ChdHeader.parse(ByteArrayInputStream(realHeaderBytes()))

        assertNotNull(header)
        header!!
        assertEquals(listOf("cdlz", "cdzl", "cdfl", ""), header.compression)
        assertEquals(657885312L, header.logicalBytes)
        assertEquals(463951824L, header.mapOffset)
        assertEquals(124L, header.metaOffset)
        assertEquals(19584, header.hunkBytes)
        assertEquals(2448, header.unitBytes)
    }

    @Test
    fun `hunk bytes is always a whole multiple of the CD frame size`() {
        val header = ChdHeader.parse(ByteArrayInputStream(realHeaderBytes()))!!

        val cdFrameSize = 2352 + 96
        assertEquals(0, header.hunkBytes % cdFrameSize)
        assertEquals(8, header.hunkBytes / cdFrameSize)
    }

    @Test
    fun `rejects a file with the wrong magic`() {
        val bytes = realHeaderBytes()
        bytes[0] = 'X'.code.toByte()
        assertNull(ChdHeader.parse(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `rejects a truncated header`() {
        val bytes = realHeaderBytes().copyOfRange(0, 50)
        assertNull(ChdHeader.parse(ByteArrayInputStream(bytes)))
    }
}
