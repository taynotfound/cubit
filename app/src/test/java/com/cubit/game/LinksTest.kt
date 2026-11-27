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
        assertNull(Links.parseChallenge("3_5_5_a_0_1_1.a_X")) // unknown version
    }

    @Test fun linksFromBeforeSpecialsStayPlain() {
        // A 1.6 link to a 12×12 cube: its ghost was recorded without bombs, so it must replay without them.
        assertEquals(false, Links.parseChallenge("1_12_10_a_0_1_1.a_X")!!.spec.special)
        val now = Challenge(LevelSpec(12, 10, 77), false, Difficulty.NORMAL, "X", listOf(longArrayOf(0, 900)))
        assertEquals(true, Links.parseChallenge(decoded(Links.challengePayload(now)))!!.spec.special)
    }

    @Test fun packRoundTrips() {
        val levels = listOf(LevelSpec(5, 4, 1000292), LevelSpec(18, 10, -4000021))
        val pk = Links.parsePack(decoded(Links.packPayload("My pack", levels)))!!
        assertEquals("My pack", pk.name)
        assertEquals(levels, pk.levels)
        assertNull(Links.parsePack("1__x"))
    }

    @Test fun whatsNewReadsOneVersion() {
        val md = "# Changelog\n\n## 1.7.0 (2026-11-13)\n\n![gif](x.gif)\n\n- **World Cube**: big.\n- See [Ko-fi](https://ko-fi.com/x) now.\n\n## 1.6.1 (2026-11-05)\n\n- Old."
        assertEquals(listOf("**World Cube**: big.", "See Ko-fi now."), whatsNew(md, "1.7.0"))
        assertEquals(listOf("Old."), whatsNew(md, "1.6.1"))
        assertEquals(emptyList<String>(), whatsNew(md, "1.7"))
    }

    @Test fun whatsNewSinceCoversEveryVersionSkipped() {
        val md = "# Changelog\n\n## 1.7.1 (x)\n\n- A.\n\n## 1.7.0 (x)\n\n- B.\n\n## 1.6.1 (x)\n\n- C.\n"
        assertEquals(listOf("1.7.1" to listOf("A.")), whatsNewSince(md, "1.7.1", "1.7.0"))
        assertEquals(listOf("1.7.1" to listOf("A."), "1.7.0" to listOf("B.")), whatsNewSince(md, "1.7.1", "1.6.1"))
        assertEquals(listOf("1.7.1" to listOf("A."), "1.7.0" to listOf("B.")), whatsNewSince(md, "1.7.1", null)) // back to x.y.0
    }

    @Test fun theRealChangelogHasThisVersion() {
        val md = java.io.File("../CHANGELOG.md").readText()
        assert(whatsNew(md, "1.7.0").size >= 10)
    }

        @Test fun friendCodes() {
        assertEquals("AB12CD34", Links.cleanCode("ab12-cd34"))
        assertNull(Links.cleanCode("ab12"))
    }

    @Test fun packsKeepCubesWithoutSpecials() {
        // A daily cube saved to My pack is 12×12 with no special arrows: shared, it must stay that cube.
        val levels = listOf(dailySpec(20800), LevelSpec(12, 10, 5), LevelSpec(5, 4, 9))
        val p = Links.parsePack(decoded(Links.packPayload("Mine", levels)))!!
        assertEquals(levels, p.levels)
        // Older links (three fields per cube) still read as before.
        assertEquals(listOf(LevelSpec(12, 10, 35)), Links.parsePack("1_12.10.z_Old")!!.levels)
    }

    @Test fun challengeWithNoTapsReads() {
        val c = Challenge(LevelSpec(6, 4, 3), false, Difficulty.ZEN, "X", emptyList())
        assertEquals(0, Links.parseChallenge(decoded(Links.challengePayload(c)))!!.moves.size)
    }

    @Test fun resultTextReadsWithoutThePicture() {
        assertEquals("Cubit daily #18 🟪 freed in 21 taps (par 21) ⭐\n❤️❤️🖤 · ⏱ 2:13 · 1234 pts · 🔥 9-day streak\nhttps://x/open.html?daily=20776",
            resultText("Cubit daily #18", false, 21, 21, 3, 2, 1, 133, 1234, 9, "https://x/open.html?daily=20776"))
        // No lives (Zen, Clean): bumps instead of hearts; no streak line for a streak of one.
        assertEquals("Cubit · Level 3 🟪 cleared in 40 taps (par 38)\n💥 1 bump · ⏱ 0:59 · 50 pts\nL",
            resultText("Cubit · Level 3", true, 40, 38, 0, 0, 1, 59, 50, 1, "L"))
        // A bomb can take an arrow in the pink one's way along, so a run can beat par.
        assertEquals("Cubit · Level 12 🟪 freed in 11 taps (par 12) ⭐ under par\n❤️❤️❤️ · ⏱ 0:37 · 655 pts\nL",
            resultText("Cubit · Level 12", false, 11, 12, 3, 3, 0, 37, 655, 1, "L"))
    }
}
