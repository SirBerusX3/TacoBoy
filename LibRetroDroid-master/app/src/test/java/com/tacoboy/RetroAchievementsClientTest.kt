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

    @Test
    fun `returns null when no core set is present`() {
        val response = JSONObject(
            """{"Sets": [{"Type": "bonus", "Achievements": []}]}"""
        )
        assertNull(RetroAchievementsClient.parseAchievementDefinitions(response))
    }
}
