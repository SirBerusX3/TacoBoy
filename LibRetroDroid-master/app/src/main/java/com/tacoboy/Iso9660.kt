package com.tacoboy

/**
 * Minimal ISO9660 directory-tree reader -- just enough to locate SYSTEM.CNF and resolve
 * the boot executable it points at, per `rc_hash_find_playstation_executable` in
 * rcheevos' `hash_disc.c`. Verified against real device bytes: read the Primary Volume
 * Descriptor and root directory of the user's own Crash Bandicoot CHD and found exactly
 * the expected `SCUS-94900` volume ID, a `SYSTEM.CNF;1` entry, and its content matching
 * chdman/RA's documented layout byte-for-byte (see CHANGELOG.md).
 */
object Iso9660 {
    data class DirEntry(val name: String, val lba: Int, val length: Int, val isDirectory: Boolean)

    private const val PVD_LBA = 16
    private const val ROOT_DIR_RECORD_OFFSET = 156

    fun readRootDirectory(readSector: (Int) -> ByteArray): List<DirEntry> {
        val pvd = readSector(PVD_LBA)
        val lba = readUInt32LE(pvd, ROOT_DIR_RECORD_OFFSET + 2)
        val length = readUInt32LE(pvd, ROOT_DIR_RECORD_OFFSET + 10)
        return readDirectory(readSector, lba, length)
    }

    fun readDirectory(readSector: (Int) -> ByteArray, lba: Int, length: Int): List<DirEntry> {
        val sectorCount = (length + 2047) / 2048
        val entries = mutableListOf<DirEntry>()
        for (s in 0 until sectorCount) {
            val data = readSector(lba + s)
            var pos = 0
            while (pos < data.size) {
                val recordLength = data[pos].toInt() and 0xFF
                if (recordLength == 0) break
                val idLength = data[pos + 32].toInt() and 0xFF
                val idFirstByte = data[pos + 33].toInt() and 0xFF
                // "." and ".." self/parent entries are a single 0x00/0x01 byte, not a real name
                val isDotEntry = idLength == 1 && (idFirstByte == 0x00 || idFirstByte == 0x01)
                if (!isDotEntry) {
                    val rawId = String(data, pos + 33, idLength, Charsets.US_ASCII)
                    val flags = data[pos + 25].toInt() and 0xFF
                    entries.add(
                        DirEntry(
                            name = rawId.substringBefore(';'), // drop the ";1" version suffix
                            lba = readUInt32LE(data, pos + 2),
                            length = readUInt32LE(data, pos + 10),
                            isDirectory = flags and 0x02 != 0,
                        )
                    )
                }
                pos += recordLength
            }
        }
        return entries
    }

    fun findEntry(entries: List<DirEntry>, name: String): DirEntry? =
        entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Resolves a backslash-separated path (as found in a SYSTEM.CNF `BOOT=` line, e.g.
     * `FOO\GAME.EXE`) against the tree, walking into subdirectories as needed. */
    fun resolvePath(readSector: (Int) -> ByteArray, path: String): DirEntry? {
        val parts = path.split('\\').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var currentDir = readRootDirectory(readSector)
        var found: DirEntry? = null
        for ((i, part) in parts.withIndex()) {
            found = findEntry(currentDir, part) ?: return null
            if (i < parts.size - 1) {
                if (!found.isDirectory) return null
                currentDir = readDirectory(readSector, found.lba, found.length)
            }
        }
        return found
    }

    private fun readUInt32LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
