package com.tacoboy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Box art from libretro's thumbnail server (https://thumbnails.libretro.com),
 * matched by exact ROM filename against each system's "Named_Boxarts"
 * folder (see GameSystem.thumbnailFolder). Not every ROM has matching art
 * — aftermarket/homebrew/patched titles especially — so a miss is
 * expected and not logged as an error.
 */
object BoxArtCache {
    private const val TAG = "TacoBoy.BoxArtCache"

    fun getCachedOrNull(context: Context, romDisplayName: String): File? {
        val file = localFile(context, romDisplayName)
        return if (file.exists()) file else null
    }

    /**
     * Blocking network call — always run on Dispatchers.IO. Returns true if
     * art is present locally afterward (already cached, or freshly
     * downloaded); false if this title has no matching art, its system
     * isn't supported, or the request failed.
     */
    fun ensureDownloaded(context: Context, romDisplayName: String): Boolean {
        val file = localFile(context, romDisplayName)
        if (file.exists()) return true

        val system = GameSystem.forFileName(romDisplayName) ?: return false
        val nameWithoutExtension = romDisplayName.substringBeforeLast('.')
        val url = "https://thumbnails.libretro.com/" +
            Uri.encode(system.thumbnailFolder) + "/Named_Boxarts/" +
            Uri.encode(nameWithoutExtension) + ".png"
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return false
            }

            file.parentFile?.mkdirs()
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            tempFile.renameTo(file)
        } catch (e: Exception) {
            TacoBoyLog.d(TAG, "No box art for $romDisplayName (${e.message})")
            false
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Copies a user-picked image in as this ROM's box art, decoded and
     * re-encoded to PNG so it matches the on-disk format ensureDownloaded
     * produces regardless of the source image's original format. Blocking
     * — run on Dispatchers.IO. A custom image set this way is
     * indistinguishable from a downloaded one to ensureDownloaded, which
     * only checks "does a file already exist" — so it won't be
     * overwritten by a later bulk "Get Art" pass.
     */
    fun setCustomArt(context: Context, romDisplayName: String, sourceUri: Uri): Boolean {
        return try {
            val bitmap = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return false

            val file = localFile(context, romDisplayName)
            file.parentFile?.mkdirs()
            file.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
            true
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to set custom art for $romDisplayName", e)
            false
        }
    }

    /** Removes any art (downloaded or custom) so the next ensureDownloaded call re-fetches from scratch. */
    fun clear(context: Context, romDisplayName: String) {
        localFile(context, romDisplayName).delete()
    }

    private fun localFile(context: Context, romDisplayName: String): File {
        val safeName = romDisplayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(context.filesDir, "boxart/$safeName.png")
    }
}
