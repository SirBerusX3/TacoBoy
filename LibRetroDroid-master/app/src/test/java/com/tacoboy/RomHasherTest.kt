package com.tacoboy

import org.junit.Assert.assertEquals
import org.junit.Test

/** Lynx header detection, checked against the vendored rcheevos rc_hash_lynx()
 *  (rhash/hash_rom.c) rather than against what a .lnx header is documented to be.
 *  The hash only has value if it matches RetroAchievements byte for byte, so the
 *  quirks below are matched deliberately and must not be "corrected":
 *
 *    if (iterator->buffer_size > 64 && memcmp(&iterator->buffer[0], "LYNX", 5) == 0)
 *      return rc_hash_unheadered_iterator_buffer(hash, iterator, 64);
 *
 *  Note the five-byte compare against a four-character literal, and the strict
 *  greater-than on a file exactly the size of the header. */
class RomHasherTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val headered = bytes(0x4C, 0x59, 0x4E, 0x58, 0x00) // "LYNX\0"

    @Test
    fun `headered file skips 64 bytes`() {
        assertEquals(64, RomHasher.lynxHeaderSize(fileLength = 128, leadingBytes = headered))
    }

    @Test
    fun `unheadered file skips nothing`() {
        val raw = bytes(0x00, 0x01, 0x02, 0x03, 0x04)
        assertEquals(0, RomHasher.lynxHeaderSize(fileLength = 128, leadingBytes = raw))
    }

    /** rcheevos compares five bytes, so a file beginning with the four characters
     *  "LYNX" followed by anything other than NUL is NOT treated as headered. A
     *  four-byte compare would skip 64 bytes here and produce a hash RA never
     *  computes, which reads as "not recognized" rather than as a bug. */
    @Test
    fun `LYNX without the trailing NUL is not a header`() {
        val nearMiss = bytes(0x4C, 0x59, 0x4E, 0x58, 0x21) // "LYNX!"
        assertEquals(0, RomHasher.lynxHeaderSize(fileLength = 128, leadingBytes = nearMiss))
    }

    /** `buffer_size > 64`, not `>=`. A file that is nothing but a header has no
     *  content to hash, and rcheevos leaves it alone rather than hashing zero bytes. */
    @Test
    fun `file exactly the size of the header is left alone`() {
        assertEquals(0, RomHasher.lynxHeaderSize(fileLength = 64, leadingBytes = headered))
    }

    @Test
    fun `file smaller than the header is left alone`() {
        assertEquals(0, RomHasher.lynxHeaderSize(fileLength = 32, leadingBytes = headered))
    }

    /** A truncated read must not be trusted: fewer bytes than the magic means the
     *  magic was never confirmed, so the safe answer is "no header". */
    @Test
    fun `short read is treated as no header`() {
        val partial = bytes(0x4C, 0x59, 0x4E) // "LYN", stream ended early
        assertEquals(0, RomHasher.lynxHeaderSize(fileLength = 128, leadingBytes = partial))
    }

    @Test
    fun `empty read is treated as no header`() {
        assertEquals(0, RomHasher.lynxHeaderSize(fileLength = 128, leadingBytes = ByteArray(0)))
    }
}
