package com.tacoboy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The two diagnostics lines that exist to make a 16 KB report checkable. Both are wrong in ways
 *  a 4 KB arm64 phone cannot show: there the page size is always 4 KB, and a failed ABI lookup
 *  falls back to the device ABI, which on that phone is also the app's. So they are tested here
 *  rather than trusted from how the About tab looks on the one device to hand. */
class DiagnosticsTest {
    @Test
    fun `page sizes read as kilobytes`() {
        assertEquals("4 KB", diagnosticsPageSizeLabel(4096))
        assertEquals("16 KB", diagnosticsPageSizeLabel(16384))
        assertEquals("64 KB", diagnosticsPageSizeLabel(65536))
    }

    /** sysconf returns -1 on failure; a report must say so rather than print "-1 bytes". */
    @Test
    fun `an unreadable page size says unknown`() {
        assertEquals("unknown", diagnosticsPageSizeLabel(-1))
        assertEquals("unknown", diagnosticsPageSizeLabel(0))
    }

    @Test
    fun `a page size that is not whole kilobytes is shown exactly`() {
        assertEquals("5000 bytes", diagnosticsPageSizeLabel(5000))
    }

    @Test
    fun `instruction-set directories map back to ABI names`() {
        assertEquals("arm64-v8a", nativeAbiForLibDir("arm64"))
        assertEquals("armeabi-v7a", nativeAbiForLibDir("arm"))
        assertEquals("x86_64", nativeAbiForLibDir("x86_64"))
        assertEquals("x86", nativeAbiForLibDir("x86"))
    }

    /** nativeLibraryDir is never guaranteed to end in a known directory; anything else must yield
     *  no answer, so the label falls back to the device ABI rather than inventing one. */
    @Test
    fun `an unrecognised directory yields no ABI`() {
        assertNull(nativeAbiForLibDir("lib"))
        assertNull(nativeAbiForLibDir(""))
    }

    @Test
    fun `native hardware shows the device ABI alone`() {
        assertEquals("arm64-v8a", diagnosticsAbiLabel(device = "arm64-v8a", app = "arm64-v8a"))
    }

    /** The case that motivated the line: an x86_64 16 KB emulator running TacoBoy's arm64 code
     *  through libndk_translation, which a bare "x86_64" would have hidden. */
    @Test
    fun `translated code is spelled out`() {
        assertEquals(
            "x86_64, running arm64-v8a translated",
            diagnosticsAbiLabel(device = "x86_64", app = "arm64-v8a"),
        )
    }

    @Test
    fun `an unknown app ABI falls back to the device ABI`() {
        assertEquals("x86_64", diagnosticsAbiLabel(device = "x86_64", app = null))
    }
}
