package com.tacoboy

/**
 * One hunk's location and codec-slot type, decoded from a CHD v5 hunk map. `type` is
 * 0-3 (index into ChdHeader.compression), COMPRESSION_NONE, or COMPRESSION_SELF (which
 * ChdHunkMap already resolves down to an offset -- callers never see the pseudo-types).
 * COMPRESSION_PARENT is preserved as-is since this app never has a parent CHD to read
 * from (ROMs are always standalone files) -- if it's ever seen, the caller has nothing
 * to do with it but fail.
 */
data class ChdHunkMapEntry(val type: Int, val length: Int, val offset: Long)

/**
 * Random-access reads into a CHD file. SAF's InputStream is sequential-only, so real
 * usage backs this with a seekable FileChannel (see the eventual ChdReader); tests back
 * it with a plain in-memory buffer.
 */
fun interface ChdRandomAccess {
    fun read(offset: Long, length: Int): ByteArray
}

/**
 * Decodes a CHD v5 hunk map -- ported from decompress_v5_map in libchdr's
 * src/libchdr_chd.c, byte-for-byte. Verified against a real file: the reconstructed
 * map's own CRC16 (see crc16 below) matched the file's embedded checksum exactly on the
 * first real file tried, which only happens if every piece -- compression-type RLE
 * decoding, canonical Huffman code assignment, and the length/offset/crc bitstream
 * layout -- is correct, not just plausible-looking. `decode` re-checks this same CRC on
 * every call and returns null on mismatch, the same integrity guard libchdr itself
 * applies, rather than silently handing back a map that might be subtly wrong.
 */
object ChdHunkMap {
    private const val COMPRESSION_TYPE_0 = 0
    private const val COMPRESSION_TYPE_3 = 3
    private const val COMPRESSION_NONE = 4
    private const val COMPRESSION_SELF = 5
    private const val COMPRESSION_PARENT = 6
    private const val COMPRESSION_RLE_SMALL = 7
    private const val COMPRESSION_RLE_LARGE = 8
    private const val COMPRESSION_SELF_0 = 9
    private const val COMPRESSION_SELF_1 = 10
    private const val COMPRESSION_PARENT_SELF = 11
    private const val COMPRESSION_PARENT_0 = 12
    private const val COMPRESSION_PARENT_1 = 13

    fun decode(source: ChdRandomAccess, header: ChdHeader): List<ChdHunkMapEntry>? {
        val hunkCount = ((header.logicalBytes + header.hunkBytes - 1) / header.hunkBytes).toInt()

        val mapHeader = source.read(header.mapOffset, 16)
        val mapBytes = readU32(mapHeader, 0).toInt()
        val firstOffset = readU48(mapHeader, 4)
        val expectedCrc = readU16(mapHeader, 10)
        val lengthBits = mapHeader[12].toInt() and 0xFF
        val selfBits = mapHeader[13].toInt() and 0xFF
        val parentBits = mapHeader[14].toInt() and 0xFF

        val compressed = source.read(header.mapOffset + 16, mapBytes)
        val bits = ChdBitReader(compressed)
        val decoder = ChdHuffmanDecoder(numCodes = 16, maxBits = 8)
        decoder.importTreeRle(bits)

        val rawTypes = IntArray(hunkCount)
        var repCount = 0
        var lastComp = 0
        for (hunkNum in 0 until hunkCount) {
            if (repCount > 0) {
                rawTypes[hunkNum] = lastComp
                repCount--
            } else {
                when (val value = decoder.decodeOne(bits)) {
                    COMPRESSION_RLE_SMALL -> {
                        rawTypes[hunkNum] = lastComp
                        repCount = 2 + decoder.decodeOne(bits)
                    }
                    COMPRESSION_RLE_LARGE -> {
                        rawTypes[hunkNum] = lastComp
                        repCount = 2 + 16 + (decoder.decodeOne(bits) shl 4)
                        repCount += decoder.decodeOne(bits)
                    }
                    else -> {
                        lastComp = value
                        rawTypes[hunkNum] = value
                    }
                }
            }
        }

        val rawMap = ByteArray(hunkCount * 12)
        val entries = ArrayList<ChdHunkMapEntry>(hunkCount)
        var curOffset = firstOffset
        var lastSelf = 0L
        var lastParent = 0L
        for (hunkNum in 0 until hunkCount) {
            var type = rawTypes[hunkNum]
            var offset = curOffset
            var length = 0
            var crc = 0
            when (type) {
                in COMPRESSION_TYPE_0..COMPRESSION_TYPE_3 -> {
                    length = bits.read(lengthBits)
                    curOffset += length
                    crc = bits.read(16)
                }
                COMPRESSION_NONE -> {
                    length = header.hunkBytes
                    curOffset += length
                    crc = bits.read(16)
                }
                COMPRESSION_SELF -> {
                    lastSelf = bits.read(selfBits).toLong()
                    offset = lastSelf
                }
                COMPRESSION_PARENT -> {
                    lastParent = bits.read(parentBits).toLong()
                    offset = lastParent
                }
                COMPRESSION_SELF_1 -> {
                    lastSelf++
                    type = COMPRESSION_SELF
                    offset = lastSelf
                }
                COMPRESSION_SELF_0 -> {
                    type = COMPRESSION_SELF
                    offset = lastSelf
                }
                COMPRESSION_PARENT_SELF -> {
                    type = COMPRESSION_PARENT
                    lastParent = (hunkNum.toLong() * header.hunkBytes) / header.unitBytes
                    offset = lastParent
                }
                COMPRESSION_PARENT_1 -> {
                    lastParent += header.hunkBytes / header.unitBytes
                    type = COMPRESSION_PARENT
                    offset = lastParent
                }
                COMPRESSION_PARENT_0 -> {
                    type = COMPRESSION_PARENT
                    offset = lastParent
                }
                else -> return null // unrecognized/reserved compression type -- treat as corrupt, not crash
            }
            entries.add(ChdHunkMapEntry(type, length, offset))
            writeRawMapEntry(rawMap, hunkNum, type, length, offset, crc)
        }

        if (crc16(rawMap) != expectedCrc) return null
        return entries
    }

    private fun writeRawMapEntry(rawMap: ByteArray, hunkNum: Int, type: Int, length: Int, offset: Long, crc: Int) {
        val base = hunkNum * 12
        rawMap[base] = type.toByte()
        rawMap[base + 1] = (length ushr 16).toByte()
        rawMap[base + 2] = (length ushr 8).toByte()
        rawMap[base + 3] = length.toByte()
        for (i in 0 until 6) rawMap[base + 4 + i] = (offset ushr (8 * (5 - i))).toByte()
        rawMap[base + 10] = (crc ushr 8).toByte()
        rawMap[base + 11] = crc.toByte()
    }

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, MSB-first, no final XOR) -- the same
     *  algorithm libchdr's crc16() computes via a precomputed table; this is the
     *  mathematically equivalent bit-by-bit form, confirmed to produce identical output
     *  before use here rather than assumed. */
    private fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun readU32(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value
    }

    private fun readU48(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 6) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value
    }
}
