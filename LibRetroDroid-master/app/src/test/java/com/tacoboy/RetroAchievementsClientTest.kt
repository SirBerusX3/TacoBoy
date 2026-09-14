package com.tacoboy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the two pieces of live achievement tracking's networking layer that don't need
 * android.net.Uri (unavailable under a plain JVM unit test) or a real network call:
 * the r=awardachievement request-signing algorithm and the r=achievementsets response
 * parser. Both were split out of RetroAchievementsClient's public methods specifically to
 * make this possible -- see RetroAchievementsClient.kt's doc comments on
 * awardAchievementSignature/parseAchievementDefinitions.
 *
 * The signature expected value was computed independently in Python
 * (`hashlib.md5(...).hexdigest()`), same cross-check practice used for the CHD/PS1 work.
 * The response shape (`Sets[]`, each with `Type`/`Achievements[]`) reflects a real
 * correction made this session: the first attempt used `r=patch`, whose shape was read
 * from rcheevos' `rapi/rc_api_runtime.c` but which 401'd against a real, otherwise-working
 * account -- `rc_client.c` (the actual current high-level client) turned out to never call
 * that endpoint, only `r=achievementsets`. See RetroAchievementsClient.kt's doc comment on
 * getAchievementDefinitions and CHANGELOG.md for the full story.
 */
class RetroAchievementsClientTest {

    @Test
    fun `award achievement signature matches independently-computed MD5`() {
        val signature = RetroAchievementsClient.awardAchievementSignature(
            achievementId = 12345,
            username = "ChikinNuggit",
            hardcore = 0,
        )
        assertEquals("3de47d22c8af5ba602764b24c7fd47cc", signature)
    }

    @Test
    fun `award achievement signature changes with hardcore flag`() {
        val signature = RetroAchievementsClient.awardAchievementSignature(
            achievementId = 19,
            username = "ChikinNuggit",
            hardcore = 0,
        )
        assertEquals("9145cb7d7a0ce504ea227b0a63c6313c", signature)
    }

    @Test
    fun `parses core achievement definitions from a real-shaped achievementsets response`() {
        val response = JSONObject(
            """
            {
              "Success": true,
              "GameId": 4650,
              "Title": "Crash Bandicoot",
              "ConsoleId": 12,
              "Sets": [
                {
                  "AchievementSetId": 1, "GameId": 4650, "Title": "Crash Bandicoot", "Type": "core",
                  "Achievements": [
                    {"ID": 90000, "Title": "Getting Started", "MemAddr": "0xH001234=1", "Flags": 3, "Points": 5},
                    {"ID": 90001, "Title": "No MemAddr", "Flags": 3, "Points": 5}
                  ],
                  "Leaderboards": []
                },
                {
                  "AchievementSetId": 2, "GameId": 4650, "Title": "Bonus Set", "Type": "bonus",
                  "Achievements": [
                    {"ID": 90099, "Title": "Bonus Achievement", "MemAddr": "0xH001299=1", "Flags": 3, "Points": 5}
                  ],
                  "Leaderboards": []
                }
              ]
            }
            """.trimIndent()
        )

        val definitions = RetroAchievementsClient.parseAchievementDefinitions(response)!!

        assertEquals(1, definitions.size)
        assertEquals(RetroAchievementsClient.AchievementDefinition(90000, "0xH001234=1"), definitions[0])
    }

    @Test
    fun `returns null when Sets is missing`() {
        assertNull(RetroAchievementsClient.parseAchievementDefinitions(JSONObject("""{"Success": false}""")))
    }

    /** RA's requirements list this exact string as a valid user agent. Built through the same
     *  two functions TacoBoy uses, it must come out byte for byte, or the format was misread. */
    @Test
    fun `user agent reproduces RetroAchievements' own published example`() {
        val clause = RetroAchievementsClient.coreClause("genesis_plus_gx_libretro_android.so", "v1.7.4 8ea39ee")
        assertEquals(
            "RetroArch/1.20.0 (Android 13.0) genesis_plus_gx_libretro_android/v1.7.4_8ea39ee",
            RetroAchievementsClient.userAgent("RetroArch/1.20.0 (Android 13.0)", clause),
        )
    }

    /** RA's fbneo example has a double underscore, which is what replacing each space one for
     *  one gives from a version with two spaces. Collapsing them would not match. */
    @Test
    fun `every space becomes an underscore`() {
        assertEquals("fbneo_libretro/v1.0.0.03__e90b821", RetroAchievementsClient.coreClause("fbneo_libretro.so", "v1.0.0.03  e90b821"))
    }

    /** The version strings actually embedded in TacoBoy's rebuilt cores (CHANGELOG 2026-09-11). */
    @Test
    fun `core clauses for the shipped cores`() {
        assertEquals(
            "mednafen_psx_hw_libretro_android/0.9.44.1-GLES3_d97afa8",
            RetroAchievementsClient.coreClause("mednafen_psx_hw_libretro_android.so", "0.9.44.1-GLES3 d97afa8"),
        )
        assertEquals(
            "snes9x_libretro_android/1.60_bd9246d",
            RetroAchievementsClient.coreClause("snes9x_libretro_android.so", "1.60 bd9246d"),
        )
    }

    /** mGBA's file keeps an old `lib` prefix; RA should see the name the buildbot gives it. */
    @Test
    fun `a leading lib is dropped from the core name`() {
        assertEquals(
            "mgba_libretro_android/0.11-10126-e31759b",
            RetroAchievementsClient.coreClause("libmgba_libretro_android.so", "0.11-10126-e31759b"),
        )
    }

    /** A core that reports no version still names itself, with no dangling slash. */
    @Test
    fun `a missing version leaves just the core name`() {
        assertEquals("handy_libretro_android", RetroAchievementsClient.coreClause("handy_libretro_android.so", ""))
        assertEquals("handy_libretro_android", RetroAchievementsClient.coreClause("handy_libretro_android.so", "  "))
    }

    /** Library and Settings calls run with no game loaded, and send the first two segments only. */
    @Test
    fun `no core means no core segment`() {
        assertEquals("TacoBoy/0.2.1 (Android 16)", RetroAchievementsClient.userAgent("TacoBoy/0.2.1 (Android 16)", null))
        assertEquals("TacoBoy/0.2.1 (Android 16)", RetroAchievementsClient.userAgent("TacoBoy/0.2.1 (Android 16)", ""))
    }

    @Test
    fun `returns null when no core set is present`() {
        val response = JSONObject(
            """{"Sets": [{"Type": "bonus", "Achievements": []}]}"""
        )
        assertNull(RetroAchievementsClient.parseAchievementDefinitions(response))
    }
}
