package com.tacoboy

/**
 * Recognises a Sega CD / Mega-CD boot ROM by its contents, and says which region it is for.
 *
 * Genesis Plus GX (core/loadrom.c, load_bios) reads the disc's region and then loads exactly one
 * of bios_CD_U.bin, bios_CD_E.bin or bios_CD_J.bin, refusing to boot without it. So TacoBoy needs
 * each imported file's region, to stage it under the name the core will ask for. Filenames are
 * no help (the user's three are eu_mcd1_9210.bin, jp_mcd1_9112.bin and us_scd1_9210.bin; others
 * circulate under many more), but the ROM describes itself: a boot ROM is a Mega Drive-format
 * image, verified against the user's three dumps, whose EU and US MD5s match the ones Genesis
 * Plus GX documents:
 *
 *  - 0x100: "SEGA MEGA DRIVE " (a Genesis-branded model may say "SEGA GENESIS    ")
 *  - 0x120: the ROM's name, "MEGA-CD BOOT ROM" / "SEGA-CD BOOT ROM", or the CDX and
 *    WonderMega variants load_bios itself checks for ("CDX BOOT ROM", "WONDER-MEGA BOOT")
 *  - 0x1F0: the region, a letter (J, U, E) as in all three dumps, or in later ROMs a hex digit
 *    of region bits (1 Japan, 4 USA, 8 Europe)
 *
 * A PS1 BIOS is 512 KB like some Sega CD 2 boot ROMs, but carries none of this, so the header
 * check alone keeps the two apart.
 */
internal object SegaCdBios {
    enum class Region(val stagedFileName: String, val label: String, val flag: String) {
        USA("bios_CD_U.bin", "USA", "🇺🇸"),
        EUROPE("bios_CD_E.bin", "Europe", "🇪🇺"),
        JAPAN("bios_CD_J.bin", "Japan", "🇯🇵"),
    }

    /** Boot ROMs are 128 KB; some Sega CD 2 dumps are 512 KB, of which the core reads the first 128. */
    val VALID_SIZES = setOf(0x20000L, 0x80000L)

    /** How much of the start of the file [regions] needs. */
    const val HEADER_BYTES = 0x200

    /**
     * The regions this boot ROM serves, from its first [HEADER_BYTES] bytes: one for a normal
     * dump, several for a region-free one, empty if this is not a Sega CD boot ROM at all.
     */
    fun regions(fileLength: Long, header: ByteArray): Set<Region> {
        if (fileLength !in VALID_SIZES || header.size < HEADER_BYTES) return emptySet()
        val system = ascii(header, 0x100, 16)
        if (!system.startsWith("SEGA MEGA DRIVE") && !system.startsWith("SEGA GENESIS")) return emptySet()
        if (!ascii(header, 0x120, 16).contains("BOOT")) return emptySet()

        val field = ascii(header, 0x1F0, 3).trim()
        val regions = mutableSetOf<Region>()
        for (c in field) {
            when (c) {
                'J' -> regions += Region.JAPAN
                'U' -> regions += Region.USA
                'E' -> regions += Region.EUROPE
                else -> c.digitToIntOrNull(16)?.let { bits ->
                    if (bits and 1 != 0) regions += Region.JAPAN
                    if (bits and 4 != 0) regions += Region.USA
                    if (bits and 8 != 0) regions += Region.EUROPE
                }
            }
        }
        return regions
    }

    /**
     * Which file serves each region: a file made for that region alone first, then a region-free
     * one, so a dedicated dump always wins over a modded one. Within either group the
     * [preferred] file wins if it qualifies, otherwise the first by name, matching how every
     * other system resolves its active BIOS.
     */
    fun assign(detected: Map<String, Set<Region>>, preferred: String?): Map<Region, String> {
        val result = mutableMapOf<Region, String>()
        for (region in Region.entries) {
            val dedicated = detected.filter { (_, regions) -> regions == setOf(region) }.keys.sorted()
            val regionFree = detected.filter { (_, regions) -> region in regions && regions.size > 1 }.keys.sorted()
            val pick = listOf(dedicated, regionFree).firstNotNullOfOrNull { group ->
                if (group.isEmpty()) null else if (preferred in group) preferred else group.first()
            }
            if (pick != null) result[region] = pick
        }
        return result
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int) =
        String(bytes, offset, length, Charsets.ISO_8859_1)
}
