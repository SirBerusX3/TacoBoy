package com.tacoboy

import com.tacoboy.SegaCdBios.Region
import org.junit.Assert.assertEquals
import org.junit.Test

/** Header fields copied from the user's three real dumps (eu_mcd1_9210, jp_mcd1_9112,
 *  us_scd1_9210): system name at 0x100, ROM name at 0x120, region letter at 0x1F0. */
class SegaCdBiosTest {
    private fun header(system: String = "SEGA MEGA DRIVE ", name: String = "SEGA-CD BOOT ROM", region: String = "U") =
        ByteArray(SegaCdBios.HEADER_BYTES).also { bytes ->
            system.toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0x100)
            name.toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0x120)
            region.padEnd(16).toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0x1F0)
        }

    private val size128k = 0x20000L

    @Test
    fun `the three real dumps are recognised with their regions`() {
        assertEquals(setOf(Region.USA), SegaCdBios.regions(size128k, header(name = "SEGA-CD BOOT ROM", region = "U")))
        assertEquals(setOf(Region.EUROPE), SegaCdBios.regions(size128k, header(name = "MEGA-CD BOOT ROM", region = "E")))
        assertEquals(setOf(Region.JAPAN), SegaCdBios.regions(size128k, header(name = "MEGA-CD BOOT ROM", region = "J")))
    }

    @Test
    fun `Genesis branding, variant hardware and hex region bits are recognised`() {
        assertEquals(setOf(Region.USA), SegaCdBios.regions(size128k, header(system = "SEGA GENESIS    ", region = "4")))
        assertEquals(setOf(Region.JAPAN), SegaCdBios.regions(0x80000L, header(name = "CDX BOOT ROM    ", region = "1")))
        assertEquals(setOf(Region.JAPAN, Region.USA, Region.EUROPE), SegaCdBios.regions(size128k, header(region = "JUE")))
        assertEquals(setOf(Region.USA, Region.EUROPE), SegaCdBios.regions(size128k, header(region = "C")))
    }

    /** A PS1 BIOS is 512 KB too, and must never be offered as a Sega CD one. */
    @Test
    fun `anything without the boot ROM header is not a Sega CD BIOS`() {
        assertEquals(emptySet<Region>(), SegaCdBios.regions(0x80000L, ByteArray(SegaCdBios.HEADER_BYTES)))
        assertEquals(emptySet<Region>(), SegaCdBios.regions(size128k, header(name = "SONIC THE HEDGEH")))
        assertEquals(emptySet<Region>(), SegaCdBios.regions(size128k, header(system = "SEGA SATURN     ")))
        assertEquals(emptySet<Region>(), SegaCdBios.regions(0x40000L, header()))
        assertEquals(emptySet<Region>(), SegaCdBios.regions(size128k, ByteArray(16)))
    }

    @Test
    fun `each region gets its own dump, and a region-free one fills the gaps`() {
        val detected = mapOf(
            "us.bin" to setOf(Region.USA),
            "free.bin" to setOf(Region.USA, Region.EUROPE, Region.JAPAN),
        )
        assertEquals(
            mapOf(Region.USA to "us.bin", Region.EUROPE to "free.bin", Region.JAPAN to "free.bin"),
            SegaCdBios.assign(detected, preferred = null),
        )
    }

    @Test
    fun `the preferred file wins within its region, otherwise the first by name`() {
        val detected = mapOf("b_us.bin" to setOf(Region.USA), "a_us.bin" to setOf(Region.USA), "eu.bin" to setOf(Region.EUROPE))
        assertEquals(mapOf(Region.USA to "a_us.bin", Region.EUROPE to "eu.bin"), SegaCdBios.assign(detected, preferred = null))
        assertEquals(mapOf(Region.USA to "b_us.bin", Region.EUROPE to "eu.bin"), SegaCdBios.assign(detected, preferred = "b_us.bin"))
    }
}
