package com.cubit.game

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** [public]: the player has a public profile, which [code] opens. On the board the code comes only with one. */
data class GlobalPlayer(val name: String, val xp: Int, val level: Int, val cleanRuns: Int, val code: String = "", val public: Boolean = false)
/** A player's public profile page (GET /v1/profile/{code}). Badges and the daily numbers come from their phone. */
class PublicProfile(val code: String, val name: String, val level: Int, val xp: Int, val runs: Int, val cleanRuns: Int, val endlessBest: Int,
                    val since: Long, val badges: List<String>, val streak: Int, val solved: Int, val dailySolved: Int, val best: List<ScoreEntry>,
                    /** World Cubes they helped clear, and ones they freed themselves. */
                    val worldCubes: Int = 0, val worldWins: Int = 0)
/** [total] is every ranked player, where [players] lists only the top of the board. */
data class GlobalBoard(val players: List<GlobalPlayer>, val scores: List<ScoreEntry>, val total: Int = players.size)
data class RunResult(val status: String, val score: Int, val xp: Int, val rank: Int)
/** The World Cube as the server has it: which cube, its recipe, and the arrows cleared since version [v] was asked for. */
class WorldState(val id: Int, val spec: LevelSpec, val v: Int, val removed: List<Int>, val left: Int, val arrows: Int, val players: Int,
                 val top: List<Pair<String, Int>>, val lastId: Int, val lastWinner: String?)
/** One row of an endless board: a player and the most cubes they cleared in one run. */
data class EndlessEntry(val name: String, val level: Int, val cubes: Int, val code: String = "")
/** A ranked endless run's next cube: the server's run id, the lives carried in and the seconds bought. */
class EndlessCube(val id: String, val lives: Int, val bonusSec: Int)

/**
 * Client for the Cubit leaderboard (server/). Ranked runs are verified server-side: the app opens a run, records
 * every tap, and the server replays them against the same generated cube. Callbacks run on the main thread;
 * null means the request failed (see [lastError]).
 */
object Net {
    private const val BASE = "https://cubit-lb-api.taymaerz.de"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    /** Why the last request failed, shown in Rankings so connection problems are diagnosable on the phone. */
    @Volatile var lastError: String? = null
    /** The last failure was a lookup that never left the phone: no connection, or the app's network access is off
     *  (GrapheneOS "Network" permission, per-app firewalls). */
    @Volatile var noNetwork = false

    private class HttpError(val code: Int, msg: String) : Exception(msg)

    fun fetch(done: (GlobalBoard?) -> Unit) = io.execute {
        val b = call { parseBoard(JSONObject(request("GET", "/v1/leaderboard", null, null))) }
        main.post { done(b) }
    }

    /** One week's challenge board. */
    fun weekly(week: Int, done: (List<ScoreEntry>?) -> Unit) = io.execute {
        val b = call { parseBoard(JSONObject(request("GET", "/v1/weekly/$week", null, null))).scores }
        main.post { done(b) }
    }

    /** One day's daily-cube board (same run format as the best runs). */
    fun daily(day: Int, done: (List<ScoreEntry>?) -> Unit) = io.execute {
        val b = call { parseBoard(JSONObject(request("GET", "/v1/daily/$day", null, null))).scores }
        main.post { done(b) }
    }

    /** Opens a ranked run; registers this install first if it has no token yet. Done gets the run id. */
    fun startRun(p: Prefs, spec: LevelSpec, campaignLevel: Int?, dailyDay: Int?, difficulty: String, fullClear: Boolean, week: Int? = null,
                 endless: EndlessCube? = null, done: (String?) -> Unit) {
        val name = p.name
        io.execute {
            val id = call {
                val body = JSONObject().put("difficulty", difficulty).put("name", name).put("goal", if (fullClear) "all" else "pink")
                if (endless != null) body.put("mode", "endless").put("endless", endless.id).put("lives", endless.lives).put("bonusSec", endless.bonusSec)
                else if (week != null) body.put("mode", "weekly").put("week", week)
                else if (dailyDay != null) body.put("mode", "daily").put("day", dailyDay)
                else if (campaignLevel != null) body.put("mode", "campaign").put("level", campaignLevel)
                else body.put("mode", "free").put("n", spec.n).put("maxLen", spec.maxLen).put("seed", spec.seed)
                if (spec.hasSpecial) body.put("special", true) // the server only plays special arrows where the app does
                JSONObject(authed(p, "POST", "/v1/runs", body.toString())).getString("run")
            }
            main.post { done(id) }
        }
    }

    /** Sends the run's taps as [arrow, ms] pairs; the server replays them and returns the verified result. */
    fun finishRun(p: Prefs, run: String, moves: List<LongArray>, done: (RunResult?) -> Unit) {
        val arr = JSONArray()
        moves.forEach { arr.put(JSONArray().put(it[0]).put(it[1])) }
        val body = JSONObject().put("moves", arr).toString()
        io.execute {
            val r = call {
                val o = JSONObject(authed(p, "POST", "/v1/runs/$run/finish", body))
                RunResult(o.optString("status"), o.optInt("score"), o.optInt("xp"), o.optInt("rank"))
            }
            main.post { done(r) }
        }
    }

    /** Opens a ranked endless run (the day's shared one if [daily]). Done gets its id and seed. */
    fun endlessStart(p: Prefs, daily: Boolean, done: (Pair<String, Long>?) -> Unit) = io.execute {
        val r = call {
            val o = JSONObject(authed(p, "POST", "/v1/endless", JSONObject().put("daily", daily).toString()))
            o.getString("id") to o.getString("seed").toLong()
        }
        main.post { done(r) }
    }

    /** The World Cube; with [id] and [since], only what's been cleared on it since that version. */
    fun world(id: Int?, since: Int, done: (WorldState?) -> Unit) = io.execute {
        val r = call {
            val o = JSONObject(request("GET", "/v1/world" + (id?.let { "?id=$it&since=$since" } ?: ""), null, null))
            val rm = o.getJSONArray("removed"); val top = o.optJSONArray("top") ?: JSONArray(); val last = o.optJSONObject("last")
            WorldState(o.getInt("id"), LevelSpec(o.getInt("n"), o.getInt("maxLen"), o.getString("seed").toLong(), special = true), o.getInt("v"),
                (0 until rm.length()).map { rm.getInt(it) }, o.getInt("left"), o.getInt("arrows"), o.getInt("players"),
                (0 until top.length()).map { top.getJSONObject(it).let { t -> t.optString("name") to t.optInt("arrows") } },
                last?.optInt("id") ?: 0, last?.optString("winner"))
        }
        main.post { done(r) }
    }

    /** One tap on the World Cube. Done gets the server's answer: ok, what it cleared, and won when it freed the pink one. */
    fun worldTap(p: Prefs, id: Int, arrow: Int, done: (JSONObject?) -> Unit) = io.execute {
        val r = call { JSONObject(authed(p, "POST", "/v1/world/tap", JSONObject().put("id", id).put("arrow", arrow).toString())) }
        main.post { done(r) }
    }

    /** Endless board: everyone's best run, or one day's daily endless runs. */
    fun endless(day: Int?, done: (List<EndlessEntry>?) -> Unit) = io.execute {
        val r = call {
            val a = JSONObject(request("GET", "/v1/endless" + (day?.let { "/$it" } ?: ""), null, null)).getJSONArray("players")
            (0 until a.length()).map { a.getJSONObject(it) }.map { EndlessEntry(it.optString("name"), it.optInt("level"), it.optInt("cubes"), it.optString("code")) }
        }
        main.post { done(r) }
    }

    /** Turns this player's public page on (with what it shows) or off. Done gets whether it's on. */
    fun setProfile(p: Prefs, public: Boolean, done: (Boolean?) -> Unit = {}) {
        val body = JSONObject().put("public", public).put("badges", JSONArray(p.achievements.sorted())).put("streak", p.liveStreak(utcDay()))
            .put("solved", p.solved).put("dailySolved", p.dailyDays.size + p.archiveDays.size).toString()
        io.execute {
            val r = call { JSONObject(authed(p, "POST", "/v1/me/profile", body)).getBoolean("public") }
            main.post { done(r) }
        }
    }

    /** A public profile by friend code; null if there's none (or it was switched off). */
    fun profile(code: String, done: (PublicProfile?) -> Unit) = io.execute {
        val r = call {
            val o = JSONObject(request("GET", "/v1/profile/$code", null, null))
            val b = o.optJSONArray("badges") ?: JSONArray()
            PublicProfile(o.getString("code"), o.optString("name"), o.optInt("level"), o.optInt("xp"), o.optInt("runs"), o.optInt("cleanRuns"),
                o.optInt("endlessBest"), o.optLong("since"), (0 until b.length()).map { b.getString(it) }, o.optInt("streak"), o.optInt("solved"),
                o.optInt("dailySolved"), parseBoard(JSONObject().put("scores", o.optJSONArray("best") ?: JSONArray())).scores,
                o.optInt("worldCubes"), o.optInt("worldWins"))
        }
        main.post { done(r) }
    }

    /** This install's friend code (registering first if needed); stored in [Prefs.friendCode]. */
    fun me(p: Prefs, done: (String?) -> Unit) = io.execute {
        val code = call { JSONObject(authed(p, "GET", "/v1/me", null)).getString("code").also { p.friendCode = it } }
        main.post { done(code) }
    }

    /** Players behind these friend codes, each with their best run (null if none yet). Unknown codes are left out. */
    fun friends(codes: Collection<String>, done: (List<Pair<GlobalPlayer, ScoreEntry?>>?) -> Unit) = io.execute {
        val r = call {
            val a = JSONObject(request("GET", "/v1/friends?codes=" + codes.joinToString(","), null, null)).getJSONArray("friends")
            (0 until a.length()).map { a.getJSONObject(it) }.map { f ->
                val best = f.optJSONObject("best")?.let { parseBoard(JSONObject().put("scores", JSONArray().put(it))).scores.first() }
                GlobalPlayer(f.optString("name"), f.optInt("xp"), f.optInt("level"), f.optInt("cleanRuns"), f.optString("code"), f.optBoolean("public")) to best
            }
        }
        main.post { done(r) }
    }

    /** A board run's cube, taps, and whether it was a full clear (pink arrow last), for watching it back. */
    fun replay(run: String, done: (Triple<LevelSpec, List<LongArray>, Boolean>?) -> Unit) = io.execute {
        val r = call {
            val o = JSONObject(request("GET", "/v1/runs/$run/replay", null, null))
            val mv = o.getJSONArray("moves")
            Triple(LevelSpec(o.getInt("n"), o.getInt("maxLen"), o.getLong("seed"), o.optBoolean("lap"), o.optBoolean("special")), (0 until mv.length()).map { val m = mv.getJSONArray(it); longArrayOf(m.getLong(0), m.getLong(1)) }, o.optBoolean("all"))
        }
        main.post { done(r) }
    }

    /** The newest release on GitHub ("1.2.0"), for the update check in Settings. Null if it can't be reached. */
    fun latestVersion(done: (String?) -> Unit) = io.execute {
        val v = call {
            val c = URL("https://api.github.com/repos/taynotfound/cubit/releases/latest").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 6000; c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("User-Agent", "Cubit")
                if (c.responseCode != 200) throw HttpError(c.responseCode, "HTTP ${c.responseCode}")
                JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getString("tag_name").removePrefix("v")
            } finally { c.disconnect() }
        }
        main.post { done(v) }
    }

    /** True when version [a] is newer than [b], comparing dotted numbers ("1.10.0" > "1.9.2"). */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    // Runs on the io thread.
    private fun authed(p: Prefs, method: String, path: String, body: String?): String {
        val token = p.lbToken ?: JSONObject(request("POST", "/v1/register", JSONObject().put("name", p.name).toString(), null))
            .getString("token").also { p.lbToken = it }
        try {
            return request(method, path, body, token)
        } catch (e: HttpError) {
            if (e.code == 401) p.lbToken = null // server forgot us (or data reset): re-register next time
            throw e
        }
    }

    private fun <T> call(f: () -> T): T? = runCatching(f).onSuccess { lastError = null; noNetwork = false }
        .onFailure { lastError = it.message ?: it.javaClass.simpleName; noNetwork = it is java.net.UnknownHostException }.getOrNull()

    private fun request(method: String, path: String, body: String?, token: String?): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 6000; c.readTimeout = 8000
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            if (c.responseCode !in 200..299) {
                val msg = runCatching { JSONObject(c.errorStream.bufferedReader().readText()).optString("error") }.getOrNull()
                throw HttpError(c.responseCode, msg?.ifEmpty { null } ?: "HTTP ${c.responseCode}")
            }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun parseBoard(o: JSONObject): GlobalBoard {
        fun arr(k: String) = o.optJSONArray(k) ?: JSONArray()
        val players = arr("players").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { GlobalPlayer(it.optString("name"), it.optInt("xp"), it.optInt("level"), it.optInt("cleanRuns"), it.optString("code"), it.optString("code").isNotEmpty()) } }
        val scores = arr("scores").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            val pu = it.optJSONArray("powerups") ?: JSONArray()
            ScoreEntry(it.optString("name"), it.optInt("score"), it.optString("label"), it.optString("difficulty"), it.optLong("time"),
                it.optInt("arrows"), it.optLong("ms"), it.optInt("mistakes"), it.optInt("size"), (0 until pu.length()).map { i -> pu.getString(i) }, it.optInt("level"), run = it.optString("run"), code = it.optString("code"))
        } }
        return GlobalBoard(players, scores, maxOf(o.optInt("total"), players.size))
    }
}
