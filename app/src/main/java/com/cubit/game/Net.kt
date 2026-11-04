package com.cubit.game

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class GlobalPlayer(val name: String, val xp: Int, val level: Int, val cleanRuns: Int)
data class GlobalBoard(val players: List<GlobalPlayer>, val scores: List<ScoreEntry>)
data class RunResult(val status: String, val score: Int, val xp: Int, val rank: Int)

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
    fun startRun(p: Prefs, spec: LevelSpec, campaignLevel: Int?, dailyDay: Int?, difficulty: String, fullClear: Boolean, week: Int? = null, done: (String?) -> Unit) {
        val name = p.name
        io.execute {
            val id = call {
                val body = JSONObject().put("difficulty", difficulty).put("name", name).put("goal", if (fullClear) "all" else "pink")
                if (week != null) body.put("mode", "weekly").put("week", week)
                else if (dailyDay != null) body.put("mode", "daily").put("day", dailyDay)
                else if (campaignLevel != null) body.put("mode", "campaign").put("level", campaignLevel)
                else body.put("mode", "free").put("n", spec.n).put("maxLen", spec.maxLen).put("seed", spec.seed)
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

    /** A board run's cube and taps, for watching it back. */
    fun replay(run: String, done: (Pair<LevelSpec, List<LongArray>>?) -> Unit) = io.execute {
        val r = call {
            val o = JSONObject(request("GET", "/v1/runs/$run/replay", null, null))
            val mv = o.getJSONArray("moves")
            LevelSpec(o.getInt("n"), o.getInt("maxLen"), o.getLong("seed"), o.optBoolean("lap")) to (0 until mv.length()).map { val m = mv.getJSONArray(it); longArrayOf(m.getLong(0), m.getLong(1)) }
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
    private fun authed(p: Prefs, method: String, path: String, body: String): String {
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
        val players = arr("players").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { GlobalPlayer(it.optString("name"), it.optInt("xp"), it.optInt("level"), it.optInt("cleanRuns")) } }
        val scores = arr("scores").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            val pu = it.optJSONArray("powerups") ?: JSONArray()
            ScoreEntry(it.optString("name"), it.optInt("score"), it.optString("label"), it.optString("difficulty"), it.optLong("time"),
                it.optInt("arrows"), it.optLong("ms"), it.optInt("mistakes"), it.optInt("size"), (0 until pu.length()).map { i -> pu.getString(i) }, it.optInt("level"), run = it.optString("run"))
        } }
        return GlobalBoard(players, scores)
    }
}
