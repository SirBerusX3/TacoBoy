package com.tacoboy

import org.junit.Assert.assertEquals
import org.junit.Test

/** The challenge indicator sits over the game, so it shows at most a few titles and counts the rest. */
class AchievementsSessionTest {
    @Test
    fun `up to the limit every challenge is listed`() {
        assertEquals(listOf("A", "B", "C") to 0, AchievementsSession.challengeIndicatorLines(listOf("A", "B", "C"), 3))
        assertEquals(emptyList<String>() to 0, AchievementsSession.challengeIndicatorLines(emptyList(), 3))
    }

    @Test
    fun `past the limit the rest are counted`() {
        assertEquals(listOf("A", "B", "C") to 2, AchievementsSession.challengeIndicatorLines(listOf("A", "B", "C", "D", "E"), 3))
    }
}
