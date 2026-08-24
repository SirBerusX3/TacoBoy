package com.tacoboy

import android.content.Context
import java.io.File

/**
 * Save states, keyed by ROM display name (sanitized) since ROMs come from
 * SAF content:// Uris rather than stable filesystem paths. Separate from
 * SRAM/battery saves (GLRetroView.serializeSRAM) — these are full
 * emulator-state snapshots via GLRetroView.serializeState.
 */
object SaveStateManager {
    const val SLOT_COUNT = 4

    fun save(context: Context, romIdentifier: String, slot: Int, data: ByteArray) {
        val file = slotFile(context, romIdentifier, slot)
        file.parentFile?.mkdirs()
        file.writeBytes(data)
    }

    fun load(context: Context, romIdentifier: String, slot: Int): ByteArray? {
        val file = slotFile(context, romIdentifier, slot)
        return if (file.exists()) file.readBytes() else null
    }

    fun lastModified(context: Context, romIdentifier: String, slot: Int): Long? {
        val file = slotFile(context, romIdentifier, slot)
        return if (file.exists()) file.lastModified() else null
    }

    private fun slotFile(context: Context, romIdentifier: String, slot: Int): File {
        val safeName = romIdentifier.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(context.filesDir, "savestates/$safeName/slot_$slot.state")
    }
}
