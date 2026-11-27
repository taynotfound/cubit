package com.cubit.game

import java.util.Calendar
import java.util.TimeZone

/**
 * The advent calendar: 24 cubes, door 1 on 1 December (UTC) and one more each day to the 24th. Missed doors stay
 * open until 6 January, so a late start can still finish. The cubes grow from a small 7×7 to a 16×16 finale, the
 * same for everyone (the seed is the year and the door).
 */
object Advent {
    const val DOORS = 24

    /** The calendar's year and how many doors are open on [day] (days since 1970, UTC), or null outside the season. */
    fun season(day: Int): Pair<Int, Int>? {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = day * 86_400_000L }
        val y = c.get(Calendar.YEAR); val m = c.get(Calendar.MONTH); val d = c.get(Calendar.DAY_OF_MONTH)
        return when {
            m == Calendar.DECEMBER -> y to minOf(d, DOORS)
            m == Calendar.JANUARY && d <= 6 -> (y - 1) to DOORS
            else -> null
        }
    }

    /** The [k]th candidate for door [door] (1–24) of [year]. Bombs and the other special arrows join from door 8. */
    fun candidate(year: Int, door: Int, k: Int) = LevelSpec(
        n = (7 + door / 2).coerceAtMost(16),
        maxLen = (5 + door / 3).coerceAtMost(12),
        seed = year * 10_007L + door * 104_729L + k * 7_919L + 1225,
        special = door >= 8,
    )

    const val CANDIDATES = 8

    /**
     * How good a candidate is as a puzzle: the share of arrows that must go before the pink one can (deeper is
     * harder and more interesting), with a little weight on arrow count so bigger cubes don't lose to empty ones.
     */
    fun depthScore(p: Puzzle): Double = mustClear(p).toDouble() / p.paths.size.coerceAtLeast(1) + p.paths.size / 10_000.0

    /**
     * Door [door]'s cube: of [CANDIDATES] generated cubes, the one with the pink arrow buried deepest. Everyone's
     * phone picks the same one (generation is deterministic). Slow for big doors: call off the main thread.
     */
    fun pick(year: Int, door: Int, build: (LevelSpec) -> Puzzle = ::generate): LevelSpec =
        (0 until CANDIDATES).map { candidate(year, door, it) }.maxBy { depthScore(build(it)) }

    /** "Door 3 is open", "All 24 done", for the home card. */
    fun status(open: Int, done: Set<Int>): String = when {
        done.size >= DOORS -> "All $DOORS doors done · Happy holidays!"
        (1..open).all { it in done } -> "Door $open done · the next one opens tomorrow"
        else -> {
            val todo = (1..open).count { it !in done }
            if (open !in done) "Door $open is open" + (if (todo > 1) " · ${todo - 1} more to catch up" else "")
            else "$todo door${if (todo == 1) "" else "s"} to catch up"
        }
    }
}
