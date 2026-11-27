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

data class LeagueRow(val name: String, val level: Int, val xp: Int, val code: String, val me: Boolean)

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
