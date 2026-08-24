package com.tacoboy

import java.security.MessageDigest

/**
 * Computes RetroAchievements' PS1 identification hash from a CHD disc image, per
 * `rc_hash_psx()`/`rc_hash_find_playstation_executable()` in rcheevos' `hash_disc.c`
 * (read from source, not paraphrased -- see CHANGELOG.md/memory for the full derivation).
 *
 * Algorithm: find `SYSTEM.CNF` in the root directory, parse its `BOOT=`/`BOOT2=` line to
 * get the boot executable's path (stripping the `cdrom:` prefix and leading backslashes;
 * internal backslashes for a subdirectory path are kept, since they're what gets hashed).
 * Fall back to a root-level `PSX.EXE` if no BOOT line resolves. Verify the executable's
 * `PS-X EXE` header (only 7 of its 8 magic bytes -- a quirk of rcheevos itself, matched
 * here for identical hashes rather than "fixed"). Hash = MD5(name bytes + executable
 * content bytes), content length = the header's declared size plus 2048 (the header
 * sector itself, excluded from the declared size).
 *
 * End-to-end verified against the user's real Crash Bandicoot (USA).chd: resolved
 * BOOT=cdrom:\SCUS_949.00;1, confirmed the executable's declared size (288768) plus 2048
 * matches the ISO9660 directory's own file-length field (290816) exactly -- a strong
 * internal cross-check that offset math throughout this whole chain is right.
 */
object Ps1Hasher {
    private val EXE_MAGIC = "PS-X EXE".toByteArray(Charsets.US_ASCII)
    private const val SECTOR_SIZE = 2048

    fun hash(source: ChdRandomAccess): String? {
        val disc = ChdDisc.open(source) ?: return null
        val readSector: (Int) -> ByteArray = disc::readSector

        val (name, entry) = resolveBootExecutable(readSector) ?: return null

        val header = readSector(entry.lba)
        if (!header.copyOfRange(0, 7).contentEquals(EXE_MAGIC.copyOfRange(0, 7))) return null

        val size = readUInt32LE(header, 28) + SECTOR_SIZE
        val content = readFile(readSector, entry.lba, size)

        val digest = MessageDigest.getInstance("MD5")
        digest.update(name.toByteArray(Charsets.US_ASCII))
        digest.update(content)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resolveBootExecutable(readSector: (Int) -> ByteArray): Pair<String, Iso9660.DirEntry>? {
        val root = Iso9660.readRootDirectory(readSector)
        val systemCnf = Iso9660.findEntry(root, "SYSTEM.CNF")
        val bootPath = systemCnf?.let { parseBootLine(readFile(readSector, it.lba, it.length)) }
        if (bootPath != null) {
            Iso9660.resolvePath(readSector, bootPath)?.let { return bootPath to it }
        }
        return Iso9660.findEntry(root, "PSX.EXE")?.let { "PSX.EXE" to it }
    }

    private fun parseBootLine(content: ByteArray): String? {
        val text = String(content, Charsets.US_ASCII)
        for (line in text.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim()
            if (!key.equals("BOOT", ignoreCase = true) && !key.equals("BOOT2", ignoreCase = true)) continue
            var value = line.substring(eq + 1).trim()
            if (value.startsWith("cdrom:", ignoreCase = true)) value = value.substring(6)
            while (value.startsWith("\\")) value = value.substring(1)
            val cut = value.indexOfFirst { it.isWhitespace() || it == ';' }
            return if (cut >= 0) value.substring(0, cut) else value
        }
        return null
    }

    private fun readFile(readSector: (Int) -> ByteArray, lba: Int, length: Int): ByteArray {
        val sectorCount = (length + SECTOR_SIZE - 1) / SECTOR_SIZE
        val buffer = ByteArray(sectorCount * SECTOR_SIZE)
        for (s in 0 until sectorCount) {
            readSector(lba + s).copyInto(buffer, s * SECTOR_SIZE)
        }
        return buffer.copyOfRange(0, length)
    }

    private fun readUInt32LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
