package com.tacoboy

/**
 * Canonical Huffman decoder for CHD's RLE-encoded tree format, ported from libchdr's
 * huffman.c (huffman_import_tree_rle / huffman_assign_canonical_codes /
 * huffman_build_lookup_table / huffman_decode_one). Decoder-only -- this app never
 * builds a tree from scratch, only imports one that's already embedded in a CHD's hunk
 * map. Verified against real CHD map data via that map's own embedded CRC16 (see
 * ChdHunkMap) -- a decode bug anywhere in here would produce a mismatching CRC, so a
 * matching one on the first real file tried is strong end-to-end proof this is right,
 * not just plausible-looking.
 */
class ChdHuffmanDecoder(private val numCodes: Int, private val maxBits: Int) {
    private val numBits = IntArray(numCodes)
    private val codeStart = IntArray(numCodes)
    private val lookup = IntArray(1 shl maxBits) // packed as (symbol shl 5) or codeLength

    fun importTreeRle(bits: ChdBitReader) {
        val fieldWidth = if (maxBits >= 16) 5 else if (maxBits >= 8) 4 else 3
        var curNode = 0
        while (curNode < numCodes) {
            var nodeBits = bits.read(fieldWidth)
            if (nodeBits != 1) {
                numBits[curNode++] = nodeBits
            } else {
                nodeBits = bits.read(fieldWidth)
                if (nodeBits == 1) {
                    numBits[curNode++] = nodeBits
                } else {
                    var repeatCount = bits.read(fieldWidth) + 3
                    check(repeatCount + curNode <= numCodes) { "RLE repeat count overruns the tree" }
                    while (repeatCount-- > 0) {
                        numBits[curNode++] = nodeBits
                    }
                }
            }
        }
        check(curNode == numCodes)
        assignCanonicalCodes()
        buildLookupTable()
        check(!bits.overflow()) { "bitstream overflow while importing huffman tree" }
    }

    private fun assignCanonicalCodes() {
        val bitHisto = IntArray(33)
        for (nb in numBits) {
            check(nb <= maxBits) { "code length exceeds maxBits" }
            if (nb in 0..32) bitHisto[nb]++
        }
        var curStart = 0
        for (codeLen in 32 downTo 1) {
            val nextStart = (curStart + bitHisto[codeLen]) ushr 1
            check(codeLen == 1 || nextStart * 2 == (curStart + bitHisto[codeLen])) {
                "internal inconsistency assigning canonical codes"
            }
            bitHisto[codeLen] = curStart
            curStart = nextStart
        }
        for (code in 0 until numCodes) {
            val nb = numBits[code]
            if (nb > 0) {
                codeStart[code] = bitHisto[nb]
                bitHisto[nb]++
            }
        }
    }

    private fun buildLookupTable() {
        for (code in 0 until numCodes) {
            val nb = numBits[code]
            if (nb > 0) {
                val value = (code shl 5) or (nb and 0x1F)
                val shift = maxBits - nb
                val start = codeStart[code] shl shift
                val end = ((codeStart[code] + 1) shl shift) - 1
                for (i in start..end) lookup[i] = value
            }
        }
    }

    fun decodeOne(bits: ChdBitReader): Int {
        val peeked = bits.peek(maxBits)
        val entry = lookup[peeked]
        bits.remove(entry and 0x1F)
        return entry ushr 5
    }
}
