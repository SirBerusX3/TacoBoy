package com.tacoboy

import android.net.Uri
import android.os.Build
import com.android.libretrodroid.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * RetroAchievements auth turns out to be two genuinely different tiers, discovered the
 * hard way (both confirmed live against a real account, corrected after each got it
 * wrong first — see CHANGELOG.md 2026-08-15):
 *
 * 1. **The permanent Web API key** (from the user's RA profile Settings page) — no
 *    password ever needed. Works directly as the Connect API's `t` parameter for
 *    identification-flavored calls (`r=gameid`, confirmed live) and for the whole
 *    read-only Web API (`retroachievements.org/API/...`, used by `verifyCredentials`/
 *    `getGameProgress`). `identifyGameId`/`getGameProgress`/`verifyCredentials` all use
 *    this tier — callers pass `apiKey`.
 *
 * 2. **A real login session token**, only obtainable via `r=login2` with the user's
 *    actual account *password* (not the API key — passing the key as `login2`'s `t=`
 *    param also 401s, confirmed live). Required for every session/tracking-flavored
 *    Connect API call: `r=achievementsets` (fetch achievement trigger definitions),
 *    `r=ping`, `r=postactivity`, and `r=awardachievement` all 401 on the permanent key
 *    even though `r=gameid` accepts it fine with the exact same account. `login()` is
 *    the one-time password exchange (`rc_client_begin_login_with_password`'s Connect-API
 *    equivalent, per the vendored rcheevos' `rc_client.c`/wiki docs); the returned token
 *    is cached (TacoBoyPrefs' session-token slot, not the password) and reused
 *    indefinitely — RA's own docs describe this as the intended pattern specifically so
 *    the password itself never needs storing. `getAchievementDefinitions`/
 *    `awardAchievement` need this tier — callers pass `sessionToken`, not `apiKey`.
 *
 * Live tracking (AchievementsSession.kt) is therefore gated on the user completing a
 * *separate* login (Settings' "Live Tracking" section) beyond the identification-only
 * username+key login every other RA feature in this app already only ever needed.
 */
object RetroAchievementsClient {
    private const val TAG = "TacoBoy.RetroAchievementsClient"
    private const val WEB_API_BASE_URL = "https://retroachievements.org/API"
    private const val CONNECT_API_BASE_URL = "https://retroachievements.org/dorequest.php"
    private const val MEDIA_BASE_URL = "https://media.retroachievements.org"
    private const val BADGE_BASE_URL = "https://i.retroachievements.org/Badge"
    private const val TIMEOUT_MS = 10_000
    // Connect API docs are explicit that every dorequest.php request needs a User-Agent
    // identifying the calling frontend — requests without one are rejected. No app version to
    // embed here (this fork's build.gradle has no versionName), so this is a fixed identifier.
    /** RetroAchievements identifies clients by user agent, and their rules treat a
     *  non-unique one as an auto-fail, so this must stay distinctive AND truthful.
     *  It read "TacoBoy/1.0" until 2026-09-10 while versionName was 0.1.0 -- unique,
     *  but reporting a version that never existed, which makes anything RA sees from
     *  the field impossible to tie back to a build. Built from BuildConfig now, so it
     *  cannot drift from the manifest again.
     *
     *  Still missing the active core, which the compliance audit's C1 asks for
     *  (emulator and core are separate fields in RA's format). That needs the client
     *  to know which core is loaded and is left for when it does. */
    private val USER_AGENT =
        "TacoBoy/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"

    /** `valid` false covers both a network failure and RA actually rejecting the
     *  credentials (401) — verifyCredentials doesn't distinguish them since either
     *  way there's nothing to save and the same "couldn't verify" message applies. */
    data class VerifyResult(val valid: Boolean, val confirmedUsername: String?)

    /** `token` is the session token live-tracking calls need as `t=` going forward — never
     *  the password, which is discarded the instant this returns. */
    data class LoginResult(val success: Boolean, val confirmedUsername: String?, val token: String?)

    /**
     * Exchanges the user's real RA account password for a session token, via the Connect
     * API's `r=login2` (POST — matches `rc_api_init_login_request_hosted` in the vendored
     * rcheevos exactly, and keeps the password out of a GET URL/server access log). This
     * is the *only* place a password ever touches this app; it's used for this one
     * request and never persisted — see the class doc comment for why this separate,
     * heavier login exists at all.
     */
    fun login(username: String, password: String): LoginResult {
        val body = "r=login2&u=${Uri.encode(username)}&p=${Uri.encode(password)}"
        return try {
            val json = post(CONNECT_API_BASE_URL, body) ?: return LoginResult(false, null, null)
            if (!json.optBoolean("Success", false)) return LoginResult(false, null, null)
            val token = json.optString("Token").ifEmpty { null } ?: return LoginResult(false, null, null)
            LoginResult(true, json.optString("User").ifEmpty { username }, token)
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Login failed", e)
            LoginResult(false, null, null)
        }
    }

    fun verifyCredentials(username: String, apiKey: String): VerifyResult {
        val url = "$WEB_API_BASE_URL/API_GetUserSummary.php" +
            "?u=${Uri.encode(username)}&y=${Uri.encode(apiKey)}"
        return try {
            val json = get(url) ?: return VerifyResult(false, null)
            val confirmedUsername = json.optString("User").ifEmpty { null }
            VerifyResult(confirmedUsername != null, confirmedUsername)
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Credential verification failed", e)
            VerifyResult(false, null)
        }
    }

    /**
     * Looks up the game a hash belongs to. Returns null only on request
     * failure (network error, unparsable response) — a successfully
     * answered "no match" comes back as 0, RA's own convention for an
     * unrecognized hash (confirmed live), and is a normal outcome (homebrew,
     * romhacks, or a hash rule this app hasn't implemented correctly yet),
     * not an error.
     */
    fun identifyGameId(username: String, apiKey: String, md5: String): Int? {
        val url = "$CONNECT_API_BASE_URL?r=gameid&u=${Uri.encode(username)}&t=${Uri.encode(apiKey)}&m=$md5"
        return try {
            val json = get(url) ?: return null
            json.optInt("GameID", 0)
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Game identification failed", e)
            null
        }
    }

    /** One achievement, merged with this user's progress on it — `earned`/`earnedHardcore`
     *  reflect whether RA's response included a `DateEarned`/`DateEarnedHardcore` field at
     *  all (confirmed live: absent, not null, when unearned — see getGameProgress). */
    data class AchievementInfo(
        val id: Int,
        val title: String,
        val description: String,
        val points: Int,
        val badgeName: String,
        val displayOrder: Int,
        val earned: Boolean,
        val earnedHardcore: Boolean,
    ) {
        val badgeUrl: String get() = "$BADGE_BASE_URL/$badgeName${if (earned) "" else "_lock"}.png"
    }

    data class GameProgress(
        val gameId: Int,
        val gameTitle: String,
        val iconUrl: String?,
        /** Sorted by RA's own DisplayOrder, not JSON object key order — the response's
         *  "Achievements" is a JSON object keyed by achievement ID, which carries no
         *  guaranteed ordering at all, let alone the game's intended progression order. */
        val achievements: List<AchievementInfo>,
    )

    /**
     * Fetches a game's full achievement list pre-merged with this user's earned status —
     * confirmed live against a real account's data (see CHANGELOG.md 2026-08-15): the
     * response uses PascalCase field names throughout (`ID`, `Title`, `BadgeName`, ...),
     * not the lowercase shape a first read of the docs suggested, and `DateEarned`/
     * `DateEarnedHardcore` are simply absent from an achievement's object when it hasn't
     * been earned rather than present-but-null.
     */
    fun getGameProgress(username: String, apiKey: String, gameId: Int): GameProgress? {
        val url = "$WEB_API_BASE_URL/API_GetGameInfoAndUserProgress.php" +
            "?u=${Uri.encode(username)}&y=${Uri.encode(apiKey)}&g=$gameId"
        return try {
            val json = get(url) ?: return null
            val title = json.optString("Title").ifEmpty { return null }
            val iconPath = json.optString("ImageIcon").ifEmpty { null }
            val achievementsJson = json.optJSONObject("Achievements") ?: JSONObject()
            val achievements = achievementsJson.keys().asSequence().mapNotNull { key ->
                val a = achievementsJson.optJSONObject(key) ?: return@mapNotNull null
                AchievementInfo(
                    id = a.optInt("ID"),
                    title = a.optString("Title"),
                    description = a.optString("Description"),
                    points = a.optInt("Points"),
                    badgeName = a.optString("BadgeName"),
                    displayOrder = a.optInt("DisplayOrder"),
                    earned = a.has("DateEarned"),
                    earnedHardcore = a.has("DateEarnedHardcore"),
                )
            }.sortedBy { it.displayOrder }.toList()
            GameProgress(gameId, title, iconPath?.let { "$MEDIA_BASE_URL$it" }, achievements)
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Fetching game progress failed", e)
            null
        }
    }

    /** One achievement's trigger condition string, exactly as RA's own rc_runtime_t needs
     *  it (id + MemAddr) -- everything else about an achievement (title/points/badge) is
     *  already covered by GameProgress.achievements, fetched separately. Only achievements
     *  from the "core" set are surfaced (see parseAchievementDefinitions) -- bonus/
     *  specialty/exclusive sets are a newer RA concept (alternate/expanded achievement
     *  sets for a game) this app has no business activating by default. */
    data class AchievementDefinition(val id: Int, val memAddr: String)

    /**
     * Fetches every core-set achievement's trigger-condition string for live tracking.
     *
     * **Corrected against a live call, not just source-read** (this project's established
     * practice, see CHANGELOG.md): first attempt used Connect API `r=patch` ("fetch game
     * data"), matching `rc_api_init_fetch_game_data_request` in the vendored rcheevos
     * `rapi/rc_api_runtime.c` -- that 401'd live with valid credentials that work fine for
     * every other call in this file. Reading `rc_client.c` (the actual current high-level
     * client, not just the lower-level rapi helpers) showed it never calls
     * `fetch_game_data`/`r=patch` at all -- only `rc_api_init_fetch_game_sets_request`,
     * i.e. `r=achievementsets`. That's what's implemented here. Response shape is also
     * different from the old `r=patch` shape: a top-level `Sets[]` array (not
     * `PatchData.Achievements[]`), each set carrying its own `Type` ("core"/"bonus"/
     * "specialty"/"exclusive") and `Achievements[]` (same per-achievement fields as
     * before -- `ID`/`MemAddr`/etc).
     */
    fun getAchievementDefinitions(username: String, sessionToken: String, gameId: Int): List<AchievementDefinition>? {
        val url = "$CONNECT_API_BASE_URL?r=achievementsets&u=${Uri.encode(username)}&t=${Uri.encode(sessionToken)}&g=$gameId"
        return try {
            val json = get(url) ?: return null
            parseAchievementDefinitions(json)
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Fetching achievement definitions failed", e)
            null
        }
    }

    /** Split out from getAchievementDefinitions so the parsing logic is testable against a
     *  captured/synthetic response without a network call (org.json.JSONObject itself is
     *  just a plain parser, unlike android.net.Uri -- no Android runtime needed). */
    internal fun parseAchievementDefinitions(response: JSONObject): List<AchievementDefinition>? {
        val sets = response.optJSONArray("Sets") ?: return null
        val coreSet = (0 until sets.length())
            .mapNotNull { sets.optJSONObject(it) }
            .firstOrNull { it.optString("Type") == "core" } ?: return null
        val achievementsJson = coreSet.optJSONArray("Achievements") ?: return null
        return (0 until achievementsJson.length()).mapNotNull { i ->
            val a = achievementsJson.optJSONObject(i) ?: return@mapNotNull null
            val memAddr = a.optString("MemAddr").ifEmpty { return@mapNotNull null }
            AchievementDefinition(id = a.optInt("ID"), memAddr = memAddr)
        }
    }

    /**
     * Submits a real-time unlock — RA's Connect API `r=awardachievement`. Softcore only
     * (hardcore mode, which disables save-states, is out of scope — see
     * CHANGELOG.md/roadmap). The `v=` request-signature algorithm is exact, taken
     * directly from `rc_api_init_award_achievement_request_hosted` in the vendored
     * `rapi/rc_api_runtime.c`, not guessed: MD5(achievementId + username + hardcoreFlag),
     * each concatenated as their plain decimal-string form, hex-encoded lowercase.
     */
    fun awardAchievement(username: String, sessionToken: String, achievementId: Int, gameHash: String): Boolean {
        val validation = awardAchievementSignature(achievementId, username, hardcore = 0)
        val url = "$CONNECT_API_BASE_URL?r=awardachievement&u=${Uri.encode(username)}&t=${Uri.encode(sessionToken)}" +
            "&a=$achievementId&h=0&m=${Uri.encode(gameHash)}&v=$validation"
        return try {
            val json = get(url) ?: return false
            json.optBoolean("Success", false)
        } catch (e: Exception) {
            TacoBoyLog.e(TAG, "Awarding achievement $achievementId failed", e)
            false
        }
    }

    /** MD5(achievementId + username + hardcoreFlag), each concatenated as their plain
     *  decimal-string form -- split out from awardAchievement (which also needs
     *  android.net.Uri, not available under a plain JVM unit test) so this pure,
     *  deterministic piece is directly testable against a known input/expected-hash pair. */
    internal fun awardAchievementSignature(achievementId: Int, username: String, hardcore: Int): String {
        return md5("$achievementId$username$hardcore")
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun get(url: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection?.disconnect()
        }
    }

    private fun post(url: String, urlEncodedBody: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "POST"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                doOutput = true
            }
            connection.outputStream.use { it.write(urlEncodedBody.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection?.disconnect()
        }
    }
}
