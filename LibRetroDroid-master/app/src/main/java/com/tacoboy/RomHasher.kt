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
 * Lynx strips a 64-byte header when the file starts with the magic "LYNX\0",
 * which rcheevos detects by content rather than by size.
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
    private const val LYNX_HEADER_SIZE = 64

    /** Five bytes, not four: rcheevos compares against the literal "LYNX" with memcmp(..., 5),
     *  which takes in the terminating NUL. Matching that exactly is the point -- a four-byte
     *  compare would accept files rcheevos rejects, and produce a hash RA never asked for. */
    private val LYNX_MAGIC = byteArrayOf(
        'L'.code.toByte(), 'Y'.code.toByte(), 'N'.code.toByte(), 'X'.code.toByte(), 0,
    )

    fun raHash(context: Context, rom: RomLibrary.RomEntry): String? {
        val system = GameSystem.forFileName(rom.displayName) ?: return null
        if (system == GameSystem.PS1) return ps1Hash(context, rom)

        return try {
            val skipBytes = when (system) {
                GameSystem.SNES -> snesHeaderSize(context, rom)
                GameSystem.LYNX -> lynxHeaderSize(context, rom)
                else -> 0
            }
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

    /** A .lnx may or may not carry a 64-byte header, and rcheevos skips it when present
     *  (rc_hash_lynx in rhash/hash_rom.c). Unlike the SNES copier header this cannot be
     *  inferred from the file's length -- the header is a fixed size rather than a
     *  remainder -- so the magic has to be read. Files no longer than the header itself
     *  are left alone, matching rcheevos' own `buffer_size > 64` guard: skipping every
     *  byte would hash nothing at all. */
    private fun lynxHeaderSize(context: Context, rom: RomLibrary.RomEntry): Int {
        val length = DocumentFile.fromSingleUri(context, rom.uri)?.length() ?: return 0
        return try {
            context.contentResolver.openInputStream(rom.uri)?.use { input ->
                val leading = ByteArray(LYNX_MAGIC.size)
                var read = 0
                while (read < leading.size) {
                    val count = input.read(leading, read, leading.size - read)
                    if (count < 0) break
                    read += count
                }
                lynxHeaderSize(length, leading.copyOf(read))
            } ?: 0
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Failed to read Lynx header from ${rom.displayName}", e)
            0
        }
    }

    /** The decision itself, kept free of Context and SAF so it can be tested directly.
     *  `leadingBytes` is however much of the start of the file could actually be read,
     *  which is why a short read is treated as "no header" rather than trusted. */
    internal fun lynxHeaderSize(fileLength: Long, leadingBytes: ByteArray): Int {
        if (fileLength <= LYNX_HEADER_SIZE) return 0
        if (leadingBytes.size < LYNX_MAGIC.size) return 0
        for (i in LYNX_MAGIC.indices) {
            if (leadingBytes[i] != LYNX_MAGIC[i]) return 0
        }
        return LYNX_HEADER_SIZE
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
