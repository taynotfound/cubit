package com.cubit.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdventTest {
    private fun day(y: Int, m: Int, d: Int) = (java.time.LocalDate.of(y, m, d).toEpochDay()).toInt()

    @Test fun season() {
        assertNull(Advent.season(day(2026, 11, 30)))
        assertEquals(2026 to 1, Advent.season(day(2026, 12, 1)))
        assertEquals(2026 to 24, Advent.season(day(2026, 12, 24)))
        assertEquals(2026 to 24, Advent.season(day(2026, 12, 31)))
        assertEquals(2026 to 24, Advent.season(day(2027, 1, 6)))
        assertNull(Advent.season(day(2027, 1, 7)))
    }

    @Test fun doorsGrowAndPickTheDeepest() {
        assertEquals(7, Advent.candidate(2026, 1, 0).n)
        assertEquals(16, Advent.candidate(2026, 24, 0).n)
        assertEquals(false, Advent.candidate(2026, 7, 0).special)
        // The pick is the same on every phone, and is the best of the candidates.
        val pick = Advent.pick(2026, 3)
        assertEquals(pick, Advent.pick(2026, 3))
        val best = (0 until Advent.CANDIDATES).maxOf { Advent.depthScore(generate(Advent.candidate(2026, 3, it))) }
        assertEquals(best, Advent.depthScore(generate(pick)), 1e-9)
    }

    @Test fun status() {
        assertEquals("Door 3 is open · 2 more to catch up", Advent.status(3, emptySet()))
        assertEquals("Door 3 done · the next one opens tomorrow", Advent.status(3, setOf(1, 2, 3)))
        assertEquals("1 door to catch up", Advent.status(3, setOf(1, 3)))
        assertEquals("All 24 doors done · Happy holidays!", Advent.status(24, (1..24).toSet()))
    }
}
