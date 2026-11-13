package com.cubit.game

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/** lives = 0 means unlimited; secPerArrow = 0 means no timer. */
enum class Difficulty(val label: String, val lives: Int, val secPerArrow: Float, val xpMult: Float, val blurb: String) {
    ZEN("Zen", 0, 0f, 0.5f, "No lives · no timer"),
    EASY("Easy", 5, 0f, 1f, "5 lives · no timer"),
    NORMAL("Normal", 3, 4f, 1.3f, "3 lives · relaxed timer"),
    HARD("Hard", 3, 2f, 1.7f, "3 lives · tight timer"),
    EXPERT("Expert", 1, 1.2f, 2.4f, "1 life · brutal timer"),
    /** Only the cube and a close button on screen. Ranked runs are tagged Clean on the board. */
    CLEAN("Clean", 0, 0f, 0.5f, "Just the cube · no lives · no timer"),
    /** The hardest mode: arrows lap the whole cube before leaving (a lap cube), one life, no hints or powerups.
     *  Last in the list so saved difficulty ordinals stay put. Mirrors "Nightmare" in server/verify.go. */
    NIGHTMARE("Nightmare", 1, 2.5f, 3.5f, "Arrows lap the whole cube before leaving · 1 life · no help");

    fun timeLimitSec(arrows: Int) = if (secPerArrow == 0f) 0 else (10 + arrows * secPerArrow).toInt()
}

/** One charge each per level, each can be switched off in Settings. [code] is what a ranked run records. */
enum class Powerup(val label: String, val glyph: Glyph, val code: Int, val blurb: String) {
    SCAN("Scan", Glyph.SCAN, -4, "Lights up every arrow that can leave right now, for three seconds."),
    SHIELD("Shield", Glyph.SHIELD, -1, "Your next bump doesn't cost a life."),
    FREEZE("Freeze", Glyph.FREEZE, -2, "Adds 15 seconds to the clock."),
    LIFE("Spare life", Glyph.LIFE, -3, "One extra life for this level."),
    UNDO("Undo", Glyph.UNDO, -5, "Takes back your last bump: the mistake comes off your record and its life comes back.");

    fun appliesTo(d: Difficulty) = d != Difficulty.NIGHTMARE && when (this) { SCAN, UNDO -> d != Difficulty.CLEAN; SHIELD, LIFE -> d.lives > 0; FREEZE -> d.secPerArrow > 0 }
}

/** What a finished, won run looked like, for achievement checks. */
class RunFacts(val diff: Difficulty, val perfect: Boolean, val cleared: Int, val ms: Long, val n: Int, val daily: Boolean, val usedPowerups: Boolean,
               val atPar: Boolean = false, val weekly: Boolean = false)

/** Colourways past the first six are earned: each one opens with a badge. */
val THEME_UNLOCKS = mapOf(6 to Achievement.TEN, 7 to Achievement.PERFECT, 8 to Achievement.ARROWS_100, 9 to Achievement.DAILY, 10 to Achievement.PURIST, 11 to Achievement.PERFECT_EXPERT)

/** A named set of hand-picked cubes (seeds chosen offline for how deep the pink arrow sits). */
class Pack(val name: String, val blurb: String, val levels: List<LevelSpec>)

private fun pack(name: String, blurb: String, n: Int, maxLen: Int, vararg seeds: Long) = Pack(name, blurb, seeds.map { LevelSpec(n, maxLen, it) })

/** Seeds from a search over a few hundred cubes each, keeping the ten deepest, easiest first. */
val PACKS = listOf(
    pack("Pocket", "Tiny 5×5 cubes with the pink arrow at the very bottom.", 5, 4,
        1000292, 1000527, 1000210, 1000538, 1000509, 1000086, 1000554, 1000349, 1000172, 1000245),
    pack("Needlework", "Short, stubby arrows packed tight on 9×9 faces.", 9, 3,
        2000115, 2000171, 2000300, 2000128, 2000118, 2000154, 2000157, 2000095, 2000027, 2000302),
    pack("Long lines", "Few arrows, all of them long and winding round the cube.", 9, 20,
        3000299, 3000399, 3000103, 3000302, 3000196, 3000377, 3000189, 3000387, 3000289, 3000025),
    pack("Monster", "18×18 faces. The last one has 63 arrows in the pink one's way.", 18, 10,
        4000021, 4000107, 4000058, 4000070, 4000085, 4000049, 4000048, 4000002, 4000077, 4000008),
)

/** Badges, checked after every win. [check] sees the prefs after this run's stats were added. */
enum class Achievement(val title: String, val blurb: String, val check: (Prefs, RunFacts) -> Boolean) {
    FIRST("First train out", "Win your first level.", { p, _ -> p.solved >= 1 }),
    TEN("Regular", "Win 10 levels.", { p, _ -> p.solved >= 10 }),
    HUNDRED("Season ticket", "Win 100 levels.", { p, _ -> p.solved >= 100 }),
    PERFECT("Spotless", "Win without a single bump.", { _, r -> r.perfect }),
    PERFECT_EXPERT("Signal master", "A perfect run on Expert.", { _, r -> r.perfect && r.diff == Difficulty.EXPERT }),
    NIGHTMARE("Night shift", "Win a level on Nightmare.", { _, r -> r.diff == Difficulty.NIGHTMARE }),
    ARROWS_100("Rush hour", "Clear 100 arrows in total.", { p, _ -> p.totalArrows >= 100 }),
    ARROWS_1000("Terminus", "Clear 1,000 arrows in total.", { p, _ -> p.totalArrows >= 1000 }),
    SPEED("Express", "Win with 20 or more arrows cleared in under 30 seconds.", { _, r -> r.cleared >= 20 && r.ms < 30_000 }),
    PURIST("Purist", "Win 10 levels in a row without powerups.", { p, _ -> p.noPowerupStreak >= 10 }),
    CLEAN_10("Quiet carriage", "Win 10 runs in Clean mode.", { p, _ -> p.cleanSolved >= 10 }),
    DAILY("Commuter", "Solve a daily cube.", { _, r -> r.daily }),
    STREAK_7("On fire", "Solve the daily cube 7 days in a row.", { p, _ -> p.dailyStreak >= 7 }),
    BIG("Grand central", "Win on a 14×14 cube or bigger.", { _, r -> r.n >= 14 }),
    LEVEL_25("Long haul", "Reach player level 25.", { p, _ -> playerLevel(p.xp).first >= 25 }),
    PAR("On par", "Win in the fewest taps possible.", { _, r -> r.atPar }),
    WEEKLY("Weekender", "Finish a weekly challenge.", { _, r -> r.weekly }),
    PACK("Collector", "Finish every level in a puzzle pack.", { p, _ -> PACKS.any { p.packSolved(it) >= it.levels.size } }),
}

class Theme(
    val name: String, val bg: Int, val face: Int, val ink: Int, val accent: Int, val text: Int, val panel: Int,
    /** Per-face transit-line colours used when "lines per side" is on, indexed by face (+x,-x,+y,-y,+z,-z). */
    val tints: IntArray = LINE_COLORS,
) {
    val onAccent get() = if (Color.luminance(accent) > 0.45f) Color.rgb(14, 27, 61) else Color.WHITE
    val muted get() = mix(text, bg, 0.38f)
    val hairline get() = mix(text, bg, 0.82f)
    val tile get() = mix(bg, Color.BLACK, 0.35f)
    val isLight get() = Color.luminance(bg) > 0.5f
}

/** Transit-line colours: blue, green, red, yellow, violet, orange. */
val LINE_COLORS = intArrayOf(
    Color.rgb(31, 79, 191), Color.rgb(11, 122, 87), Color.rgb(200, 49, 43),
    Color.rgb(214, 160, 0), Color.rgb(107, 63, 160), Color.rgb(226, 98, 27),
)

/** Colourways of the signage system; the first is the house look. */
val THEMES = listOf(
    Theme("Signal", Color.rgb(14, 27, 61), Color.rgb(31, 79, 191), Color.rgb(244, 246, 251), Color.rgb(255, 199, 44), Color.rgb(244, 246, 251), Color.rgb(23, 42, 87)),
    Theme("Platform", Color.rgb(9, 37, 30), Color.rgb(11, 122, 87), Color.rgb(244, 246, 251), Color.rgb(255, 199, 44), Color.rgb(240, 247, 243), Color.rgb(15, 54, 44)),
    Theme("Metro", Color.rgb(38, 13, 15), Color.rgb(200, 49, 43), Color.rgb(250, 244, 240), Color.rgb(255, 199, 44), Color.rgb(250, 240, 236), Color.rgb(58, 22, 25)),
    Theme("Night line", Color.rgb(11, 11, 13), Color.rgb(31, 32, 38), Color.rgb(255, 176, 32), Color.rgb(255, 176, 32), Color.rgb(236, 236, 240), Color.rgb(24, 24, 28)),
    Theme("Daylight", Color.rgb(233, 237, 245), Color.WHITE, Color.rgb(14, 27, 61), Color.rgb(31, 79, 191), Color.rgb(14, 27, 61), Color.WHITE),
    // Bright and clean: white cube, ink-black arrows on a pale sky ground.
    Theme("Arcade", Color.rgb(220, 232, 246), Color.WHITE, Color.rgb(21, 23, 28), Color.rgb(47, 140, 255), Color.rgb(28, 36, 64), Color.WHITE),
    Theme("Candy", Color.rgb(255, 228, 240), Color.WHITE, Color.rgb(58, 29, 92), Color.rgb(122, 60, 255), Color.rgb(58, 29, 92), Color.WHITE),
    Theme("Lavender", Color.rgb(234, 230, 251), Color.rgb(250, 249, 255), Color.rgb(43, 35, 80), Color.rgb(108, 76, 245), Color.rgb(43, 35, 80), Color.WHITE),
    Theme("Ocean", Color.rgb(6, 42, 58), Color.rgb(14, 90, 115), Color.rgb(232, 251, 255), Color.rgb(62, 230, 193), Color.rgb(226, 246, 250), Color.rgb(10, 56, 74)),
    Theme("Sunset", Color.rgb(43, 16, 53), Color.rgb(242, 120, 75), Color.rgb(255, 247, 240), Color.rgb(255, 209, 102), Color.rgb(255, 238, 230), Color.rgb(62, 26, 72)),
    Theme("Forest", Color.rgb(19, 38, 27), Color.rgb(233, 226, 207), Color.rgb(30, 58, 42), Color.rgb(242, 165, 65), Color.rgb(236, 232, 218), Color.rgb(27, 52, 38)),
    Theme("Mono", Color.rgb(17, 17, 17), Color.rgb(237, 237, 237), Color.rgb(17, 17, 17), Color.WHITE, Color.rgb(240, 240, 240), Color.rgb(32, 32, 32)),
    // Accessibility, free for everyone: black and white at full contrast, and Okabe–Ito line colours that stay
    // distinct with every common colour-vision deficiency.
    Theme("Contrast", Color.BLACK, Color.WHITE, Color.BLACK, Color.rgb(255, 212, 0), Color.WHITE, Color.rgb(28, 28, 28), intArrayOf(
        Color.rgb(0, 114, 178), Color.rgb(0, 158, 115), Color.rgb(213, 94, 0), Color.rgb(240, 228, 66), Color.rgb(86, 180, 233), Color.rgb(230, 159, 0))),
)

/** Arrow colour override; 0 means "use the colourway's arrows". */
val ARROW_COLORS = intArrayOf(0, Color.WHITE, Color.rgb(255, 199, 44), Color.rgb(92, 214, 255), Color.rgb(14, 27, 61), Color.rgb(140, 240, 160))

/** One finished run. The detail fields are 0/empty for runs saved before they existed. */
data class ScoreEntry(
    val name: String, val score: Int, val label: String, val difficulty: String, val time: Long,
    val arrows: Int = 0, val ms: Long = 0, val mistakes: Int = 0, val size: Int = 0, val powerups: List<String> = emptyList(), val playerLevel: Int = 0,
    /** Server run id (global board) for fetching its replay. */
    val run: String = "",
    /** Local runs keep their cube and taps so they can be watched back. */
    val spec: LevelSpec? = null, val moves: List<LongArray> = emptyList(),
)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cubit", Context.MODE_PRIVATE)

    var level: Int get() = sp.getInt("level", 1); set(v) = sp.edit().putInt("level", v).apply()
    var xp: Int get() = sp.getInt("xp", 0); set(v) = sp.edit().putInt("xp", v).apply()
    var hints: Int get() = sp.getInt("hints", 5); set(v) = sp.edit().putInt("hints", v).apply()
    var name: String get() = sp.getString("name", "Player")!!; set(v) = sp.edit().putString("name", v).apply()
    var difficulty: Difficulty
        get() = Difficulty.entries.getOrElse(sp.getInt("diff", Difficulty.NORMAL.ordinal)) { Difficulty.NORMAL }
        set(v) = sp.edit().putInt("diff", v.ordinal).apply { if (v != Difficulty.CLEAN) putInt("lastMode", v.ordinal) }.apply()
    /** The mode Clean switches back to. */
    val lastMode: Difficulty get() = Difficulty.entries.getOrElse(sp.getInt("lastMode", Difficulty.NORMAL.ordinal)) { Difficulty.NORMAL }
    var themeIndex: Int get() = sp.getInt("theme", 0).coerceIn(THEMES.indices); set(v) = sp.edit().putInt("theme", v).apply()
    var arrowColor: Int get() = sp.getInt("arrowColor", 0).coerceIn(ARROW_COLORS.indices); set(v) = sp.edit().putInt("arrowColor", v).apply()
    var tintSides: Boolean get() = sp.getBoolean("tint", false); set(v) = sp.edit().putBoolean("tint", v).apply()
    var sideMarkers: Boolean get() = sp.getBoolean("markers", false); set(v) = sp.edit().putBoolean("markers", v).apply()
    var sound: Boolean get() = sp.getBoolean("sound", true); set(v) = sp.edit().putBoolean("sound", v).apply()
    /** Full clear: every arrow has to go and the pink one leaves last. Off: free the pink arrow. */
    var fullClear: Boolean get() = sp.getBoolean("fullClear", false); set(v) = sp.edit().putBoolean("fullClear", v).apply()
    var tutorialDone: Boolean get() = sp.getBoolean("tutorialDone", false); set(v) = sp.edit().putBoolean("tutorialDone", v).apply()
    /** Accessibility: mirrors the in-game controls for left thumbs. */
    var leftHanded: Boolean get() = sp.getBoolean("leftHanded", false); set(v) = sp.edit().putBoolean("leftHanded", v).apply()
    /** Accessibility: a stripe down the pink arrow so it doesn't depend on colour. */
    var markTarget: Boolean get() = sp.getBoolean("markTarget", false); set(v) = sp.edit().putBoolean("markTarget", v).apply()
    /** A level left mid-way (JSON written by MainActivity.saveRun), or null. */
    var savedRun: String? get() = sp.getString("savedRun", null); set(v) = sp.edit().putString("savedRun", v).apply()
    var weeklyDone: Int get() = sp.getInt("weeklyDone", -1); set(v) = sp.edit().putInt("weeklyDone", v).apply()
    var parWins: Int get() = sp.getInt("parWins", 0); set(v) = sp.edit().putInt("parWins", v).apply()
    /** Update check: on by default, once a day, against GitHub releases. */
    var autoUpdate: Boolean get() = sp.getBoolean("autoUpdate", true); set(v) = sp.edit().putBoolean("autoUpdate", v).apply()
    var updateCheckedDay: Int get() = sp.getInt("updDay", -1); set(v) = sp.edit().putInt("updDay", v).apply()
    var latestVersion: String? get() = sp.getString("updLatest", null); set(v) = sp.edit().putString("updLatest", v).apply()
    var dismissedVersion: String? get() = sp.getString("updDismissed", null); set(v) = sp.edit().putString("updDismissed", v).apply()
    /** Evening reminder while a daily streak is alive and today's cube isn't done. Opt-in. */
    var streakReminder: Boolean get() = sp.getBoolean("remind", false); set(v) = sp.edit().putBoolean("remind", v).apply()
    /** Day whose "yesterday's cube" card was already shown or dismissed. */
    /** Most cubes cleared in one endless run. */
    var endlessBest: Int get() = sp.getInt("endlessBest", 0); set(v) = sp.edit().putInt("endlessBest", v).apply()
    /** Past daily cubes solved from the archive (they don't count toward the streak). */
    var archiveDays: Set<Int>
        get() = (sp.getStringSet("archiveDays", null) ?: emptySet()).mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = sp.edit().putStringSet("archiveDays", v.sortedDescending().take(400).map { "$it" }.toHashSet()).apply()
    /** The first bomb arrow explains itself once. */
    var bombSeen: Boolean get() = sp.getBoolean("bombSeen", false); set(v) = sp.edit().putBoolean("bombSeen", v).apply()
    var summarySeen: Int get() = sp.getInt("summarySeen", -1); set(v) = sp.edit().putInt("summarySeen", v).apply()
    fun packSolved(p: Pack) = sp.getInt("pack_" + p.name, 0)
    fun setPackSolved(p: Pack, n: Int) = sp.edit().putInt("pack_" + p.name, n).apply()
    var music: Boolean get() = sp.getBoolean("music", true); set(v) = sp.edit().putBoolean("music", v).apply()
    /** UTC day of the last solved daily cube. */
    var dailyDone: Int get() = sp.getInt("dailyDone", -1); set(v) = sp.edit().putInt("dailyDone", v).apply()
    var dailyStreak: Int get() = sp.getInt("dailyStreak", 0); set(v) = sp.edit().putInt("dailyStreak", v).apply()
    var totalArrows: Int get() = sp.getInt("totalArrows", 0); set(v) = sp.edit().putInt("totalArrows", v).apply()
    var noPowerupStreak: Int get() = sp.getInt("noPuStreak", 0); set(v) = sp.edit().putInt("noPuStreak", v).apply()
    var cleanSolved: Int get() = sp.getInt("cleanSolved", 0); set(v) = sp.edit().putInt("cleanSolved", v).apply()
    var achievements: Set<String> get() = sp.getStringSet("ach", emptySet())!!; set(v) = sp.edit().putStringSet("ach", HashSet(v)).apply()
    /** Earned by finishing a weekly challenge; each one covers a missed day of the daily streak. Up to [MAX_FREEZES]. */
    var streakFreezes: Int get() = sp.getInt("freezes", 0); set(v) = sp.edit().putInt("freezes", v.coerceIn(0, MAX_FREEZES)).apply()
    /** The streak as it stands today: it lapses once more whole days pass without a daily solve than freezes cover. */
    fun liveStreak(today: Int) = if (today - 1 - dailyDone <= streakFreezes) dailyStreak else 0
    /** Best daily-cube score and the day it was set. */
    var dailyBest: Int get() = sp.getInt("dailyBest", 0); set(v) = sp.edit().putInt("dailyBest", v).apply()
    var dailyBestDay: Int get() = sp.getInt("dailyBestDay", -1); set(v) = sp.edit().putInt("dailyBestDay", v).apply()
    /** Every UTC day whose daily cube was solved here, for the calendar in Stats. */
    var dailyDays: Set<Int>
        get() = (sp.getStringSet("dailyDays", null) ?: emptySet()).mapNotNull { it.toIntOrNull() }.toSet() + listOfNotNull(dailyDone.takeIf { it >= 0 })
        set(v) = sp.edit().putStringSet("dailyDays", v.sortedDescending().take(400).map { "$it" }.toHashSet()).apply()

    /** Per-mode record: wins, losses, mistakes in wins, total win seconds, fastest win seconds (0 = none yet). */
    fun stats(d: Difficulty): IntArray = runCatching { JSONObject(sp.getString("stats", "{}")).optJSONArray(d.name) }.getOrNull()
        ?.let { a -> IntArray(5) { a.optInt(it) } } ?: IntArray(5)
    fun recordRun(d: Difficulty, won: Boolean, mistakes: Int, secs: Int) {
        val all = runCatching { JSONObject(sp.getString("stats", "{}")) }.getOrElse { JSONObject() }
        val s = stats(d)
        if (won) { s[0]++; s[2] += mistakes; s[3] += secs; s[4] = if (s[4] == 0) secs.coerceAtLeast(1) else minOf(s[4], secs.coerceAtLeast(1)) } else s[1]++
        sp.edit().putString("stats", all.put(d.name, JSONArray(s.toList())).toString()).apply()
    }

    /** Friend codes added in Rankings → Friends, and this install's own code once the board has issued it. */
    var friends: Set<String> get() = sp.getStringSet("friends", emptySet())!!; set(v) = sp.edit().putStringSet("friends", HashSet(v)).apply()
    var friendCode: String? get() = sp.getString("friendCode", null); set(v) = sp.edit().putString("friendCode", v).apply()

    /** Cubes saved from the win sheet into "My pack", which can be shared as a link. */
    var myPack: List<LevelSpec>
        get() = (sp.getString("myPack", "") ?: "").split(";").mapNotNull { s -> s.split(".").takeIf { it.size == 3 }?.let { (n, l, seed) -> LevelSpec(n.toInt(), l.toInt(), seed.toLong()) } }
        set(v) = sp.edit().putString("myPack", v.takeLast(30).joinToString(";") { "${it.n}.${it.maxLen}.${it.seed}" }).apply()
    /** Packs opened from someone's link, as their payloads. */
    var sharedPacks: List<String>
        get() = runCatching { JSONArray(sp.getString("sharedPacks", "[]")) }.getOrElse { JSONArray() }.let { a -> (0 until a.length()).map { a.getString(it) } }
        set(v) = sp.edit().putString("sharedPacks", JSONArray(v.takeLast(20)).toString()).apply()
    /** Built-in packs, then My pack (if it has cubes), then shared ones. */
    fun allPacks(): List<Pack> = PACKS + listOfNotNull(myPack.takeIf { it.isNotEmpty() }?.let { Pack(MY_PACK, "Cubes you saved from the win sheet.", it) }) +
        sharedPacks.mapNotNull { Links.parsePack(it) }
    var haptics: Boolean get() = sp.getBoolean("haptics", true); set(v) = sp.edit().putBoolean("haptics", v).apply()
    var thickness: Int get() = sp.getInt("thick", 5); set(v) = sp.edit().putInt("thick", v).apply()
    var freeSize: Int get() = sp.getInt("freeSize", 5); set(v) = sp.edit().putInt("freeSize", v).apply()
    var freeLen: Int get() = sp.getInt("freeLen", 6); set(v) = sp.edit().putInt("freeLen", v).apply()
    var solved: Int get() = sp.getInt("solved", 0); set(v) = sp.edit().putInt("solved", v).apply()
    /** Global rankings: off means nothing is posted or fetched. Asked once on first launch. */
    var postOnline: Boolean get() = sp.getBoolean("online", false); set(v) = sp.edit().putBoolean("online", v).apply()
    /** First-launch setup finished. Key bumped when the setup grew, so existing installs see the new one once. */
    var onboarded: Boolean get() = sp.getBoolean("setup2", false); set(v) = sp.edit().putBoolean("setup2", v).apply()
    /** Bearer token the leaderboard issued to this install ("<device>.<secret>"); null until first ranked run. */
    var lbToken: String? get() = sp.getString("lbToken", null); set(v) = sp.edit().putString("lbToken", v).apply()

    val theme get() = THEMES[themeIndex]

    fun powerupOn(p: Powerup) = sp.getBoolean("pu_" + p.name, true)
    fun setPowerup(p: Powerup, on: Boolean) = sp.edit().putBoolean("pu_" + p.name, on).apply()

    fun scores(): List<ScoreEntry> {
        val arr = runCatching { JSONArray(sp.getString("scores", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val pu = o.optJSONArray("p") ?: JSONArray()
            ScoreEntry(o.optString("n"), o.optInt("s"), o.optString("l"), o.optString("d"), o.optLong("t"),
                o.optInt("a"), o.optLong("ms"), o.optInt("m"), o.optInt("z"), (0 until pu.length()).map { pu.getString(it) }, o.optInt("lv"),
                spec = if (o.has("sn")) LevelSpec(o.getInt("sn"), o.getInt("sl"), o.getLong("ss"), o.optBoolean("sp"), o.optBoolean("sb")) else null,
                moves = o.optJSONArray("mv")?.let { mv -> (0 until mv.length()).map { val m = mv.getJSONArray(it); longArrayOf(m.getLong(0), m.getLong(1)) } } ?: emptyList())
        }
    }

    /** Keeps the top 50; returns the 1-based rank of the new entry, or 0 if it didn't make the board. */
    fun addScore(e: ScoreEntry): Int {
        val all = (scores() + e).sortedByDescending { it.score }.take(50)
        val arr = JSONArray()
        all.forEach {
            arr.put(JSONObject().put("n", it.name).put("s", it.score).put("l", it.label).put("d", it.difficulty).put("t", it.time)
                .put("a", it.arrows).put("ms", it.ms).put("m", it.mistakes).put("z", it.size).put("p", JSONArray(it.powerups)).put("lv", it.playerLevel)
                .apply { it.spec?.let { s -> put("sn", s.n).put("sl", s.maxLen).put("ss", s.seed).put("sp", s.lap).put("sb", s.bombs) } }
                .put("mv", JSONArray().apply { it.moves.forEach { m -> put(JSONArray().put(m[0]).put(m[1])) } }))
        }
        sp.edit().putString("scores", arr.toString()).apply()
        return all.indexOf(e) + 1
    }

    fun resetProgress() = sp.edit().remove("level").remove("xp").remove("hints").remove("scores").remove("solved")
        .remove("dailyDone").remove("dailyStreak").remove("totalArrows").remove("noPuStreak").remove("cleanSolved").remove("ach")
        .remove("weeklyDone").remove("parWins").remove("freezes").remove("dailyBest").remove("dailyBestDay").remove("dailyDays").remove("stats")
        .remove("endlessBest").remove("archiveDays")
        .apply { allPacks().forEach { remove("pack_" + it.name) } }.apply()
}

const val MAX_FREEZES = 2
const val MY_PACK = "My pack"

/** XP needed to go from player level k to k+1. */
fun xpForLevel(k: Int) = 100 + 60 * (k - 1)

/** Returns (playerLevel, xpIntoLevel, xpNeededForNext). */
fun playerLevel(totalXp: Int): Triple<Int, Int, Int> {
    var k = 1; var rest = totalXp
    while (rest >= xpForLevel(k)) { rest -= xpForLevel(k); k++ }
    return Triple(k, rest, xpForLevel(k))
}
