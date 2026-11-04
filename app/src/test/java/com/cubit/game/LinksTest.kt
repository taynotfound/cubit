package com.cubit.game

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLDecoder

class LinksTest {
    private fun decoded(p: String) = URLDecoder.decode(p, "UTF-8")

    @Test fun challengeRoundTrips() {
        val moves = listOf(longArrayOf(4, 900), longArrayOf(-2, 1500), longArrayOf(UNDO_TAP.toLong(), 2100), longArrayOf(0, 5400))
        val c = Challenge(LevelSpec(12, 10, -987654321012L, lap = true), true, Difficulty.NIGHTMARE, "Tay_M é", moves)
        val p = Links.challengePayload(c)
        assert(p.all { it.isLetterOrDigit() || it in "-._~%" }) { "not URL-safe: $p" }
        val back = Links.parseChallenge(decoded(p))!!
        assertEquals(c.spec, back.spec)
        assertEquals(true, back.full)
        assertEquals(Difficulty.NIGHTMARE, back.mode)
        assertEquals("Tay_M é", back.name)
        moves.zip(back.moves).forEach { (a, b) -> assertArrayEquals(a, b) }
    }

    @Test fun badChallengesAreRejected() {
        assertNull(Links.parseChallenge(""))
        assertNull(Links.parseChallenge("1_99_10_a_0_1_1.a_X")) // cube too big
        assertNull(Links.parseChallenge("1_5_5_a_0_42_1.a_X")) // no such mode
        assertNull(Links.parseChallenge("2_5_5_a_0_1_1.a_X")) // unknown version
    }

    @Test fun packRoundTrips() {
        val levels = listOf(LevelSpec(5, 4, 1000292), LevelSpec(18, 10, -4000021))
        val pk = Links.parsePack(decoded(Links.packPayload("My pack", levels)))!!
        assertEquals("My pack", pk.name)
        assertEquals(levels, pk.levels)
        assertNull(Links.parsePack("1__x"))
    }

    @Test fun friendCodes() {
        assertEquals("AB12CD34", Links.cleanCode("ab12-cd34"))
        assertNull(Links.cleanCode("ab12"))
    }
}
