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
    /** RetroAchievements identifies clients by user agent, and their rules treat a
     *  non-unique one as an auto-fail, so this must stay distinctive AND truthful.
     *  It read "TacoBoy/1.0" until 2026-09-10 while versionName was 0.1.0 -- unique,
     *  but reporting a version that never existed, which makes anything RA sees from
     *  the field impossible to tie back to a build. Built from BuildConfig now, so it
     *  cannot drift from the manifest again.
     *
     *  This is only the first two segments of RA's format; calls made while a game is
     *  running append the core as a third (see userAgent). */
    private val USER_AGENT_PREFIX =
        "TacoBoy/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"

    /** RA's user agent format is `EmulatorName/v1.0.0 (OSName 10.0) core_name/v0.5.0`, the
     *  core segment "strongly advised" for multi-core emulators (compliance audit C1b).
     *  Calls made from the library or Settings, with no game running, send no core segment,
     *  as RetroArch does. */
    internal fun userAgent(prefix: String, coreClause: String?): String =
        if (coreClause.isNullOrEmpty()) prefix else "$prefix $coreClause"

    /** `genesis_plus_gx_libretro_android/v1.7.4_b7e79b3`. Follows RetroArch's
     *  `rcheevos_get_user_agent` (cheevos_client.c), which RA's own examples come from: the
     *  core's file name without its extension, then `/` and the core's self-reported
     *  library_version, with spaces in either turned into underscores. The version is read
     *  from the running core rather than recorded here, so a rebuilt core cannot leave a
     *  stale one behind.
     *
     *  One deliberate difference: a leading `lib` is dropped. TacoBoy's mGBA file carries
     *  one only because it kept an old filename (CHANGELOG 2026-08-24); the buildbot and
     *  RetroArch call the same core `mgba_libretro_android`, and RA should see one name
     *  for one core regardless of which frontend's packaging it came through. */
    internal fun coreClause(coreFileName: String, libraryVersion: String): String {
        val name = coreFileName.substringBeforeLast('.').removePrefix("lib").replace(' ', '_')
        val version = libraryVersion.trim().replace(' ', '_')
        return if (version.isEmpty()) name else "$name/$version"
    }

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
    fun identifyGameId(username: String, apiKey: String, md5: String, core: String? = null): Int? {
        val url = "$CONNECT_API_BASE_URL?r=gameid&u=${Uri.encode(username)}&t=${Uri.encode(apiKey)}&m=$md5"
        return try {
            val json = get(url, core) ?: return null
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
    fun getGameProgress(username: String, apiKey: String, gameId: Int, core: String? = null): GameProgress? {
        val url = "$WEB_API_BASE_URL/API_GetGameInfoAndUserProgress.php" +
            "?u=${Uri.encode(username)}&y=${Uri.encode(apiKey)}&g=$gameId"
        return try {
            val json = get(url, core) ?: return null
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
    fun getAchievementDefinitions(
        username: String,
        sessionToken: String,
        gameId: Int,
        core: String? = null,
    ): List<AchievementDefinition>? {
        val url = "$CONNECT_API_BASE_URL?r=achievementsets&u=${Uri.encode(username)}&t=${Uri.encode(sessionToken)}&g=$gameId"
        return try {
            val json = get(url, core) ?: return null
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

    /** What became of one unlock submission, and so what the queue does with it. */
    enum class AwardOutcome {
        /** RA accepted it, or already had it. Remove from the queue. */
        AWARDED,

        /** RA answered with a definite refusal (an unknown achievement, say). Retrying cannot
         *  change the answer, so remove it. */
        REJECTED,

        /** No answer, or a transient one (a timeout, maintenance, rate limiting). Keep it. */
        RETRY,

        /** RA refused the credentials. Keep it and stop: every other entry would fail the same
         *  way, and a fresh login is what sends them. */
        AUTH_FAILED,
    }

    /**
     * Submits an unlock -- RA's Connect API `r=awardachievement`. Softcore only for now (a
     * hardcore submission path is roadmap 6.3, a stretch goal -- see
     * RETROACHIEVEMENTS-COMPLIANCE.md). The request, including the `v=` signature, follows
     * `rc_api_init_award_achievement_request_hosted` in the vendored `rapi/rc_api_runtime.c`
     * exactly; see awardAchievementSignature.
     *
     * [secondsSinceUnlock] is RA's `o` parameter, which rcheevos sends on every retry so a late
     * submission records when the achievement was really earned. 0 omits it, as rcheevos does.
     */
    fun awardAchievement(
        username: String,
        sessionToken: String,
        achievementId: Int,
        gameHash: String,
        secondsSinceUnlock: Long = 0,
        core: String? = null,
    ): AwardOutcome {
        val validation = awardAchievementSignature(achievementId, username, hardcore = 0, secondsSinceUnlock)
        val url = "$CONNECT_API_BASE_URL?r=awardachievement&u=${Uri.encode(username)}&t=${Uri.encode(sessionToken)}" +
            "&a=$achievementId&h=0&m=${Uri.encode(gameHash)}" +
            (if (secondsSinceUnlock > 0) "&o=$secondsSinceUnlock" else "") +
            "&v=$validation"
        val response = request(url, core)
        val outcome = classifyAwardResponse(response.status, response.body)
        if (outcome != AwardOutcome.AWARDED) {
            // RA's own Error text, when there is one, says why far better than the status does.
            val error = try { response.body?.let { JSONObject(it).optString("Error") } } catch (e: Exception) { null }
            TacoBoyLog.e(TAG, "Awarding achievement $achievementId: $outcome (HTTP ${response.status ?: "no response"}" +
                (if (!error.isNullOrEmpty()) ", \"$error\")" else ")"))
        }
        return outcome
    }

    /** Transient statuses, copied from `rc_client_should_retry` in the vendored `rc_client.c`:
     *  rate limiting, gateway and Cloudflare failures, maintenance mode. */
    private val RETRYABLE_STATUSES = setOf(429, 502, 503, 504, 521, 522, 523, 524, 525)

    /**
     * Decides what to do with an award response, following rcheevos' own client
     * (`rc_client_award_achievement_callback` and `rc_client_should_retry`) so TacoBoy gives up
     * and retries in the same cases RetroArch does:
     *
     *  - `Success: true` is awarded, including RA's "already unlocked", which it returns as a
     *    success carrying an error message.
     *  - A refusal only counts as final when RA sent an actual `Error` and the status is not a
     *    transient one. No response, an empty body, or a body that is not RA's JSON is retried.
     *
     * One deliberate difference: rcheevos treats a refused login like any other final error and
     * drops the unlock. Its retries live in memory, so that loses little. TacoBoy's queue is on
     * disk precisely so unlocks outlive problems like this one, so a 401 or 403 is kept for the
     * next login instead.
     *
     * [status] null means no response at all.
     */
    internal fun classifyAwardResponse(status: Int?, body: String?): AwardOutcome {
        if (status == null || body.isNullOrBlank()) return AwardOutcome.RETRY
        if (status == 401 || status == 403) return AwardOutcome.AUTH_FAILED
        if (status in RETRYABLE_STATUSES) return AwardOutcome.RETRY
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return AwardOutcome.RETRY
        }
        if (json.optBoolean("Success", false)) return AwardOutcome.AWARDED
        return if (json.optString("Error").isNotEmpty()) AwardOutcome.REJECTED else AwardOutcome.RETRY
    }

    /** MD5(achievementId + username + hardcoreFlag), each as its plain decimal string -- and,
     *  for a delayed unlock, the achievement id again and the seconds since unlock appended, as
     *  `rc_api_init_award_achievement_request_hosted` does whenever it sends `o`. Split out from
     *  awardAchievement (which also needs android.net.Uri, not available under a plain JVM unit
     *  test) so it is directly testable against independently computed hashes. */
    internal fun awardAchievementSignature(
        achievementId: Int,
        username: String,
        hardcore: Int,
        secondsSinceUnlock: Long = 0,
    ): String {
        val delayed = if (secondsSinceUnlock > 0) "$achievementId$secondsSinceUnlock" else ""
        return md5("$achievementId$username$hardcore$delayed")
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** `core` is a coreClause, passed only by calls made while a game is running. */
    private fun get(url: String, core: String? = null): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent(USER_AGENT_PREFIX, core))
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection?.disconnect()
        }
    }

    private class HttpResponse(val status: Int?, val body: String?)

    /** A GET that keeps what get() throws away: the status and body of a non-200 response,
     *  which is how an award is told apart from a transient failure. A null status is no
     *  response at all -- no network, DNS failure, a timeout. */
    private fun request(url: String, core: String?): HttpResponse {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent(USER_AGENT_PREFIX, core))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(status, stream?.bufferedReader()?.use { it.readText() })
        } catch (e: Exception) {
            HttpResponse(null, null)
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
                setRequestProperty("User-Agent", USER_AGENT_PREFIX)
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
