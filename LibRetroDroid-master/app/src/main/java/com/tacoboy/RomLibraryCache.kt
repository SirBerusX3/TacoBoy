package com.tacoboy

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the last ROM scan to disk so opening the library doesn't re-walk
 * the whole SAF tree every time (measured ~4.5s for 485 ROMs in
 * one-subfolder-per-game layouts). One cache file per GameSystem, since
 * each system now remembers its own folder — a single shared cache file
 * would only ever reflect whichever system was scanned most recently.
 * Invalidated by folder URI mismatch or an explicit refresh.
 */
object RomLibraryCache {
    private const val TAG = "TacoBoy.RomLibraryCache"

    fun load(context: Context, system: GameSystem, folderUri: Uri): List<RomLibrary.RomEntry>? {
        val file = cacheFile(context, system)
        if (!file.exists()) return null

        return try {
            val json = JSONObject(file.readText())
            if (json.optString("folderUri") != folderUri.toString()) return null

            val array = json.getJSONArray("roms")
            (0 until array.length()).map { i ->
                val entry = array.getJSONObject(i)
                RomLibrary.RomEntry(entry.getString("name"), Uri.parse(entry.getString("uri")))
            }
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to read ROM library cache for $system", e)
            null
        }
    }

    fun save(context: Context, system: GameSystem, folderUri: Uri, roms: List<RomLibrary.RomEntry>) {
        val array = JSONArray()
        roms.forEach { rom ->
            array.put(
                JSONObject().apply {
                    put("name", rom.displayName)
                    put("uri", rom.uri.toString())
                }
            )
        }
        val json = JSONObject().apply {
            put("folderUri", folderUri.toString())
            put("roms", array)
        }

        try {
            cacheFile(context, system).writeText(json.toString())
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to write ROM library cache for $system", e)
        }
    }

    private fun cacheFile(context: Context, system: GameSystem): File {
        return File(context.filesDir, "rom_library_cache_${system.name}.json")
    }
}
