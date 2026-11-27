package com.cubit.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WrappedTest {
    private fun day(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d).toEpochDay().toInt()

    @Test fun season() {
        assertNull(WrappedYear.season(day(2026, 12, 9)))
        assertEquals(2026, WrappedYear.season(day(2026, 12, 10)))
        assertEquals(2026, WrappedYear.season(day(2027, 1, 15)))
        assertNull(WrappedYear.season(day(2027, 1, 16)))
        assertEquals(day(2026, 1, 1) until day(2027, 1, 1), WrappedYear.days(2026))
    }

    @Test fun lines() {
        val w = Wrapped(2026, 1234, 56789, 31, 12, 17, 9, 40, "Normal", "Level 3" to 21_400L, "Daily" to 2100, 24, 0, 7300, 900)
        val l = WrappedYear.lines(w).toMap()
        assertEquals("1,234", l["Cubes cleared"])
        assertEquals("12 days", l["Best streak"])
        assertEquals("0:21.4", l["Fastest win"])
        assertEquals("2h 01m", l["Time solving"])
        assertEquals("24 of 24", l["Advent doors"])
        assertNull(l["Endless record"])
        assertEquals("My 2026 in Cubit 🟪 1,234 cubes cleared, 56,789 arrows freed, best streak 12 days 🔥. What was yours?\n#CubitWrapped\nX",
            WrappedYear.shareText(w, "X"))
    }
}
