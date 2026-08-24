package com.tacoboy

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses a CHD v5 container's fixed 124-byte header. Verified byte-for-byte against
 * header_read() in rtissera/libchdr (src/libchdr_chd.c) rather than assumed, then
 * cross-checked against a real PS1 CHD's actual header bytes pulled from the user's
 * device -- both agreed exactly (see CHANGELOG.md 2026-08-15 PS1 entry).
 *
 * v5 is the only version handled: it's what every chdman build has produced for years,
 * and the only version found across the user's real 209-file PS1 library (confirmed
 * live). Older CHD versions (1-4) use a different, larger header layout entirely.
 *
 * This is container-header parsing only -- locating and decoding actual hunk data is
 * separate, larger work: mapOffset points at the hunk map, which for a compressed CHD
 * is itself Huffman+RLE coded (see decompress_v5_map in libchdr) with self/parent hunk
 * references, not a flat table -- genuinely more involved than "read hunk N's offset."
 * Hunk data is CD-frame-oriented, not raw bytes: hunkBytes is always a whole multiple
 * of 2448 (2352-byte raw sector + 96-byte subcode) for a CD image, confirmed against the
 * same real file (hunkBytes=19584 = 8 * 2448).
 *
 * A hunk's actual codec is chosen per-hunk from up to 4 slots in `compression` (unused
 * slots are empty strings) -- a single file commonly mixes codecs (e.g. cdlz for data,
 * cdfl/FLAC for CDDA audio frames), confirmed against the same real file. Only the `cd*`
 * (CD-frontend) codecs matter for identification purposes -- SYSTEM.CNF and the boot
 * executable live in the data track, never in an audio-track hunk.
 */
data class ChdHeader(
    val compression: List<String>,
    val logicalBytes: Long,
    val mapOffset: Long,
    val metaOffset: Long,
    val hunkBytes: Int,
    val unitBytes: Int,
) {
    companion object {
        private const val MAGIC = "MComprHD"
        private const val V5_HEADER_SIZE = 124
        private const val V5_VERSION = 5

        fun parse(input: InputStream): ChdHeader? {
            val raw = ByteArray(V5_HEADER_SIZE)
            if (!readFully(input, raw)) return null
            if (String(raw, 0, 8, Charsets.US_ASCII) != MAGIC) return null

            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            val headerLength = buffer.getInt(8)
            val version = buffer.getInt(12)
            if (version != V5_VERSION || headerLength != V5_HEADER_SIZE) return null

            return ChdHeader(
                compression = (0 until 4).map { i -> fourCc(raw, 16 + i * 4) },
                logicalBytes = buffer.getLong(32),
                mapOffset = buffer.getLong(40),
                metaOffset = buffer.getLong(48),
                hunkBytes = buffer.getInt(56),
                unitBytes = buffer.getInt(60),
            )
        }

        private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read < 0) return false
                offset += read
            }
            return true
        }

        private val NUL = 0.toChar()

        /** Unused compression slots are all-zero bytes, not a real 4-char code -- surfaced as "". */
        private fun fourCc(buffer: ByteArray, offset: Int): String {
            return String(buffer, offset, 4, Charsets.US_ASCII).trim(NUL)
        }
    }
}
