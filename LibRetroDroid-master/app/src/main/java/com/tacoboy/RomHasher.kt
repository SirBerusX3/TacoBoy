package com.tacoboy

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Computes the same MD5 identification hash RetroAchievements itself uses,
 * per console — checked against RA's own docs and rcheevos source before
 * implementing, not assumed:
 * https://docs.retroachievements.org/developer-docs/game-identification.html
 *
 * GB/GBC/GBA hash the file's raw bytes unmodified. SNES strips a leading
 * 512-byte copier header first, but only if the file size indicates one is
 * actually present (size mod 0x2000 == 512) — a leftover convention from
 * SNES backup-copier hardware, unrelated to the in-ROM SNES header, and not
 * every .sfc/.smc file has one.
 *
 * PS1 hashes the disc's boot executable out of its .chd (see Ps1Hasher):
 * decode the CHD v5 container + hunk map (ChdHeader/ChdHunkMap), decompress
 * hunks on demand (ChdCdCodec), read the ISO9660 filesystem to find
 * SYSTEM.CNF and resolve the boot executable it names (Iso9660), then hash
 * that executable's name and contents together, matching rcheevos exactly.
 */
object RomHasher {
    private const val TAG = "TacoBoy.RomHasher"
    private const val SNES_COPIER_HEADER_SIZE = 512
    private const val SNES_HEADER_MODULUS = 0x2000L

    fun raHash(context: Context, rom: RomLibrary.RomEntry): String? {
        val system = GameSystem.forFileName(rom.displayName) ?: return null
        if (system == GameSystem.PS1) return ps1Hash(context, rom)

        return try {
            val skipBytes = if (system == GameSystem.SNES) snesHeaderSize(context, rom) else 0
            val digest = MessageDigest.getInstance("MD5")
            val hashed = context.contentResolver.openInputStream(rom.uri)?.use { input ->
                if (skipBytes > 0) skipFully(input, skipBytes)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
                true
            } ?: false
            if (!hashed) return null
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to hash ${rom.displayName}", e)
            null
        }
    }

    /** SAF's InputStream is sequential-only, but CHD hunk lookups need real seeks (the
     * hunk map lives at the end of the file, boot executables can be anywhere on the
     * disc) -- openFileDescriptor's fd backs a real seekable FileChannel instead. */
    private fun ps1Hash(context: Context, rom: RomLibrary.RomEntry): String? {
        return try {
            context.contentResolver.openFileDescriptor(rom.uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    val source = ChdRandomAccess { offset, length ->
                        val buffer = ByteBuffer.allocate(length)
                        var position = offset
                        while (buffer.hasRemaining()) {
                            val read = channel.read(buffer, position)
                            if (read < 0) break
                            position += read
                        }
                        buffer.array()
                    }
                    Ps1Hasher.hash(source)
                }
            }
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to hash PS1 CHD ${rom.displayName}", e)
            null
        }
    }

    private fun snesHeaderSize(context: Context, rom: RomLibrary.RomEntry): Int {
        val length = DocumentFile.fromSingleUri(context, rom.uri)?.length() ?: return 0
        return if (length % SNES_HEADER_MODULUS == SNES_COPIER_HEADER_SIZE.toLong()) SNES_COPIER_HEADER_SIZE else 0
    }

    private fun skipFully(input: InputStream, byteCount: Int) {
        var remaining = byteCount.toLong()
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }
}
