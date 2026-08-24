package com.tacoboy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Base64

/**
 * Verifies ChdHunkMap.decode against the real hunk map of the same file ChdHeaderTest
 * uses (Crash Bandicoot (USA).chd), pulled from the user's device this session. The map
 * header (16 bytes at mapOffset) is small enough to inline; the compressed map itself
 * (130398 bytes) is a binary test resource (chd_test_data/crash_bandicoot_hunk_map.bin)
 * since it's too large for a Kotlin string constant. Expected entry values were computed
 * independently in Python before this test was written (see CHANGELOG.md's 2026-08-15
 * PS1 entry) -- decode() succeeding at all already means its internal CRC16 check
 * against the map's own embedded checksum passed, which is strong proof by itself; the
 * per-entry assertions catch anything CRC16 alone wouldn't (e.g. right bytes, wrong
 * field mapping in a way that still happens to checksum correctly -- vanishingly
 * unlikely, but cheap to also check directly).
 */
class ChdHunkMapTest {

    private val mapHeaderBase64 = "AAH9XgAAAAAA6UnbDwAAAA=="

    private val header = ChdHeader(
        compression = listOf("cdlz", "cdzl", "cdfl", ""),
        logicalBytes = 657885312L,
        mapOffset = 463951824L,
        metaOffset = 124L,
        hunkBytes = 19584,
        unitBytes = 2448,
    )

    private fun realSource(): ChdRandomAccess {
        val mapHeaderBytes = Base64.getDecoder().decode(mapHeaderBase64)
        val compressedMapBytes = javaClass.classLoader!!
            .getResourceAsStream("chd_test_data/crash_bandicoot_hunk_map.bin")!!
            .use { it.readBytes() }

        return ChdRandomAccess { offset, length ->
            when (offset) {
                header.mapOffset -> mapHeaderBytes.copyOfRange(0, length)
                header.mapOffset + 16 -> compressedMapBytes.copyOfRange(0, length)
                else -> error("Unexpected read at offset $offset in test fixture")
            }
        }
    }

    @Test
    fun `decodes a real v5 hunk map and passes its own CRC16 check`() {
        val entries = ChdHunkMap.decode(realSource(), header)
        assertNotNull(entries)
    }

    @Test
    fun `hunk count matches logicalBytes divided by hunkBytes, rounded up`() {
        val entries = ChdHunkMap.decode(realSource(), header)!!
        assertEquals(33593, entries.size)
    }

    @Test
    fun `first five entries match the independently-computed Python reference`() {
        val entries = ChdHunkMap.decode(realSource(), header)!!
        val expected = listOf(
            ChdHunkMapEntry(0, 1533, 233L),
            ChdHunkMapEntry(0, 2264, 1766L),
            ChdHunkMapEntry(0, 570, 4030L),
            ChdHunkMapEntry(0, 4005, 4600L),
            ChdHunkMapEntry(0, 6561, 8605L),
        )
        assertEquals(expected, entries.take(5))
    }

    @Test
    fun `last entry's data ends exactly where the compressed map begins`() {
        val entries = ChdHunkMap.decode(realSource(), header)!!
        val last = entries.last()
        assertEquals(ChdHunkMapEntry(1, 87, 463951737L), last)
        assertEquals(header.mapOffset, last.offset + last.length)
    }
}
