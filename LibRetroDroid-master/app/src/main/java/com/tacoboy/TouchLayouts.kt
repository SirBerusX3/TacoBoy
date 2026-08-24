package com.tacoboy

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Where a user has dragged each on-screen control and how big they made it, stored per system.
 *
 * Positions are fractions of the control zone, not pixels, so a saved layout survives the
 * boundary handle being dragged to a different height — which is the whole reason the pad's
 * own default layout is expressed in fractions too. A control the user hasn't moved simply
 * isn't in the map, and falls back to its computed default; that also means a later change to
 * the default layout still reaches anyone who never customised that particular button.
 *
 * Per system rather than global because the control sets genuinely differ: a Game Boy pad has
 * five controls and a PS1 pad has sixteen, and a position that suits one would be meaningless
 * on the other.
 *
 * A JSON file rather than SharedPreferences, matching ControllerPresets and RomLibraryCache —
 * a nested map of maps doesn't fit the flat key-value shape, and this is written rarely (only
 * on leaving edit mode) and read once per game load.
 */
object TouchLayouts {
    private const val TAG = "TacoBoy.TouchLayouts"
    private const val FILE_NAME = "touch_layouts.json"

    /**
     * Where one control sits and how big it is, relative to the control zone.
     *
     * [x] and [y] are fractions of the zone's width and height, both 0..1. [scale] multiplies
     * that control's default size, on top of the global size from
     * TacoBoyPrefs.getTouchControlScale -- so a user who has set everything larger can still
     * pull one crowded button back down without undoing the global change.
     */
    data class Position(val x: Float, val y: Float, val scale: Float = 1f)

    /** Bounds on a single control's own size multiplier. Wider than the global range because
     *  this one is deliberate, one control at a time, and immediately visible while editing. */
    const val SCALE_MIN = 0.5f
    const val SCALE_MAX = 2f

    fun load(context: Context, system: GameSystem): Map<String, Position> {
        val file = layoutsFile(context)
        if (!file.exists()) return emptyMap()
        return try {
            val root = JSONObject(file.readText())
            val forSystem = root.optJSONObject(system.name) ?: return emptyMap()
            buildMap {
                forSystem.keys().forEach { key ->
                    val pair = forSystem.optJSONArray(key) ?: return@forEach
                    if (pair.length() >= 2) {
                        put(
                            key,
                            Position(
                                pair.getDouble(0).toFloat().coerceIn(0f, 1f),
                                pair.getDouble(1).toFloat().coerceIn(0f, 1f),
                                // Third element added when per-control resizing landed; a
                                // layout saved before that is a two-element array and reads
                                // back at its original size.
                                if (pair.length() >= 3) {
                                    pair.getDouble(2).toFloat().coerceIn(SCALE_MIN, SCALE_MAX)
                                } else {
                                    1f
                                },
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to read touch layouts", e)
            emptyMap()
        }
    }

    /** Replaces this system's entry wholesale; an empty map removes it, which is what "reset
     *  to defaults" does — deleting the entry rather than writing out the current defaults, so
     *  the user keeps tracking any later change to them. */
    fun save(context: Context, system: GameSystem, positions: Map<String, Position>) {
        try {
            val file = layoutsFile(context)
            val root = if (file.exists()) {
                try {
                    JSONObject(file.readText())
                } catch (e: Exception) {
                    TacoBoyLog.e(TAG, "Discarding unreadable touch layouts file", e)
                    JSONObject()
                }
            } else {
                JSONObject()
            }

            if (positions.isEmpty()) {
                root.remove(system.name)
            } else {
                val forSystem = JSONObject()
                positions.forEach { (key, position) ->
                    forSystem.put(
                        key,
                        listOf(position.x, position.y, position.scale).let { values ->
                            org.json.JSONArray().apply { values.forEach { put(it.toDouble()) } }
                        }
                    )
                }
                root.put(system.name, forSystem)
            }

            // Other systems' layouts live in the same file, so a failed write must not be able
            // to take them with it -- write beside it and swap only once the new copy is whole.
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to write touch layouts", e)
        }
    }

    fun clearAll(context: Context) {
        try {
            layoutsFile(context).delete()
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to clear touch layouts", e)
        }
    }

    private fun layoutsFile(context: Context) = File(context.filesDir, FILE_NAME)
}
