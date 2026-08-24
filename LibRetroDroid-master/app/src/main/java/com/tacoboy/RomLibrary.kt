package com.tacoboy

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Scans a SAF folder tree for ROMs across every system in GameSystem.
 */
object RomLibrary {
    private const val TAG = "TacoBoy.RomLibrary"
    private val ROM_EXTENSIONS = GameSystem.SUPPORTED_EXTENSIONS

    data class RomEntry(val displayName: String, val uri: Uri)

    fun scanRoms(context: Context, treeUri: Uri): List<RomEntry> {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
            val results = mutableListOf<RomEntry>()
            collectRoms(root, results)
            results.sortedBy { it.displayName.lowercase() }
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "scanRoms failed for $treeUri", e)
            emptyList()
        }
    }

    private fun collectRoms(dir: DocumentFile, into: MutableList<RomEntry>) {
        for (child in dir.listFiles()) {
            when {
                child.isDirectory -> collectRoms(child, into)
                child.isFile -> {
                    val name = child.name ?: continue
                    if (ROM_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())) {
                        into.add(RomEntry(name, child.uri))
                    }
                }
            }
        }
    }
}
