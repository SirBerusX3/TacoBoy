package com.tacoboy

import org.junit.Assert.assertEquals
import org.junit.Test

/** The queue file is the only copy of an unlock earned offline, so its format and its
 *  handling of damage are what decide whether one survives. */
class PendingUnlocksTest {
    private val unlock = PendingUnlock(
        username = "ChikinNuggit",
        achievementId = 12345,
        gameHash = "0123456789abcdef0123456789abcdef",
        hardcore = 0,
        unlockedAtMs = 1_757_851_200_000L,
        core = "mednafen_psx_hw_libretro_android/0.9.44.1-GLES3_d97afa8",
    )

    @Test
    fun `an unlock survives the round trip through the file`() {
        val noCore = unlock.copy(achievementId = 19, core = null)
        assertEquals(listOf(unlock, noCore), PendingUnlocks.decode(PendingUnlocks.encode(listOf(unlock, noCore))))
    }

    /** One entry that cannot be sent must not cost the others. */
    @Test
    fun `an unusable entry is skipped and the rest kept`() {
        val text = """[
            {"username": "ChikinNuggit", "achievementId": 0, "gameHash": "aa", "unlockedAtMs": 5},
            {"achievementId": 7, "gameHash": "aa", "unlockedAtMs": 5},
            {"username": "ChikinNuggit", "achievementId": 7, "gameHash": "aa", "unlockedAtMs": 5}
        ]"""
        assertEquals(listOf(7), PendingUnlocks.decode(text).map { it.achievementId })
    }

    /** The first unlock time is the true one; a second trigger of the same achievement keeps it. */
    @Test
    fun `queueing the same achievement twice keeps the first`() {
        val again = unlock.copy(unlockedAtMs = unlock.unlockedAtMs + 60_000, username = "chikinnuggit")
        assertEquals(listOf(unlock), PendingUnlocks.withUnlock(listOf(unlock), again))
    }

    @Test
    fun `the same achievement for another account is a separate unlock`() {
        val other = unlock.copy(username = "SomeoneElse")
        assertEquals(listOf(unlock, other), PendingUnlocks.withUnlock(listOf(unlock), other))
    }

    @Test
    fun `seconds since unlock`() {
        assertEquals(90L, PendingUnlocks.secondsSinceUnlock(1_000_000L, 1_090_999L))
        assertEquals(3 * 24 * 3600L, PendingUnlocks.secondsSinceUnlock(0L + 1, 3 * 24 * 3600_000L + 1))
    }

    /** A clock set back since the unlock must give 0, which omits `o`, not a negative. */
    @Test
    fun `a clock set backwards gives zero`() {
        assertEquals(0L, PendingUnlocks.secondsSinceUnlock(2_000_000L, 1_000_000L))
    }

    @Test
    fun `retry delays follow rcheevos`() {
        val delays = (1..11).map { UnlockSync.retryDelaySeconds(it) }
        assertEquals(listOf(0L, 1L, 2L, 4L, 8L, 16L, 32L, 64L, 120L, 120L, 120L), delays)
    }
}
