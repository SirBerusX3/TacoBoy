package com.tacoboy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Achievement unlocks that have not yet been accepted by RetroAchievements, kept on disk so
 * an unlock earned without a connection survives until there is one (compliance audit A4:
 * "unlocks created while offline must be securely cached and sync to RetroAchievements when
 * connectivity returns").
 *
 * Every unlock is written here *before* its first submission, not after a failed one, so a
 * process killed mid-request cannot lose it. UnlockSync removes an entry once RA has either
 * accepted it or definitively refused it.
 *
 * "Securely": the file is in app-private internal storage, unreadable to other apps, and holds
 * no credentials -- the session token is read from TacoBoyPrefs at send time. An entry is only
 * ever sent for the account that earned it.
 */
internal data class PendingUnlock(
    val username: String,
    val achievementId: Int,
    val gameHash: String,
    val hardcore: Int,
    /** Wall-clock time of the unlock. Not a monotonic clock, as rcheevos uses, because this has
     *  to survive a restart or a reboot, which a monotonic clock does not. */
    val unlockedAtMs: Long,
    /** The user agent core segment of the session that earned it (RetroAchievementsClient.
     *  coreClause), so a late submission is still attributed to the core that earned it. */
    val core: String?,
)

internal object PendingUnlocks {
    private const val TAG = "TacoBoy.PendingUnlocks"
    private const val FILE_NAME = "pending_unlocks.json"

    private val lock = Any()

    fun all(context: Context): List<PendingUnlock> = synchronized(lock) { read(context) }

    /** Achievement ids waiting to be sent for [username], so a new session does not activate
     *  them again: RA would otherwise see them re-earned before the first unlock arrives. */
    fun idsFor(context: Context, username: String): Set<Int> =
        all(context).filter { it.username.equals(username, ignoreCase = true) }.map { it.achievementId }.toSet()

    fun add(context: Context, unlock: PendingUnlock) = synchronized(lock) {
        write(context, withUnlock(read(context), unlock))
    }

    fun remove(context: Context, unlock: PendingUnlock) = synchronized(lock) {
        write(context, read(context).filterNot { it.sameUnlock(unlock) })
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun read(context: Context): List<PendingUnlock> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        return try {
            decode(file.readText())
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Could not read the pending unlock queue", e)
            emptyList()
        }
    }

    /** Written to a temporary file and renamed over the old one, so a crash mid-write leaves
     *  the previous queue intact rather than a truncated file that decodes to nothing. */
    private fun write(context: Context, unlocks: List<PendingUnlock>) {
        val file = file(context)
        try {
            if (unlocks.isEmpty()) {
                file.delete()
                return
            }
            val temp = File(context.filesDir, "$FILE_NAME.tmp")
            temp.writeText(encode(unlocks))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Could not write the pending unlock queue", e)
        }
    }

    /** Adds [unlock] unless the same account's same achievement is already queued, in which
     *  case the earlier entry, with the earlier unlock time, is kept. */
    fun withUnlock(queue: List<PendingUnlock>, unlock: PendingUnlock): List<PendingUnlock> =
        if (queue.any { it.sameUnlock(unlock) }) queue else queue + unlock

    private fun PendingUnlock.sameUnlock(other: PendingUnlock) =
        achievementId == other.achievementId && username.equals(other.username, ignoreCase = true)

    fun encode(unlocks: List<PendingUnlock>): String {
        val array = JSONArray()
        unlocks.forEach {
            array.put(JSONObject().apply {
                put("username", it.username)
                put("achievementId", it.achievementId)
                put("gameHash", it.gameHash)
                put("hardcore", it.hardcore)
                put("unlockedAtMs", it.unlockedAtMs)
                if (it.core != null) put("core", it.core)
            })
        }
        return array.toString()
    }

    /** Skips any entry missing a field it cannot be sent without, rather than failing the
     *  whole file: one bad entry must not cost every other unlock in the queue. */
    fun decode(text: String): List<PendingUnlock> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val username = o.optString("username").ifEmpty { return@mapNotNull null }
            val gameHash = o.optString("gameHash").ifEmpty { return@mapNotNull null }
            val achievementId = o.optInt("achievementId", 0).takeIf { it > 0 } ?: return@mapNotNull null
            val unlockedAtMs = o.optLong("unlockedAtMs", 0L).takeIf { it > 0 } ?: return@mapNotNull null
            PendingUnlock(
                username = username,
                achievementId = achievementId,
                gameHash = gameHash,
                hardcore = o.optInt("hardcore", 0),
                unlockedAtMs = unlockedAtMs,
                core = o.optString("core").ifEmpty { null },
            )
        }
    }

    /** RA's `o` parameter, seconds since the unlock. A clock set backwards since the unlock
     *  gives 0, which omits the parameter, rather than a negative that would wrap. */
    fun secondsSinceUnlock(unlockedAtMs: Long, nowMs: Long): Long =
        ((nowMs - unlockedAtMs) / 1000).coerceIn(0L, UInt.MAX_VALUE.toLong())
}
