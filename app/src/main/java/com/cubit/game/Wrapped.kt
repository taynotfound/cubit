package com.cubit.game

import java.util.Calendar
import java.util.TimeZone

/** A year in Cubit, from what this phone kept. Cubit 1.0 came out in November 2026, so 2026's covers everything. */
class Wrapped(
    val year: Int, val wins: Int, val arrows: Int, val dailies: Int, val bestStreak: Int, val level: Int, val badges: Int,
    val parWins: Int, val favouriteMode: String?, val fastest: Pair<String, Long>?, val topScore: Pair<String, Int>?,
    val adventDoors: Int, val endlessBest: Int, val secondsSolving: Int, val players: Int,
)

object WrappedYear {
    /** Which year's recap shows on [day] (days since 1970, UTC): from 10 December to 15 January, else none. */
    fun season(day: Int): Int? {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = day * 86_400_000L }
        val y = c.get(Calendar.YEAR); val m = c.get(Calendar.MONTH); val d = c.get(Calendar.DAY_OF_MONTH)
        return when {
            m == Calendar.DECEMBER && d >= 10 -> y
            m == Calendar.JANUARY && d <= 15 -> y - 1
            else -> null
        }
    }

    /** First and last day (days since 1970) of [year]. */
    fun days(year: Int): IntRange {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(year, Calendar.JANUARY, 1) }
        val first = (c.timeInMillis / 86_400_000L).toInt()
        c.set(year + 1, Calendar.JANUARY, 1)
        return first until (c.timeInMillis / 86_400_000L).toInt()
    }

    /** The recap's big numbers, in order: (label, value). Empty ones are left out. */
    fun lines(w: Wrapped): List<Pair<String, String>> = buildList {
        add("Cubes cleared" to "%,d".format(java.util.Locale.ENGLISH, w.wins))
        add("Arrows freed" to "%,d".format(java.util.Locale.ENGLISH, w.arrows))
        if (w.dailies > 0) add("Daily cubes" to "${w.dailies}")
        if (w.bestStreak > 1) add("Best streak" to "${w.bestStreak} days")
        add("Player level" to "LV ${w.level}")
        if (w.parWins > 0) add("Wins on par" to "${w.parWins}")
        w.favouriteMode?.let { add("Favourite mode" to it) }
        w.fastest?.let { (_, ms) -> add("Fastest win" to "%d:%02d.%d".format(ms / 60000, ms / 1000 % 60, ms / 100 % 10)) }
        w.topScore?.let { (_, sc) -> add("Top score" to "%,d".format(java.util.Locale.ENGLISH, sc)) }
        if (w.endlessBest > 0) add("Endless record" to "${w.endlessBest} cubes")
        if (w.adventDoors > 0) add("Advent doors" to "${w.adventDoors} of ${Advent.DOORS}")
        if (w.badges > 0) add("Badges" to "${w.badges}")
        if (w.secondsSolving >= 60) add("Time solving" to if (w.secondsSolving >= 3600) "%dh %02dm".format(w.secondsSolving / 3600, w.secondsSolving % 3600 / 60) else "${w.secondsSolving / 60} min")
    }

    /** One line to go with the picture. */
    fun shareText(w: Wrapped, link: String) = "My ${w.year} in Cubit 🟪 ${"%,d".format(java.util.Locale.ENGLISH, w.wins)} cubes cleared, " +
        "${"%,d".format(java.util.Locale.ENGLISH, w.arrows)} arrows freed" + (if (w.bestStreak > 1) ", best streak ${w.bestStreak} days 🔥" else "") +
        ". What was yours?\n#CubitWrapped\n$link"
}
