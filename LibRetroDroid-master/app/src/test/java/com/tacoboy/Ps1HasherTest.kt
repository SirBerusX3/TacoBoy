package com.tacoboy

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * End-to-end test of the whole PS1 identification chain (ChdHeader -> ChdHunkMap ->
 * ChdCdCodec -> ChdDisc -> Iso9660 -> Ps1Hasher) against real bytes pulled from the
 * user's own Crash Bandicoot (USA).chd this session: the header's fixed fields (matching
 * ChdHeaderTest/ChdHunkMapTest), the same compressed hunk map, plus the two additional
 * ranges of the real file this test actually needs -- hunk 2's compressed bytes (contains
 * the ISO9660 PVD/root directory/SYSTEM.CNF) and the 18 contiguous hunks spanning the
 * cdlz-compressed boot executable (SCUS_949.00).
 *
 * The expected hash was computed independently in Python (a from-scratch reimplementation
 * of the whole chain: bit reader, Huffman decoder, hunk-map RLE decode, raw-LZMA1 sector
 * decompression via `lzma.LZMADecompressor(format=FORMAT_RAW, ...)`, CD frame/ISO9660/
 * SYSTEM.CNF parsing) run against these same real bytes before any Kotlin code existed --
 * matching it here is proof this port is correct end to end, not just plausible-looking.
 * A strong internal cross-check along the way: the executable's declared size (288768,
 * read from its PS-X EXE header) plus 2048 equals the ISO9660 directory's own file-length
 * field (290816) exactly, confirming the offset math throughout is right independent of
 * the Python comparison.
 */
class Ps1HasherTest {

    private val mapOffset = 463951824L
    private val mapHeaderBase64 = "AAH9XgAAAAAA6UnbDwAAAA=="

    private val hunk2Offset = 4030L
    private val exeHunksOffset = 140055229L

    private fun headerBytes(): ByteArray {
        val raw = ByteArray(124)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        raw[0] = 'M'.code.toByte(); raw[1] = 'C'.code.toByte(); raw[2] = 'o'.code.toByte(); raw[3] = 'm'.code.toByte()
        raw[4] = 'p'.code.toByte(); raw[5] = 'r'.code.toByte(); raw[6] = 'H'.code.toByte(); raw[7] = 'D'.code.toByte()
        buffer.putInt(8, 124) // header length
        buffer.putInt(12, 5) // version
        "cdlz".toByteArray(Charsets.US_ASCII).copyInto(raw, 16)
        "cdzl".toByteArray(Charsets.US_ASCII).copyInto(raw, 20)
        "cdfl".toByteArray(Charsets.US_ASCII).copyInto(raw, 24)
        buffer.putLong(32, 657885312L) // logicalbytes
        buffer.putLong(40, mapOffset)
        buffer.putLong(48, 124L) // metaoffset
        buffer.putInt(56, 19584) // hunkbytes
        buffer.putInt(60, 2448) // unitbytes
        return raw
    }

    private fun realSource(): ChdRandomAccess {
        val mapHeaderBytes = Base64.getDecoder().decode(mapHeaderBase64)
        val compressedMapBytes = resource("crash_bandicoot_hunk_map.bin")
        val hunk2Bytes = resource("crash_bandicoot_hunk2_compressed.bin")
        val exeHunksBytes = resource("crash_bandicoot_exe_hunks_compressed.bin")
        val header = headerBytes()

        return ChdRandomAccess { offset, length ->
            when {
                offset == 0L -> header.copyOfRange(0, length)
                offset == mapOffset -> mapHeaderBytes.copyOfRange(0, length)
                offset == mapOffset + 16 -> compressedMapBytes.copyOfRange(0, length)
                offset == hunk2Offset -> hunk2Bytes.copyOfRange(0, length)
                offset >= exeHunksOffset && offset + length <= exeHunksOffset + exeHunksBytes.size -> {
                    val start = (offset - exeHunksOffset).toInt()
                    exeHunksBytes.copyOfRange(start, start + length)
                }
                else -> error("Unexpected read at offset $offset length $length in test fixture")
            }
        }
    }

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("chd_test_data/$name")!!.use { it.readBytes() }

    @Test
    fun `computes the same RA PS1 hash as an independent Python reference over real disc bytes`() {
        val hash = Ps1Hasher.hash(realSource())
        assertEquals("35386fd0891e594c9b6d8a4a5baa0027", hash)
    }
}
