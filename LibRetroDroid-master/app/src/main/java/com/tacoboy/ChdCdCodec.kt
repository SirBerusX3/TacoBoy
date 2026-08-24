package com.tacoboy

import com.github.luben.zstd.Zstd
import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream
import java.util.zip.Inflater

/**
 * Decompresses one CHD v5 hunk stored under a `cd*` (CD-frontend) codec slot --
 * `cdlz`/`cdzl`/`cdzs`, wrapping LZMA/zlib/Zstandard respectively -- into raw CD frame
 * bytes. Ported from `cdlz_codec_decompress` in libchdr's `libchdr_lzma.c` (the only `cd*`
 * codec whose implementation is actually vendored in this repo's libchdr copy; `cdzl`/
 * `cdzs` share the exact same frame layout per libchdr's source, just swapping the base
 * codec -- confirmed structurally, not assumed, since chd.h lists all three as siblings
 * built from the same `CD_FRAME_SIZE`/`CD_MAX_SECTOR_DATA` constants).
 *
 * Each hunk is `frames` = hunkBytes/2448 CD frames. The compressed blob is: an ECC bitmap
 * ((frames+7)/8 bytes, one bit per frame -- flags whether that frame's sync header + ECC
 * were omitted and need regenerating), a 2-or-3-byte big-endian length of the base-codec
 * stream, then the base-codec-compressed sector data (frames*2352 bytes decompressed),
 * then (unused here) zlib-compressed subcode data filling out the rest of `complen`.
 *
 * Deliberately does NOT regenerate sync headers or ECC/EDC (the `WANT_RAW_DATA_SECTOR`
 * path in libchdr): verified against real device bytes that the sync/ECC region occupies
 * only frame offsets [0..11] (sync) and the EDC/ECC tail -- never the mode byte (offset
 * 15), subheader (16-23), or the 2048-byte user data area PS1 identification actually
 * reads, so those bytes come out correct straight from base-codec decompression whether
 * or not the ECC bit is set. Subcode is decompressed even less: never even reached, since
 * PS1 identification only needs sector user data.
 */
object ChdCdCodec {
    private const val FRAME_SIZE = 2448
    private const val SECTOR_DATA_SIZE = 2352

    /** Decompresses [src] (the exact `length` bytes a ChdHunkMapEntry points at) into a
     * `hunkBytes`-sized buffer of raw CD frames (subcode portion left zero-filled). */
    fun decompress(codec: String, src: ByteArray, hunkBytes: Int): ByteArray {
        val frames = hunkBytes / FRAME_SIZE
        val complenBytes = if (hunkBytes < 65536) 2 else 3
        val eccBytes = (frames + 7) / 8
        val headerBytes = eccBytes + complenBytes

        var complenBase = ((src[eccBytes].toInt() and 0xFF) shl 8) or (src[eccBytes + 1].toInt() and 0xFF)
        if (complenBytes > 2) {
            complenBase = (complenBase shl 8) or (src[eccBytes + 2].toInt() and 0xFF)
        }

        val sectorBytes = decompressBase(codec, src, headerBytes, complenBase, frames * SECTOR_DATA_SIZE)

        val dest = ByteArray(hunkBytes)
        for (frame in 0 until frames) {
            System.arraycopy(sectorBytes, frame * SECTOR_DATA_SIZE, dest, frame * FRAME_SIZE, SECTOR_DATA_SIZE)
        }
        return dest
    }

    private fun decompressBase(codec: String, src: ByteArray, offset: Int, length: Int, destLen: Int): ByteArray {
        return when (codec) {
            "cdlz" -> decompressLzma(src, offset, length, destLen)
            "cdzl" -> decompressZlib(src, offset, length, destLen)
            "cdzs" -> decompressZstd(src, offset, length, destLen)
            else -> error("Unsupported CD codec: $codec")
        }
    }

    /** chdman always encodes with LZMA SDK level 9, which fixes lc=3/lp=0/pb=2 (properties
     * byte 0x5D) -- these aren't stored per-file, only derivable by replicating the
     * encoder's own defaults, same as libchdr's `lzma_codec_init` does. */
    private fun decompressLzma(src: ByteArray, offset: Int, length: Int, destLen: Int): ByteArray {
        val dictSize = lzmaDictSize(destLen)
        val propsByte = ((2 * 5 + 0) * 9 + 3).toByte() // pb=2, lp=0, lc=3
        val input = ByteArrayInputStream(src, offset, length)
        LZMAInputStream(input, destLen.toLong(), propsByte, dictSize).use { lzma ->
            val out = ByteArray(destLen)
            readFully(lzma, out)
            return out
        }
    }

    /** Mirrors `LzmaEncProps_Normalize` from the 7-Zip SDK for level 9 with
     * `reduceSize` = the decompressed sector-data length -- the smallest dictionary the
     * real encoder would have picked, which is also the smallest window that can decode
     * it correctly (a larger one would also work, just wastes memory). */
    private fun lzmaDictSize(reduceSize: Int): Int {
        var dictSize = 1L shl 26
        if (dictSize > reduceSize) {
            for (i in 11..30) {
                val small = 2L shl i
                if (reduceSize <= small) {
                    dictSize = small
                    break
                }
                val large = 3L shl i
                if (reduceSize <= large) {
                    dictSize = large
                    break
                }
            }
        }
        return dictSize.toInt()
    }

    /** Unverified against real data -- zero files in the user's own 209-file PS1 library
     * use `cdzl` (170 use cdzs, 39 use cdlz, per the survey in CHANGELOG.md), but kept for
     * completeness since other PS1 CHDs in the wild may. libchdr's zlib codec uses raw
     * (headerless) deflate, matching `inflateInit2` with a negative window-bits value. */
    private fun decompressZlib(src: ByteArray, offset: Int, length: Int, destLen: Int): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(src, offset, length)
        val out = ByteArray(destLen)
        var written = 0
        while (written < destLen) {
            val n = inflater.inflate(out, written, destLen - written)
            if (n == 0 && inflater.finished()) break
            written += n
        }
        inflater.end()
        check(written == destLen) { "zlib hunk decompressed to $written bytes, expected $destLen" }
        return out
    }

    private fun decompressZstd(src: ByteArray, offset: Int, length: Int, destLen: Int): ByteArray {
        val out = ByteArray(destLen)
        val written = Zstd.decompressByteArray(out, 0, destLen, src, offset, length)
        check(written == destLen.toLong()) { "zstd hunk decompressed to $written bytes, expected $destLen" }
        return out
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) error("LZMA stream ended after $offset of ${buffer.size} bytes")
            offset += read
        }
    }
}
