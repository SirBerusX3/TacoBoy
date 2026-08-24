package com.tacoboy

import java.io.ByteArrayInputStream

/**
 * Ties the CHD v5 container pieces together into "give me cooked sector N's 2048 bytes of
 * user data" -- the interface the ISO9660 layer actually wants, hiding hunk maps, per-hunk
 * codec dispatch, and CD frame layout (sync/header/subheader offsets) behind it.
 *
 * Frame-to-sector extraction verified against real device bytes: pulled the frame
 * containing LBA 16 (the ISO9660 PVD) from the user's own Crash Bandicoot CHD, decoded it,
 * and found `PLAYSTATION`/`CD001` exactly where this offset math says they should be (see
 * CHANGELOG.md). Only Mode 1 and Mode 2/Form 1 sectors are handled -- the only modes an
 * ISO9660 filesystem (directories, SYSTEM.CNF, the boot executable) is ever stored in;
 * Mode 2/Form 2 (XA audio/video streaming sectors) never holds filesystem data so isn't
 * needed for identification purposes.
 */
class ChdDisc private constructor(
    private val source: ChdRandomAccess,
    private val header: ChdHeader,
    private val hunkMap: List<ChdHunkMapEntry>,
) {
    private val framesPerHunk = header.hunkBytes / FRAME_SIZE
    private var cachedHunkIndex = -1
    private var cachedHunkData: ByteArray? = null

    /** Reads one CD sector's 2048 bytes of cooked (post sync/header/subheader) user data. */
    fun readSector(lba: Int): ByteArray {
        val hunkIndex = lba / framesPerHunk
        val frameIndex = lba % framesPerHunk
        val hunkData = decompressedHunk(hunkIndex)
        val frameOffset = frameIndex * FRAME_SIZE

        val mode = hunkData[frameOffset + 15].toInt() and 0xFF
        val dataOffset = if (mode == 2) frameOffset + 24 else frameOffset + 16
        return hunkData.copyOfRange(dataOffset, dataOffset + 2048)
    }

    private fun decompressedHunk(index: Int): ByteArray {
        if (index != cachedHunkIndex) {
            val entry = hunkMap[index]
            check(entry.type in 0..3) { "Hunk $index has unsupported map entry type ${entry.type}" }
            val codec = header.compression[entry.type]
            val compressed = source.read(entry.offset, entry.length)
            cachedHunkData = ChdCdCodec.decompress(codec, compressed, header.hunkBytes)
            cachedHunkIndex = index
        }
        return cachedHunkData!!
    }

    companion object {
        private const val FRAME_SIZE = 2448

        fun open(source: ChdRandomAccess): ChdDisc? {
            val header = ChdHeader.parse(ByteArrayInputStream(source.read(0, 124))) ?: return null
            val hunkMap = ChdHunkMap.decode(source, header) ?: return null
            return ChdDisc(source, header, hunkMap)
        }
    }
}
