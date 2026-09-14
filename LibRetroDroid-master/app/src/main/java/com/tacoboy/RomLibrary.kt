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
            collectRoms(context, root, results)
            results.sortedBy { it.displayName.lowercase() }
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "scanRoms failed for $treeUri", e)
            emptyList()
        }
    }

    /**
     * A folder's .m3u playlist is listed as the game, and the discs it names in that folder are
     * not listed separately: starting disc 2 of Final Fantasy VII on its own is never what anyone
     * wants, and the playlist is what lets the game change disc (see MultiDiscGame). A disc no
     * playlist names, like a single combined disc, is still listed.
     */
    private fun collectRoms(context: Context, dir: DocumentFile, into: MutableList<RomEntry>) {
        val children = dir.listFiles()
        val listedByPlaylist = children
            .filter { it.isFile && it.name?.let(MultiDiscGame::isPlaylist) == true }
            .flatMap { playlist ->
                try {
                    context.contentResolver.openInputStream(playlist.uri)
                        ?.use { M3uPlaylist.parse(it.readBytes().toString(Charsets.UTF_8)) }
                        .orEmpty()
                } catch (e: Exception) {
                    TacoBoyLog.e(TAG, "Could not read playlist ${playlist.name}", e)
                    emptyList()
                }
            }
            .map { it.lowercase() }
            .toSet()
        for (child in children) {
            when {
                child.isDirectory -> collectRoms(context, child, into)
                child.isFile -> {
                    val name = child.name ?: continue
                    if (name.lowercase() in listedByPlaylist) continue
                    if (ROM_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())) {
                        into.add(RomEntry(name, child.uri))
                    }
                }
            }
        }
    }
}
