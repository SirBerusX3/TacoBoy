package com.tacoboy

/**
 * Region lookup for PS1 BIOS files by their SCPH model number, from the
 * "[SIMPLE]" section of BIOSregion.md at the repo root -- covers the common
 * "standard" dumps (5500/5501/5502/5503 and their 1000/7000-series siblings),
 * not the full historical hardware-revision catalog that file also documents.
 *
 * SwanStation itself doesn't need this -- per BiosManager's doc comment it
 * auto-detects valid BIOS images by content, scanning the whole system
 * directory regardless of filename. This is purely a "what have I actually
 * got" status display (see RomLibraryActivity.populateBiosList) -- nothing
 * here gates whether the core can find/use a BIOS file.
 */
object BiosRegion {
    data class Region(val label: String, val flag: String)

    // SCPH-1001 dumps are documented as used for both early JP and (far more
    // commonly) US consoles -- treated as US here, matching the "very common"
    // real-world case, not the rarer JP one.
    private val byModel = mapOf(
        "1000" to Region("Japan", "🇯🇵"),
        "5500" to Region("Japan", "🇯🇵"),
        "7000" to Region("Japan", "🇯🇵"),
        "1001" to Region("USA", "🇺🇸"),
        "5501" to Region("USA", "🇺🇸"),
        "7001" to Region("USA", "🇺🇸"),
        "1002" to Region("Europe", "🇪🇺"),
        "5502" to Region("Europe", "🇪🇺"),
        "7002" to Region("Europe", "🇪🇺"),
        "5503" to Region("UK", "🇬🇧"),
        "7003" to Region("UK", "🇬🇧"),

        // Seventh-generation (SCPH-7500 series) and the SCPH-5552 sibling aren't in the
        // [SIMPLE] list this table was first built from, only in BIOSregion.md's [DETAILED]
        // catalog -- but 7502 dumps in particular are common in the wild, and an undetected
        // file is worse here than it used to be: the BIOS picker can't select or delete what
        // it can't identify, so such a file would sit in the library unreachable.
        "7500" to Region("Japan", "🇯🇵"),
        "7501" to Region("USA", "🇺🇸"),
        "7502" to Region("Europe", "🇪🇺"),
        // 5502's twin -- same console, differing only in what was in the box.
        "5552" to Region("Europe", "🇪🇺"),
        // [DETAILED] calls 7503 the Southeast Asian model outright, so it gets its own label
        // rather than being folded into the UK bucket its 7003 predecessor sits in above.
        "7503" to Region("Asia", "🌏"),
    )

    // Real-world dumps vary in casing/hyphenation (scph5501.bin, SCPH-5501.BIN,
    // ps1_scph5501_bios.bin, ...) -- match the model number anywhere in the name.
    private val pattern = Regex("scph-?(\\d{4})", RegexOption.IGNORE_CASE)

    fun detect(fileName: String): Region? {
        val model = pattern.find(fileName)?.groupValues?.get(1) ?: return null
        return byModel[model]
    }
}
