package com.tacoboy

/**
 * MSB-first bit reader matching libchdr's bitstream.c exactly -- byte-for-byte verified
 * against a real CHD's hunk map via that map's own embedded CRC16 (see ChdHunkMap).
 * Bits are pulled from successive bytes of `data`, most-significant-bit first, into a
 * 32-bit shift-register buffer (kept in a Long here to avoid Kotlin Int's signedness
 * getting in the way of what's conceptually unsigned 32-bit arithmetic).
 */
class ChdBitReader(private val data: ByteArray) {
    private var buffer: Long = 0L
    private var bitsAvailable: Int = 0
    private var offset: Int = 0

    private fun fill(numBits: Int) {
        if (numBits > bitsAvailable) {
            while (bitsAvailable <= 24) {
                if (offset < data.size) {
                    val byte = data[offset].toLong() and 0xFF
                    buffer = (buffer or (byte shl (24 - bitsAvailable))) and 0xFFFFFFFFL
                }
                offset++
                bitsAvailable += 8
            }
        }
    }

    fun peek(numBits: Int): Int {
        if (numBits == 0) return 0
        fill(numBits)
        return ((buffer ushr (32 - numBits)) and ((1L shl numBits) - 1)).toInt()
    }

    fun remove(numBits: Int) {
        buffer = (buffer shl numBits) and 0xFFFFFFFFL
        bitsAvailable -= numBits
    }

    fun read(numBits: Int): Int {
        val value = peek(numBits)
        remove(numBits)
        return value
    }

    fun overflow(): Boolean = (offset - bitsAvailable / 8) > data.size
}
