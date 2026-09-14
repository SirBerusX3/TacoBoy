package com.tacoboy

import android.content.Context
import com.tacoboy.RetroAchievementsClient.AchievementDefinition
import com.tacoboy.RetroAchievementsClient.AchievementInfo
import com.tacoboy.RetroAchievementsClient.GameProgress
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What live tracking fetched for a game the last time it was started online, so a game started
 * with no connection can still track achievements. Without it, starting a session needs RA to
 * identify the game and send its definitions, so an offline start tracks nothing and the unlock
 * queue (PendingUnlocks) never has anything to hold.
 *
 * One file per game, keyed by its RA hash, rewritten on every online start so it is as fresh as
 * the last time there was a connection. The trade-off, accepted with the user: offline, a game
 * runs on the definitions as they were then. If RA has since changed an achievement, the old
 * version is the one evaluated until the next online start. Unlocks still go to RA, which has
 * the final say when the queue sends them.
 *
 * App-private storage. Nothing here is a credential: achievement definitions are public, and the
 * earned flags belong to [Entry.username], which must match the account asking before it is used.
 */
internal object AchievementCache {
    private const val TAG = "TacoBoy.AchievementCache"
    private const val DIR_NAME = "achievement_cache"

    data class Entry(
        val username: String,
        val progress: GameProgress,
        val definitions: List<AchievementDefinition>,
        val cachedAtMs: Long,
    )

    fun save(context: Context, gameHash: String, entry: Entry) {
        val dir = File(context.filesDir, DIR_NAME)
        try {
            dir.mkdirs()
            val temp = File(dir, "$gameHash.json.tmp")
            val file = File(dir, "$gameHash.json")
            temp.writeText(encode(entry))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Could not cache achievement data for $gameHash", e)
        }
    }

    /** The cached entry for [gameHash], or null if there is none, it cannot be read, or it was
     *  fetched for a different account than [username]. */
    fun load(context: Context, gameHash: String, username: String): Entry? {
        val file = File(File(context.filesDir, DIR_NAME), "$gameHash.json")
        if (!file.exists()) return null
        return try {
            decode(file.readText())?.takeIf { it.username.equals(username, ignoreCase = true) }
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Could not read cached achievement data for $gameHash", e)
            null
        }
    }

    fun encode(entry: Entry): String = JSONObject().apply {
        put("username", entry.username)
        put("cachedAtMs", entry.cachedAtMs)
        put("gameId", entry.progress.gameId)
        put("gameTitle", entry.progress.gameTitle)
        entry.progress.iconUrl?.let { put("iconUrl", it) }
        put("achievements", JSONArray().apply {
            entry.progress.achievements.forEach {
                put(JSONObject().apply {
                    put("id", it.id)
                    put("title", it.title)
                    put("description", it.description)
                    put("points", it.points)
                    put("badgeName", it.badgeName)
                    put("displayOrder", it.displayOrder)
                    put("earned", it.earned)
                    put("earnedHardcore", it.earnedHardcore)
                })
            }
        })
        put("definitions", JSONArray().apply {
            entry.definitions.forEach { put(JSONObject().put("id", it.id).put("memAddr", it.memAddr)) }
        })
    }.toString()

    /** Null for anything that would make a broken session: no account, no game id, or no
     *  definitions at all. A single malformed achievement or definition is skipped instead. */
    fun decode(text: String): Entry? {
        val json = JSONObject(text)
        val username = json.optString("username").ifEmpty { return null }
        val gameId = json.optInt("gameId", 0).takeIf { it > 0 } ?: return null
        val achievementsJson = json.optJSONArray("achievements") ?: return null
        val definitionsJson = json.optJSONArray("definitions") ?: return null

        val achievements = (0 until achievementsJson.length()).mapNotNull { i ->
            val a = achievementsJson.optJSONObject(i) ?: return@mapNotNull null
            val id = a.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
            AchievementInfo(
                id = id,
                title = a.optString("title"),
                description = a.optString("description"),
                points = a.optInt("points"),
                badgeName = a.optString("badgeName"),
                displayOrder = a.optInt("displayOrder"),
                earned = a.optBoolean("earned"),
                earnedHardcore = a.optBoolean("earnedHardcore"),
            )
        }
        val definitions = (0 until definitionsJson.length()).mapNotNull { i ->
            val d = definitionsJson.optJSONObject(i) ?: return@mapNotNull null
            val id = d.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
            val memAddr = d.optString("memAddr").ifEmpty { return@mapNotNull null }
            AchievementDefinition(id, memAddr)
        }
        if (definitions.isEmpty()) return null

        return Entry(
            username = username,
            progress = GameProgress(
                gameId = gameId,
                gameTitle = json.optString("gameTitle"),
                iconUrl = json.optString("iconUrl").ifEmpty { null },
                achievements = achievements,
            ),
            definitions = definitions,
            cachedAtMs = json.optLong("cachedAtMs", 0L),
        )
    }
}
