package com.cubit.game

/**
 * The only things friends can say to each other: fixed lines, sent by index (the server checks the index, never text),
 * so nothing anyone sends needs moderating. Append only: an index already sent must keep its meaning.
 */
val QUICK_MESSAGES = listOf(
    "GG!", "Nice one!", "Beat that 😏", "Rematch?", "Your turn!", "Nice streak 🔥",
    "So close!", "How?!", "Daily done ✅", "See you tomorrow", "Thanks!", "👋 Hi!",
)

/** A friend's note: a quick message ([msg]) or, when [challenge] is set, a challenge in the link format. */
data class FriendNote(val id: String, val from: String, val name: String, val msg: Int, val challenge: String?, val at: Long)

/** Something a friend did this week: a verified run, or a new player level. */
data class FeedItem(val code: String, val name: String, val kind: String, val label: String, val score: Int, val ms: Long, val level: Int, val at: Long)

/** Everything GET /v1/me/friends returns. */
class FriendSync(val friends: Set<String>, val requests: List<FriendRequest>, val inbox: List<FriendNote>, val feed: List<FeedItem>)

data class LeagueRow(val name: String, val level: Int, val xp: Int, val code: String, val me: Boolean, val dev: Boolean = false)

/** This week's league: [tier] in [tiers], the caller's group ranked by XP this week, and how last week ended. */
class LeagueState(val week: Int, val tier: Int, val tiers: List<String>, val ends: Long, val move: Int, val players: List<LeagueRow>,
                  val lastWeek: Int = 0, val lastRank: Int = 0, val lastOf: Int = 0, val lastMove: String = "", val lastTier: Int = 0)

/** "just now", "5 min ago", "3 h ago", "2 days ago". */
fun timeAgo(at: Long, now: Long): String {
    val s = (now - at).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        s < 2 * 86_400 -> "yesterday"
        else -> "${s / 86_400} days ago"
    }
}

/** One line of the friend feed, without the name: "solved Daily 2026-11-26 in 1:42 · 1,840 pts", "reached LV 7". */
fun feedText(f: FeedItem): String = when (f.kind) {
    "level" -> "reached LV ${f.level}"
    else -> "cleared ${f.label.ifEmpty { "a cube" }}" + (if (f.ms > 0) " in %d:%02d".format(f.ms / 60000, f.ms / 1000 % 60) else "") +
        if (f.score > 0) " · %,d pts".format(java.util.Locale.ENGLISH, f.score) else ""
}

/** Time left until [ends] (unix seconds): "3d 4h", "5h 12m", "12m". */
fun timeLeft(ends: Long, now: Long): String {
    val s = (ends - now).coerceAtLeast(0)
    val d = s / 86_400; val h = s % 86_400 / 3600; val m = s % 3600 / 60
    return when { d > 0 -> "${d}d ${h}h"; h > 0 -> "${h}h ${m}m"; else -> "${m}m" }
}

/** Ranked players needed for Road to 1,000; the server sends its own goal, this is the fallback. */
const val PLAYER_GOAL = 1000

data class ClubRow(val code: String, val name: String, val members: Int, val xp: Int)
data class ClubMember(val name: String, val level: Int, val xp: Int, val code: String, val me: Boolean, val owner: Boolean, val dev: Boolean = false)
class MyClub(val code: String, val name: String, val owner: Boolean, val xp: Int, val rank: Int, val members: List<ClubMember>)
/** GET /v1/me/club: your club, if any, and the week's top clubs ([of] clubs in all). */
class ClubState(val ends: Long, val max: Int, val of: Int, val top: List<ClubRow>, val club: MyClub?)

/** Club join codes: 6 characters with no 0/O or 1/I; typed in any case. */
fun cleanClubCode(s: String) = s.uppercase().filter { it in "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" }.takeIf { it.length == 6 }

/** A cube someone published from the cube maker (GET /v1/cubes). */
data class CommunityCube(val id: String, val title: String, val author: String, val spec: LevelSpec, val arrows: Int, val par: Int,
                         val likes: Int, val wins: Int, val liked: Boolean, val won: Boolean, val mine: Boolean,
                         /** "" live, "testing" (waiting for its maker's playtest), "review" (a moderator's), "hidden" (reported). */
                         val state: String = "", val featured: Boolean = false, val authorMs: Long = 0,
                         /** Average difficulty vote, 1 easy … 3 hard, from [votes] players who cleared it; yours is [myRating]. */
                         val rating: Double = 0.0, val votes: Int = 0, val myRating: Int = 0, val dev: Boolean = false)

/** How a cube's difficulty votes read: "" with none. */
fun feel(rating: Double) = when { rating <= 0.0 -> ""; rating < 1.67 -> "easy"; rating < 2.34 -> "just right"; else -> "hard" }

/** The developer's friend code (Tay, who made Cubit): shown in Social and Settings so players can add them. */
const val DEV_CODE = "18B72243"
const val DEV_NAME = "Tay"

/** One page of community cubes, and how many there are in all. */
class CubePage(val cubes: List<CommunityCube>, val total: Int, val page: Int, val size: Int)

/** The cube maker's sliders: sizes and arrow lengths a published cube may have (the server checks the same). */
val MAKER_SIZES = 5..18
val MAKER_LENGTHS = 3..16
