package com.tacoboy

import android.content.Context
import java.io.File

/**
 * Cart battery-save (SRAM) persistence, keyed by ROM display name like
 * SaveStateManager. Separate from save states (GLRetroView.serializeState,
 * a full emulator-memory snapshot) — this is the actual in-game save data
 * (GLRetroView.serializeSRAM), restored automatically via
 * GLRetroViewData.saveRAMState whenever a ROM is (re)loaded, so progress
 * saved in-game survives the app being backgrounded/killed or the Activity
 * being recreated (see TacoBoyActivity.persistSram / setupRetroView).
 */
object SramManager {

    fun save(context: Context, romIdentifier: String, data: ByteArray) {
        // Cores with no battery-backed save chip return an empty array — writing
        // that would clobber a real save if this ever ran against a stale/wrong core.
        if (data.isEmpty()) return
        val file = file(context, romIdentifier)
        file.parentFile?.mkdirs()
        file.writeBytes(data)
    }

    fun load(context: Context, romIdentifier: String): ByteArray? {
        val file = file(context, romIdentifier)
        return if (file.exists()) file.readBytes() else null
    }

    private fun file(context: Context, romIdentifier: String): File {
        val safeName = romIdentifier.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(context.filesDir, "saveram/$safeName.srm")
    }
}
