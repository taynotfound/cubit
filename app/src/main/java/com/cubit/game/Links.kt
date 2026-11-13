package com.cubit.game

import java.net.URLEncoder

/** A run someone shared: their cube and rules, and their taps to race as a ghost. */
class Challenge(val spec: LevelSpec, val full: Boolean, val mode: Difficulty, val name: String, val moves: List<LongArray>)

/**
 * Shareable links, with no server needed: cubit://challenge/<payload>, cubit://pack/<payload> and cubit://friend/<code>,
 * or the same through open.html (?c=, ?pack=, ?f=) for chat apps that don't linkify custom schemes.
 * Payloads are fields joined by "_", lists by "~", numbers in base 36, and the free-text name always last, so it may
 * hold anything. [parseChallenge] and [parsePack] take the payload already URL-decoded (as Uri.lastPathSegment gives it).
 */
object Links {
    const val WEB = "https://taynotfound.github.io/cubit/open.html"
    /** Public profile pages (docs/u.html), by friend code. */
    const val PROFILE = "https://taynotfound.github.io/cubit/u.html"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun challengePayload(c: Challenge): String {
        var prev = 0L
        val mv = c.moves.joinToString("~") { m -> val d = m[1] - prev; prev = m[1]; "${m[0].toString(36)}.${d.toString(36)}" }
        return listOf(if (c.spec.special) "2" else "1", c.spec.n, c.spec.maxLen, c.spec.seed.toString(36), if (c.full) 1 else 0, c.mode.ordinal, mv, enc(c.name)).joinToString("_")
    }

    fun parseChallenge(p: String): Challenge? = runCatching {
        val f = p.split("_", limit = 8)
        if (f.size != 8 || f[0] !in setOf("1", "2")) return null
        val spec = LevelSpec(f[1].toInt(), f[2].toInt(), f[3].toLong(36), special = f[0] == "2") // "1": from before bombs
        val mode = Difficulty.entries[f[5].toInt()]
        if (spec.n !in 2..18 || spec.maxLen !in 2..24) return null
        var t = 0L
        val moves = f[6].split("~").take(2000).map { m -> val (a, d) = m.split("."); t += d.toLong(36).coerceAtLeast(0); longArrayOf(a.toLong(36), t) }
        Challenge(spec.copy(lap = mode == Difficulty.NIGHTMARE), f[4] == "1", mode, f[7].take(16).ifBlank { "A friend" }, moves)
    }.getOrNull()

    fun packPayload(name: String, levels: List<LevelSpec>) =
        "1_" + levels.joinToString("~") { "${it.n}.${it.maxLen}.${it.seed.toString(36)}" } + "_" + enc(name)

    fun parsePack(p: String): Pack? = runCatching {
        val f = p.split("_", limit = 3)
        if (f.size != 3 || f[0] != "1") return null
        val levels = f[1].split("~").take(30).map { s -> val (n, l, seed) = s.split("."); LevelSpec(n.toInt(), l.toInt(), seed.toLong(36)) }
        if (levels.isEmpty() || levels.any { it.n !in 2..18 || it.maxLen !in 2..24 }) return null
        Pack(f[2].take(24).ifBlank { "Shared pack" }, "Shared with you · ${levels.size} cubes", levels)
    }.getOrNull()

    /** Friend codes are 8 hex digits; shown upper case, accepted with or without a dash. */
    fun cleanCode(s: String) = s.uppercase().filter { it in "0123456789ABCDEF" }.takeIf { it.length == 8 }
}

/**
 * One version's notes from CHANGELOG.md (bundled as an asset): its "- " lines, markdown links reduced to their text.
 * Empty if the version isn't there. **Bold** is kept for the sheet to style.
 */
fun whatsNew(changelog: String, version: String): List<String> {
    val lines = changelog.lines()
    val start = lines.indexOfFirst { it.startsWith("## $version ") || it == "## $version" }
    if (start < 0) return emptyList()
    return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }.filter { it.startsWith("- ") }
        .map { it.removePrefix("- ").replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1") }
}
