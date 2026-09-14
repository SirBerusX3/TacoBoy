package com.tacoboy

import com.tacoboy.RetroAchievementsClient.AchievementDefinition
import com.tacoboy.RetroAchievementsClient.AchievementInfo
import com.tacoboy.RetroAchievementsClient.GameProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** An offline session is only as good as what comes back out of this file, so a round trip
 *  must be exact and a damaged file must never start a session with nothing to track. */
class AchievementCacheTest {
    private val entry = AchievementCache.Entry(
        username = "ChikinNuggit",
        progress = GameProgress(
            gameId = 4650,
            gameTitle = "Crash Bandicoot",
            iconUrl = "https://media.retroachievements.org/Images/012345.png",
            achievements = listOf(
                AchievementInfo(90000, "Getting Started", "Finish the first level", 5, "12345", 1, earned = true, earnedHardcore = false),
                AchievementInfo(90001, "Keep Going", "Finish the second level", 10, "12346", 2, earned = false, earnedHardcore = false),
            ),
        ),
        definitions = listOf(
            AchievementDefinition(90000, "0xH001234=1"),
            AchievementDefinition(90001, "0xH001234=2_0xH005678>=3"),
        ),
        cachedAtMs = 1_757_851_200_000L,
    )

    @Test
    fun `a cached game survives the round trip exactly`() {
        assertEquals(entry, AchievementCache.decode(AchievementCache.encode(entry)))
    }

    @Test
    fun `a game with no icon round trips too`() {
        val noIcon = entry.copy(progress = entry.progress.copy(iconUrl = null))
        assertEquals(noIcon, AchievementCache.decode(AchievementCache.encode(noIcon)))
    }

    /** No definitions means nothing to evaluate; better no session than an empty one. */
    @Test
    fun `a file with nothing to track is refused`() {
        assertNull(AchievementCache.decode(AchievementCache.encode(entry.copy(definitions = emptyList()))))
    }

    @Test
    fun `a file without an account or game id is refused`() {
        assertNull(AchievementCache.decode(AchievementCache.encode(entry.copy(username = ""))))
        assertNull(AchievementCache.decode(AchievementCache.encode(entry.copy(progress = entry.progress.copy(gameId = 0)))))
    }

    @Test
    fun `a malformed definition is skipped and the rest kept`() {
        val text = AchievementCache.encode(entry).replace("\"memAddr\":\"0xH001234=1\"", "\"memAddr\":\"\"")
        assertEquals(listOf(90001), AchievementCache.decode(text)!!.definitions.map { it.id })
    }
}
