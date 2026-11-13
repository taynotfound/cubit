package com.cubit.game

// Directions and face normals share one encoding: 0:+x 1:-x 2:+y 3:-y 4:+z 5:-z.
val DX = intArrayOf(1, -1, 0, 0, 0, 0)
val DY = intArrayOf(0, 0, 1, -1, 0, 0)
val DZ = intArrayOf(0, 0, 0, 0, 1, -1)
fun opposite(d: Int) = d xor 1

/**
 * Surface grid of an n×n×n cube in doubled coordinates: the cube spans [-n, n] on each axis,
 * cell centers sit at odd offsets on their face, so one cell step is 2 units.
 */
class Cube(val n: Int) {
    val size = 6 * n * n
    val px = IntArray(size); val py = IntArray(size); val pz = IntArray(size)
    val face = IntArray(size)
    private val index = HashMap<Int, Int>(size * 2)

    init {
        var i = 0
        for (f in 0 until 6) {
            val a = f / 2; val s = if (f % 2 == 0) n else -n
            for (u in -n + 1 until n step 2) for (v in -n + 1 until n step 2) {
                val c = IntArray(3); c[a] = s; c[(a + 1) % 3] = u; c[(a + 2) % 3] = v
                px[i] = c[0]; py[i] = c[1]; pz[i] = c[2]; face[i] = f
                index[key(c[0], c[1], c[2])] = i
                i++
            }
        }
    }

    private fun key(x: Int, y: Int, z: Int) = ((x + 512) shl 20) or ((y + 512) shl 10) or (z + 512)
    fun cellAt(x: Int, y: Int, z: Int) = index[key(x, y, z)] ?: -1

    fun tangents(c: Int) = (0 until 6).filter { it / 2 != face[c] / 2 }

    /** One step from cell [c] moving in [d]; returns newCell*8 + newDirection (direction changes when wrapping an edge). */
    fun step(c: Int, d: Int): Int {
        val x = px[c] + 2 * DX[d]; val y = py[c] + 2 * DY[d]; val z = pz[c] + 2 * DZ[d]
        if (x * DX[d] + y * DY[d] + z * DZ[d] > n) {
            val f = face[c]
            return cellAt(px[c] + DX[d] - DX[f], py[c] + DY[d] - DY[f], pz[c] + DZ[d] - DZ[f]) * 8 + opposite(f)
        }
        return cellAt(x, y, z) * 8 + d
    }

    /** Cells straight ahead of [c] on its own face until the face edge, where an arrow flies off. */
    fun ray(c: Int, d: Int): IntArray {
        val out = ArrayList<Int>()
        var cur = c
        while (true) {
            val s = step(cur, d)
            if (s % 8 != d) break
            cur = s / 8; out.add(cur)
        }
        return out.toIntArray()
    }

    /** Every cell once around the cube from [c] heading [d], over the edges and back to (not including) [c]. */
    fun lap(c: Int, d: Int): IntArray {
        val out = ArrayList<Int>()
        var cur = c; var dir = d
        while (true) {
            val s = step(cur, dir)
            cur = s / 8; dir = s % 8
            if (cur == c) break
            out.add(cur)
        }
        return out.toIntArray()
    }

    /**
     * The cells an arrow's head crosses on its way out: normally the run to the face edge. On a lap cube (Nightmare)
     * a whole lap round the cube first, back over its own start, then that run. Mirrors exit in puzzle.go.
     */
    fun exit(c: Int, d: Int, lapRule: Boolean): IntArray = if (!lapRule) ray(c, d) else lap(c, d) + c + ray(c, d)
}

/**
 * paths[i] runs tail → head; dirs[i] is the head's travel direction. paths[target] is the pink arrow to free.
 * [lap]: Nightmare's rule, an arrow only leaves if its whole lap round the cube is clear.
 */
class Puzzle(val cube: Cube, val paths: List<IntArray>, val dirs: IntArray, val target: Int = 0, val lap: Boolean = false,
             /** Bombs take the arrows touching them along when they leave ([cleared]). */
             val bombs: BooleanArray = BooleanArray(paths.size),
             /** keys[a]: the arrow that has to be gone before [a] can leave, or -1. */
             val keys: IntArray = IntArray(paths.size) { -1 },
             /** Two-way arrows leave tail first when their head's way is blocked. */
             val twoWay: BooleanArray = BooleanArray(paths.size),
             /** Wormholes: warp[cell] is the portal on the opposite face it leads to, or -1. Portals sit on empty cells. */
             val warp: IntArray = IntArray(cube.size) { -1 }) {
    /** The cells arrow [a] must find empty to leave: through a wormhole if its way out crosses one. */
    fun exit(a: Int) = warped(cube.exit(paths[a].last(), dirs[a], lap), dirs[a])

    /** The direction a two-way arrow's tail leaves in: back against the body's first step. */
    fun tailDir(a: Int): Int { val p = paths[a]; return opposite(cube.tangents(p[0]).first { cube.step(p[0], it) / 8 == p[1] }) }
    fun tailExit(a: Int) = warped(cube.exit(paths[a][0], tailDir(a), lap), tailDir(a))

    /** A way out that reaches a portal dives through the cube and carries on, the same way, from the portal opposite.
     *  One jump at most. Mirrors warped in puzzle.go. */
    private fun warped(cells: IntArray, d: Int): IntArray {
        if (lap) return cells
        for (i in cells.indices) { val q = warp[cells[i]]; if (q >= 0) return cells.copyOf(i + 1) + q + cube.ray(q, d) }
        return cells
    }

    private var headCache: Array<IntArray>? = null
    private val heads get() = headCache ?: Array(paths.size) { exit(it) }.also { headCache = it }
    private val tails by lazy { Array(paths.size) { if (twoWay[it]) tailExit(it) else IntArray(0) } }

    /** Twist mode: every arrowhead on face [f] turns a quarter turn about the face (the bodies stay put). Only on a
     *  [forPlay] copy, never a cached cube. Mirrors turn in puzzle.go. */
    fun turn(f: Int) {
        for (a in paths.indices) if (cube.face[paths[a].last()] == f) dirs[a] = turnDir(dirs[a], f)
        headCache = null
    }

    /** A copy whose arrowheads can be turned without touching the cube everyone else (and the cache) shares. */
    fun forPlay() = Puzzle(cube, paths, dirs.copyOf(), target, lap, bombs, keys, twoWay, warp)

    /** How [a] can leave the board [occ] (owners per cell, -1 empty) with arrows [gone] removed: [HEAD], [TAIL] or
     *  [STUCK]. Its own cells never block it. The full-clear rule (pink last) is the caller's. Mirrors way in puzzle.go. */
    fun way(a: Int, occ: IntArray, gone: BooleanArray): Int = when {
        keys[a] >= 0 && !gone[keys[a]] -> STUCK
        heads[a].all { occ[it] < 0 || occ[it] == a } -> HEAD
        twoWay[a] && tails[a].all { occ[it] < 0 || occ[it] == a } -> TAIL
        else -> STUCK
    }

    private val owner by lazy { IntArray(cube.size) { -1 }.also { o -> paths.forEachIndexed { i, p -> p.forEach { o[it] = i } } } }

    /** The arrows bomb [a] takes with it when it leaves: every other arrow with a cell next to one of its cells, never
     *  the pink one. Empty for an ordinary arrow. Mirrors blast in puzzle.go. */
    fun blast(a: Int): IntArray {
        if (!bombs[a]) return IntArray(0)
        val out = LinkedHashSet<Int>()
        for (c in paths[a]) for (d in cube.tangents(c)) { val b = owner[cube.step(c, d) / 8]; if (b > 0 && b != a) out.add(b) }
        return out.toIntArray()
    }

    /** Everything that goes when [a] leaves: [a] first, then its blast, and the blasts of bombs caught in it (a chain
     *  reaction), skipping arrows already [gone]. Mirrors cleared in puzzle.go. */
    fun cleared(a: Int, gone: BooleanArray): IntArray {
        val out = arrayListOf(a); val seen = hashSetOf(a); var i = 0
        while (i < out.size) for (x in blast(out[i++])) if (!gone[x] && seen.add(x)) out.add(x)
        return out.toIntArray()
    }

    companion object { const val STUCK = 0; const val HEAD = 1; const val TAIL = 2 }
}

/** Direction [d] turned a quarter turn about face [f]'s normal (n × d). */
fun turnDir(d: Int, f: Int): Int {
    val cx = DY[f] * DZ[d] - DZ[f] * DY[d]; val cy = DZ[f] * DX[d] - DX[f] * DZ[d]; val cz = DX[f] * DY[d] - DY[f] * DX[d]
    return (0 until 6).first { DX[it] == cx && DY[it] == cy && DZ[it] == cz }
}

/** The cell straight through the cube from [c]: same place on the opposite face. */
fun Cube.antipode(c: Int): Int {
    val v = intArrayOf(px[c], py[c], pz[c]); v[face[c] / 2] = -v[face[c] / 2]
    return cellAt(v[0], v[1], v[2])
}

/** Whether every arrow can still get out (bombs aside, which only help). Taking an arrow away never blocks another,
 *  so clearing any free arrow until none is left answers it, in any order. */
fun solvable(p: Puzzle): Boolean {
    val occ = IntArray(p.cube.size) { -1 }; p.paths.forEachIndexed { i, path -> path.forEach { occ[it] = i } }
    val gone = BooleanArray(p.paths.size); var left = p.paths.size
    while (left > 0) {
        var moved = false
        for (a in p.paths.indices) if (!gone[a] && p.way(a, occ, gone) != Puzzle.STUCK) { gone[a] = true; left--; moved = true; p.paths[a].forEach { occ[it] = -1 } }
        if (!moved) return false
    }
    return true
}

/** Up to this many wormhole pairs per cube. Mirrors maxWormholes in puzzle.go. */
const val MAX_WORMHOLES = 3

/**
 * Mirrors wormholesFor in puzzle.go: portal pairs on empty cells straight through the cube from each other, from their
 * own RNG stream. A wormhole changes ways out, so pairs come off again (last first) until the cube is solvable.
 */
fun wormholesFor(p: Puzzle, seed: Long): IntArray {
    val owner = IntArray(p.cube.size) { -1 }; p.paths.forEachIndexed { i, path -> path.forEach { owner[it] = i } }
    val free = (0 until p.cube.size).filter { owner[it] < 0 }
    val warp = IntArray(p.cube.size) { -1 }
    if (free.isEmpty()) return warp
    val r = Rng(seed xor 0x576F726DL); val pairs = ArrayList<Int>()
    for (t in 0 until 40) {
        if (pairs.size == MAX_WORMHOLES) break
        val c = free[r.nextInt(free.size)]; val q = p.cube.antipode(c)
        if (q >= 0 && q != c && owner[q] < 0 && warp[c] < 0 && warp[q] < 0) { warp[c] = q; warp[q] = c; pairs.add(c) }
    }
    while (pairs.isNotEmpty() && !solvable(Puzzle(p.cube, p.paths, p.dirs, p.target, p.lap, p.bombs, p.keys, p.twoWay, warp))) {
        val c = pairs.removeAt(pairs.size - 1); warp[warp[c]] = -1; warp[c] = -1
    }
    return warp
}

/** A cube anyone can make from a name: everyone who types the same name (case and spaces aside) gets the same one. */
fun nameSeed(name: String): Long {
    var h = -3750763034362895579L
    for (b in name.trim().lowercase().toByteArray()) h = (h xor (b.toLong() and 0xff)) * 1099511628211L
    return h
}
fun nameSpec(name: String) = LevelSpec(12, 10, nameSeed(name))

/** About one arrow in this many is a bomb, a locked arrow, or two-way. Mirror the constants in puzzle.go. */
const val BOMB_EVERY = 30
const val LOCK_EVERY = 40
const val TWO_WAY_EVERY = 25

/**
 * Mirrors specialsFor in puzzle.go. Each kind has its own RNG stream and is laid on after the cube is built, so special
 * arrows never change the arrows themselves. The pink arrow (0) is never special. A lock's key is always placed later
 * than the arrow it locks, so in the generator's reverse order the key goes first and every cube stays solvable.
 */
fun specialsFor(n: Int, seed: Long): Triple<BooleanArray, IntArray, BooleanArray> {
    val rb = Rng(seed xor 0x426F6D62L)
    val bombs = BooleanArray(n) { i -> i > 0 && rb.nextInt(BOMB_EVERY) == 0 }
    val rl = Rng(seed xor 0x4C6F636BL)
    val keys = IntArray(n) { -1 }
    for (i in 1 until n - 1) if (rl.nextInt(LOCK_EVERY) == 0 && !bombs[i]) keys[i] = i + 1 + rl.nextInt(minOf(30, n - 1 - i))
    val rt = Rng(seed xor 0x54776F57L)
    val two = BooleanArray(n)
    for (i in 1 until n) if (rt.nextInt(TWO_WAY_EVERY) == 0 && !bombs[i] && keys[i] < 0) two[i] = true
    return Triple(bombs, keys, two)
}

/**
 * SplitMix64. Puzzles must come out bit-identical on the phone and on the leaderboard server (server/puzzle.go),
 * which replays every ranked run, so generation uses only this RNG and integer maths. Change both together.
 */
class Rng(private var s: Long) {
    fun next(): Long {
        s += 0x9E3779B97F4A7C15uL.toLong()
        var z = s
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }
    fun nextInt(bound: Int) = ((next() ushr 33) % bound).toInt()
    fun <T> shuffle(list: MutableList<T>): MutableList<T> {
        for (i in list.size - 1 downTo 1) { val j = nextInt(i + 1); val t = list[i]; list[i] = list[j]; list[j] = t }
        return list
    }
}

/**
 * [lap] makes it a Nightmare cube: built so every arrow can clear a full lap of the cube, in some order.
 * [special]: bombs, locked and two-way arrows (1.7+). On by default from 11×11 up; the shared daily and weekly cubes,
 * Nightmare, and specs saved or shared by older versions have none. The server only adds them when the app asks
 * (Net.startRun), so older apps' runs still verify.
 */
data class LevelSpec(val n: Int, val maxLen: Int, val seed: Long, val lap: Boolean = false, val special: Boolean = n >= 11) {
    val hasSpecial get() = special && !lap
}

/** One cube for everyone per UTC day (days since 1970). The server builds the same one (dailySpec in puzzle.go). */
fun dailySpec(day: Int) = LevelSpec(12, 10, day * 1_000_003L + 77, special = false)
/** The first daily cube (2 Nov 2026, Cubit 1.0.0); the archive starts here. */
const val FIRST_DAILY = 20759
fun utcDay() = (System.currentTimeMillis() / 86_400_000L).toInt()

/** The weekly challenge: one big cube for everyone, Monday to Sunday (UTC). Mirrors weekSpec in puzzle.go. */
fun weekSpec(week: Int) = LevelSpec(16, 12, week * 7_000_003L + 5, special = false)
/** Weeks since 1970, starting on Mondays (day 0 was a Thursday). */
fun utcWeek(day: Int = utcDay()) = (day + 3) / 7
/** First day (Monday) of a week, for labels. */
fun weekStart(week: Int) = week * 7 - 3

/** Cube [k] (from 0) of an endless run: one size bigger each time. Mirrors endlessSpec in puzzle.go. */
fun endlessSpec(seed: Long, k: Int) = LevelSpec((7 + k).coerceAtMost(14), (5 + k / 2).coerceAtMost(12), seed + k * 7_777L)
/** Daily endless: the same run of cubes for everyone that day. Mirrors dailyEndlessSeed in puzzle.go. */
fun dailyEndlessSeed(day: Int) = day * 3_000_017L + 9

fun specForLevel(level: Int) = LevelSpec(
    n = (10 + (level - 1) / 3).coerceAtMost(18),
    maxLen = (8 + level / 4).coerceAtMost(16),
    seed = level * 7919L + 17,
)

/**
 * Builds a solvable puzzle in reverse: each new arrow's exit ray must be clear of every arrow
 * already placed, so removing arrows in reverse placement order always works — and from any
 * partial state the most recently placed remaining arrow is always free.
 */
/**
 * Rerolls (up to 10 times) until enough arrows stand between the pink one and the edge: an eighth at first, rising
 * to over a fifth as arrows get longer with the level. Nightmare's cubes are sparse, so there it's a third.
 */
fun generate(spec: LevelSpec): Puzzle {
    var best = build(spec); var bestDepth = mustClear(best)
    fun need(p: Puzzle) = if (spec.lap) p.paths.size / 3 else p.paths.size * (10 + maxOf(0, spec.maxLen - 8)) / 80
    var k = 1
    while (bestDepth < need(best) && k <= 10) {
        val p = build(spec.copy(seed = spec.seed + k * 1_000_003L)); val d = mustClear(p)
        if (d > bestDepth) { best = p; bestDepth = d }
        k++
    }
    if (!spec.hasSpecial) return best
    val (bombs, keys, two) = specialsFor(best.paths.size, spec.seed)
    val special = Puzzle(best.cube, best.paths, best.dirs, best.target, best.lap, bombs, keys, two)
    return Puzzle(best.cube, best.paths, best.dirs, best.target, best.lap, bombs, keys, two, wormholesFor(special, spec.seed))
}

/** Arrows that must be removed before the pink one is free: those on its way out, those on theirs, and so on. */
fun mustClear(p: Puzzle): Int {
    val occ = IntArray(p.cube.size) { -1 }
    p.paths.forEachIndexed { i, path -> path.forEach { occ[it] = i } }
    val seen = BooleanArray(p.paths.size).also { it[0] = true }
    val queue = ArrayDeque(listOf(0)); var n = 0
    while (queue.isNotEmpty()) {
        val a = queue.removeFirst()
        for (c in p.exit(a)) {
            val b = occ[c]
            if (b >= 0 && !seen[b]) { seen[b] = true; n++; queue.add(b) }
        }
    }
    return n
}

private fun build(spec: LevelSpec): Puzzle {
    val cube = Cube(spec.n)
    val rnd = Rng(spec.seed)
    val occ = IntArray(cube.size) { -1 }
    val paths = ArrayList<IntArray>(); val dirs = ArrayList<Int>()
    var filled = 0; var fails = 0
    val goal = cube.size * 92 / 100
    val mid = -spec.n + 1 + 2 * (spec.n / 2)
    val center = cube.cellAt(mid, mid, spec.n) // front (+z) face

    val free = FreeCells(cube.size)
    val maxFails = if (spec.lap) 2000 else 400 // a clear lap is rare once a few arrows are down: keep trying longer
    while (filled < goal && fails < maxFails) {
        if (free.count < 2) break
        // Placed first = the pink target: it sits under everything placed after it.
        val start = if (paths.isEmpty()) center else free.kth(rnd.nextInt(free.count))
        val len = if (paths.isEmpty()) maxOf(4, spec.n + 1) else 2 + rnd.nextInt(spec.maxLen - 1)
        val path = arrayListOf(start); val inPath = hashSetOf(start)
        var cur = start
        var dir = cube.tangents(start).let { it[rnd.nextInt(it.size)] }
        var firstDir = dir
        var arrive = dir
        for (i in 1 until len) {
            val opts = rnd.shuffle(cube.tangents(cur).filter { it != opposite(dir) }.toMutableList())
            if (rnd.nextInt(4) == 0 && dir in opts) { opts.remove(dir); opts.add(0, dir) } // rarely straight on: arrows wind
            var moved = false
            for (d in opts) {
                val s = cube.step(cur, d); val nc = s / 8
                if (nc < 0 || occ[nc] >= 0 || nc in inPath) continue
                path.add(nc); inPath.add(nc); cur = nc; dir = s % 8; arrive = dir
                if (i == 1) firstDir = d
                moved = true; break
            }
            if (!moved) break
        }
        if (path.size < 2) { fails++; continue }

        // Head can be either end: the walk's end (moving on), or its start (moving back against the first step).
        val options = rnd.shuffle(mutableListOf(path.toIntArray() to arrive, path.reversed().toIntArray() to opposite(firstDir)))
        // A lap may cross the arrow's own body: it slides along behind its head, so that's no block.
        val pick = options.firstOrNull { (p, d) -> cube.exit(p.last(), d, spec.lap).all { occ[it] < 0 && (spec.lap || it !in inPath) } }
        if (pick == null) { fails++; continue }
        val id = paths.size
        paths.add(pick.first); dirs.add(pick.second)
        for (c in pick.first) { occ[c] = id; free.take(c) }
        filled += pick.first.size; fails = 0
    }
    return Puzzle(cube, paths, dirs.toIntArray(), lap = spec.lap)
}

/**
 * The empty cells in index order, as a Fenwick tree: picking "the k-th empty cell" is O(log n) instead of a full
 * scan per arrow. Same answer as filtering the empty cells and indexing, so cubes stay identical to the server's.
 */
class FreeCells(private val size: Int) {
    private val fen = IntArray(size + 1)
    var count = size; private set
    init { for (i in 1..size) { fen[i] += 1; val j = i + (i and -i); if (j <= size) fen[j] += fen[i] } }
    fun take(c: Int) { var i = c + 1; while (i <= size) { fen[i]--; i += i and -i }; count-- }
    fun kth(k: Int): Int {
        var pos = 0; var rem = k + 1; var step = Integer.highestOneBit(size)
        while (step > 0) { val nx = pos + step; if (nx <= size && fen[nx] < rem) { pos = nx; rem -= fen[nx] }; step = step shr 1 }
        return pos
    }
}

/** Recently built cubes, so the home preview and the level that follows don't generate the same cube twice. */
object PuzzleCache {
    private val map = object : LinkedHashMap<LevelSpec, Puzzle>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<LevelSpec, Puzzle>?) = size > 4
    }
    // Generate outside the lock: a big cube building in the background must not stall the main thread's has()/get().
    // Two threads may build the same spec at once; generation is deterministic, so either result is fine.
    fun get(spec: LevelSpec): Puzzle = synchronized(this) { map[spec] } ?: generate(spec).also { synchronized(this) { map[spec] = it } }
    @Synchronized fun has(spec: LevelSpec) = map.containsKey(spec)
}
