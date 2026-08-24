package com.tacoboy

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.CRC32

/**
 * Copies a user-picked BIOS file into filesDir (the permanent "library" of every BIOS
 * the user has ever imported), preserving its original filename rather than renaming to
 * one fixed name. Cores like SwanStation (DuckStation-derived) scan a directory and
 * identify valid PS1 BIOS images by content, not by requiring one exact filename, so
 * keeping whatever name the user's dump already had is the most broadly compatible
 * choice — also avoids us having to assert which exact filename any given core expects.
 *
 * Multiple BIOS files can coexist in the library, but a core scanning a directory with
 * more than one valid BIOS in it has no way to know which one the user actually wants —
 * it just picks whatever its own internal logic finds first, silently, with no user
 * control (the exact "we can't really choose which one" problem this was built to fix).
 * `prepareActiveBios` solves this not by asking the core to choose, but by only ever
 * showing it one file: `activeBiosDirectory` is a separate staging folder, repopulated
 * with a fresh copy of just the currently-selected library file right before each launch
 * (see TacoBoyActivity.setupRetroView) — GLRetroViewData.systemDirectory points there for
 * BIOS-needing systems instead of at the library folder directly. Ambiguity is eliminated
 * by construction, regardless of how any given core's own auto-detection works internally.
 *
 * Two systems have a BIOS as of 2026-08-23 (PS1 and Lynx), and they are recognised in
 * opposite ways — see BiosSpec for why.
 */
object BiosManager {
    private const val TAG = "TacoBoy.BiosManager"
    private const val ACTIVE_DIR_NAME = "bios_active"

    fun importBios(context: Context, sourceUri: Uri, fileName: String): Boolean {
        return try {
            val target = File(context.filesDir, fileName)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            true
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to import BIOS file $fileName", e)
            false
        }
    }

    /** One detected BIOS file, already described for display. `label`/`icon` mean whatever
     *  the owning system's detection has to say — a region for PS1, a plain "this is the
     *  boot ROM" for Lynx, which only has one valid image in existence. */
    data class DetectedBios(val fileName: String, val label: String, val icon: String)

    /**
     * How a system's BIOS is recognised, and what the core wants it called. Kept here rather
     * than on GameSystem because it is behaviour (content checks) rather than data, and
     * because only the two systems below have any BIOS at all.
     *
     * The two are recognised in opposite ways, deliberately:
     *
     *  - PS1 by *filename*, via BiosRegion's scph#### pattern. SwanStation identifies valid
     *    images by content and accepts any name, so the name is the only thing that can tell
     *    us which region a dump is — and the file is staged under whatever it is already
     *    called, since the core does not care.
     *  - Lynx by *content*: exactly 512 bytes with CRC32 0x0D973C9D. Handy hardcodes both
     *    (ROM_SIZE and ROM_CRC32 in lynx/rom.h) and falls back to its internal HLE BIOS for
     *    anything else, so checking the same two things means the picker can never offer a
     *    file the core will refuse. It also frees the user's dump to be called anything at
     *    all, which matters here: Handy looks for one exact filename (`lynxboot.img`) while
     *    real dumps circulate under several, so staging renames it.
     */
    private data class BiosSpec(
        val detect: (File) -> Pair<String, String>?,
        /** Null means "stage it under its own name" (PS1); a value renames on staging. */
        val stagedFileName: String?,
    )

    private const val LYNX_BOOT_ROM_SIZE = 512L
    private const val LYNX_BOOT_ROM_CRC32 = 0x0D973C9DL
    private const val LYNX_BOOT_ROM_FILE = "lynxboot.img"

    private fun specFor(system: GameSystem): BiosSpec? = when (system) {
        GameSystem.PS1 -> BiosSpec(
            detect = { file -> BiosRegion.detect(file.name)?.let { it.label to it.flag } },
            stagedFileName = null,
        )
        GameSystem.LYNX -> BiosSpec(
            detect = { file -> if (isLynxBootRom(file)) "Boot ROM" to "✅" else null },
            stagedFileName = LYNX_BOOT_ROM_FILE,
        )
        else -> null
    }

    /** Length first, so the 512-byte test rejects every ROM and PS1 BIOS in the library
     *  without reading any of them — only a file of exactly the right size is ever hashed. */
    private fun isLynxBootRom(file: File): Boolean {
        if (file.length() != LYNX_BOOT_ROM_SIZE) return false
        return try {
            val crc = CRC32()
            crc.update(file.readBytes())
            crc.value == LYNX_BOOT_ROM_CRC32
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to checksum candidate Lynx boot ROM ${file.name}", e)
            false
        }
    }

    /**
     * Every file in the library that this system's core would accept as a BIOS —
     * deliberately not a persisted import log, so this always reflects whatever's actually
     * on disk, including files that predate this status display or were placed some other
     * way (adb push, a prior app version). A PS1 dump with a non-standard name won't be
     * listed (same detection limit BiosRegion itself has; SwanStation could still use it if
     * made active, this display just can't identify it) — the Lynx check has no such gap,
     * since it reads the file rather than the name. Excludes the active-staging subfolder.
     */
    fun listDetectedBios(context: Context, system: GameSystem): List<DetectedBios> {
        val spec = specFor(system) ?: return emptyList()
        val files = context.filesDir.listFiles() ?: return emptyList()
        return files.filter { it.isFile }.mapNotNull { file ->
            spec.detect(file)?.let { (label, icon) -> DetectedBios(file.name, label, icon) }
        }.sortedBy { it.fileName }
    }

    /** The saved choice if it still exists among detected files, else the first one
     *  alphabetically (matches the old no-selection behavior as a sane fallback), else null
     *  if nothing's been imported at all. Used both to decide what to stage and to show the
     *  right row highlighted in RomLibraryActivity even before a game is next loaded. */
    fun resolveActiveFileName(context: Context, system: GameSystem): String? {
        val detected = listDetectedBios(context, system).map { it.fileName }
        val saved = TacoBoyPrefs.getActiveBiosFileName(context, system)
        if (saved != null && saved in detected) return saved
        return detected.firstOrNull()
    }

    /**
     * Removes a file from the library for good. Takes a name from listDetectedBios rather
     * than an arbitrary path -- that keeps it to files inside filesDir, so this can't be
     * pointed at anything outside the library. Also drops the staged copy immediately
     * instead of relying on prepareActiveBios's next-launch wipe, so a deleted BIOS can
     * never be the one handed to a core.
     */
    fun deleteBios(context: Context, fileName: String): Boolean {
        val target = File(context.filesDir, fileName)
        if (!target.isFile) return false

        val deleted = try {
            target.delete()
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to delete BIOS $fileName", e)
            false
        }
        if (deleted) {
            TacoBoyPrefs.clearActiveBiosFileName(context, fileName)
            // Staged under its own name for PS1 and under the core's expected name for
            // Lynx, so clear both rather than assuming which one is on disk.
            File(activeBiosDirectory(context), fileName).delete()
            File(activeBiosDirectory(context), LYNX_BOOT_ROM_FILE).delete()
        }
        return deleted
    }

    fun activeBiosDirectory(context: Context): File =
        File(context.filesDir, ACTIVE_DIR_NAME).apply { mkdirs() }

    /**
     * Side-effect-free "would prepareActiveBios find anything?" -- same two checks, no files
     * touched, so a caller can refuse a load *before* committing to it rather than staging an
     * empty directory and leaving the core to fail on its own terms.
     *
     * Only meaningful for systems where the BIOS is genuinely required: Lynx reports false
     * with no BIOS imported and still runs fine on Handy's internal HLE boot ROM, which is
     * what `biosOptional` records.
     */
    fun hasUsableBios(context: Context, system: GameSystem): Boolean {
        val fileName = resolveActiveFileName(context, system) ?: return false
        return File(context.filesDir, fileName).isFile
    }

    /** Stages exactly the resolved-active library file into activeBiosDirectory, clearing
     *  anything already there first -- called right before a BIOS-needing system's game
     *  loads (see TacoBoyActivity.setupRetroView). Returns false (staging dir left empty) if
     *  no BIOS file is available at all, matching the pre-existing "game fails to boot, no
     *  BIOS imported" behavior rather than introducing a new failure mode -- which for Lynx
     *  isn't a failure at all, since Handy just uses its internal HLE BIOS instead. */
    fun prepareActiveBios(context: Context, system: GameSystem): Boolean {
        val activeDir = activeBiosDirectory(context)
        activeDir.listFiles()?.forEach { it.delete() }

        val fileName = resolveActiveFileName(context, system) ?: return false
        val source = File(context.filesDir, fileName)
        if (!source.isFile) return false

        val targetName = specFor(system)?.stagedFileName ?: fileName
        return try {
            source.copyTo(File(activeDir, targetName), overwrite = true)
            true
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to stage active BIOS $fileName", e)
            false
        }
    }
}
