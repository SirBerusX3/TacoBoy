package com.tacoboy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Named, saveable snapshots of a ControllerBindings table. Captured from whichever
 * system tab is active when saved, but not tied to that system afterward — a preset
 * represents a general "how I like my controller wired" preference (e.g. "GameSir
 * Classic"), applicable to any system's binding table via applyToSystem. Persisted
 * as a JSON file rather than through TacoBoyPrefs, matching RomLibraryCache's
 * pattern — a growing list of named objects doesn't fit SharedPreferences' flat
 * key-value shape the way a handful of scalars does.
 */
object ControllerPresets {
    private const val TAG = "TacoBoy.ControllerPresets"

    data class Preset(val name: String, val bindings: Map<ControllerBindings.Target, Int>)

    fun list(context: Context): List<Preset> {
        val file = presetsFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i -> parsePreset(array.getJSONObject(i)) }
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to read controller presets", e)
            emptyList()
        }
    }

    /** Overwrites any existing preset with the same name. */
    fun save(context: Context, name: String, bindings: Map<ControllerBindings.Target, Int>) {
        val updated = list(context).filterNot { it.name == name } + Preset(name, bindings)
        writeAll(context, updated)
    }

    fun delete(context: Context, name: String) {
        writeAll(context, list(context).filterNot { it.name == name })
        // Deleting the preset currently marked "default on Pocket Taco connect" would
        // otherwise leave TacoBoyPrefs pointing at a name that no longer resolves to
        // anything, silently falling back to resetAllToDefaults with no explanation.
        if (TacoBoyPrefs.getDefaultControllerPresetName(context) == name) {
            TacoBoyPrefs.setDefaultControllerPresetName(context, null)
        }
    }

    /**
     * Bulk-replaces every bound target in `system`'s table with the preset's values.
     * Deliberately writes directly rather than going through ControllerBindings.setSource
     * (which resolves conflicts for a *single* reassignment) — a saved preset is
     * already a clean bijection by construction (captured via getSource, which never
     * returns duplicates across targets), so a plain overwrite can't introduce one.
     */
    fun applyToSystem(context: Context, system: GameSystem, preset: Preset) {
        preset.bindings.forEach { (target, sourceKeyCode) ->
            TacoBoyPrefs.setButtonBindingSource(context, system, target, sourceKeyCode)
        }
    }

    private fun parsePreset(json: JSONObject): Preset {
        val name = json.getString("name")
        val bindingsJson = json.getJSONObject("bindings")
        val bindings = ControllerBindings.Target.entries
            .filter { bindingsJson.has(it.name) }
            .associateWith { bindingsJson.getInt(it.name) }
        return Preset(name, bindings)
    }

    private fun writeAll(context: Context, presets: List<Preset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            val bindingsJson = JSONObject()
            preset.bindings.forEach { (target, keyCode) -> bindingsJson.put(target.name, keyCode) }
            array.put(
                JSONObject().apply {
                    put("name", preset.name)
                    put("bindings", bindingsJson)
                }
            )
        }
        try {
            presetsFile(context).writeText(array.toString())
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to write controller presets", e)
        }
    }

    private fun presetsFile(context: Context): File = File(context.filesDir, "controller_presets.json")
}
