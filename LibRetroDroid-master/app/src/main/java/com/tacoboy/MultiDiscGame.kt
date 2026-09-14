package com.tacoboy

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.swordfish.libretrodroid.VirtualFile
import java.io.File

/**
 * A multi-disc game: an .m3u playlist and the discs it lists, which sit beside it in the same
 * folder, the layout the user's PS1 and Sega CD collections already use.
 *
 * The cores all read .m3u themselves, but two things stop the user's own playlists from working
 * as they are. Their lines end "\r\r\n", and Genesis Plus GX strips only one \r, so every disc
 * name keeps a stray one and matches nothing. And both cores that were read (Genesis Plus GX,
 * Beetle PSX) join each entry onto the playlist's directory, so under a bare virtual name like
 * "Game.m3u" they look for "/Disc 1.chd" or "./Disc 1.chd". So TacoBoy reads the playlist, finds
 * every disc through SAF, and hands the core a clean playlist of its own with the discs, all
 * under one virtual directory, [VIRTUAL_DIR], where the cores' own joins land exactly on them.
 */
internal object MultiDiscGame {
    private const val TAG = "TacoBoy.MultiDiscGame"

    /** Not a real directory: LibretroDroid's VFS matches these names before touching the disk. */
    const val VIRTUAL_DIR = "/tacoboy-discs"

    data class Disc(val name: String, val uri: Uri)

    sealed class Resolution {
        data class Found(val discs: List<Disc>) : Resolution()
        data class MissingDiscs(val names: List<String>) : Resolution()
        object Unreadable : Resolution()
    }

    fun isPlaylist(fileName: String) = fileName.endsWith(".m3u", ignoreCase = true)

    /** Finds every disc [playlistUri] lists, in playlist order. */
    fun resolve(context: Context, playlistUri: Uri): Resolution {
        val entries = try {
            context.contentResolver.openInputStream(playlistUri)?.use { M3uPlaylist.parse(it.readBytes().toString(Charsets.UTF_8)) }
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Could not read playlist $playlistUri", e)
            null
        } ?: return Resolution.Unreadable
        if (entries.isEmpty()) return Resolution.Unreadable

        val siblings = siblingsOf(context, playlistUri) ?: return Resolution.Unreadable
        val found = mutableListOf<Disc>()
        val missing = mutableListOf<String>()
        for (entry in entries) {
            val uri = siblings[entry.lowercase()]
            if (uri == null) missing += entry else found += Disc(entry, uri)
        }
        return if (missing.isEmpty()) Resolution.Found(found) else Resolution.MissingDiscs(missing)
    }

    /**
     * The files next to [documentUri], by lower-cased name. SAF has no "parent of" call, so the
     * parent's document ID is taken from the document's own, which for local storage is its path
     * ("primary:Emulation/PS1/Game/Game.m3u"), and its children listed through the tree.
     */
    fun siblingsOf(context: Context, documentUri: Uri): Map<String, Uri>? = try {
        val documentId = DocumentsContract.getDocumentId(documentUri)
        val parentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentId.isEmpty()) {
            null
        } else {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(documentUri, parentId)
            context.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null,
            )?.use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0) ?: continue
                        put(name.lowercase(), DocumentsContract.buildDocumentUriUsingTree(documentUri, cursor.getString(1)))
                    }
                }
            }
        }
    } catch (e: Exception) {
        TacoBoyLog.e(TAG, "Could not list the folder of $documentUri", e)
        null
    }

    /**
     * The virtual files for a found game: TacoBoy's clean playlist first, which is what the core
     * is asked to load, then every disc, named exactly as that playlist lists them. The playlist
     * is written to the cache directory because the VFS serves open file descriptors.
     */
    fun virtualFiles(context: Context, playlistName: String, discs: List<Disc>): List<VirtualFile> {
        val playlist = File(context.cacheDir, "playlist.m3u")
        playlist.writeText(M3uPlaylist.render(discs.map { it.name }))
        val files = mutableListOf(
            VirtualFile("$VIRTUAL_DIR/$playlistName", ParcelFileDescriptor.open(playlist, ParcelFileDescriptor.MODE_READ_ONLY))
        )
        discs.forEach { disc ->
            val pfd = context.contentResolver.openFileDescriptor(disc.uri, "r")
                ?: throw IllegalStateException("Could not open ${disc.name}")
            files += VirtualFile("$VIRTUAL_DIR/${disc.name}", pfd)
        }
        return files
    }
}

/** The .m3u format as the user's playlists use it, kept free of Android so it can be tested. */
internal object M3uPlaylist {
    /**
     * Disc filenames in order. Every \r is dropped, not just one before the \n (the user's files
     * end lines "\r\r\n"), as is trailing whitespace; blank lines and #comments are skipped.
     */
    fun parse(text: String): List<String> = text
        .replace("\r", "")
        .split('\n')
        .map { it.trimEnd() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Plain LF lines, which every core parses. */
    fun render(entries: List<String>): String = entries.joinToString("") { "$it\n" }

    private val DISC_LABEL = Regex("""\((Disc \d+)\)((?:\s*\([^)]*\))*)\.[^.]+$""", RegexOption.IGNORE_CASE)

    /**
     * What the Change Disc list calls a disc: "Disc 2" from "Final Fantasy VII (USA) (Disc 2).chd",
     * keeping any tag after it, "Disc 1 (Allies)" from Red Alert's. A name without a "(Disc N)" tag
     * is shown as its number and filename.
     */
    fun discLabel(fileName: String, index: Int): String {
        val match = DISC_LABEL.find(fileName) ?: return "Disc ${index + 1}: ${fileName.substringBeforeLast('.')}"
        val extra = match.groupValues[2].trim()
        return if (extra.isEmpty()) match.groupValues[1] else "${match.groupValues[1]} $extra"
    }
}
