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
}

/** paths[i] runs tail → head; dirs[i] is the head's travel direction. paths[target] is the pink arrow to free. */
class Puzzle(val cube: Cube, val paths: List<IntArray>, val dirs: IntArray, val target: Int = 0)

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

data class LevelSpec(val n: Int, val maxLen: Int, val seed: Long)

/** One cube for everyone per UTC day (days since 1970). The server builds the same one (dailySpec in puzzle.go). */
fun dailySpec(day: Int) = LevelSpec(12, 10, day * 1_000_003L + 77)
fun utcDay() = (System.currentTimeMillis() / 86_400_000L).toInt()

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
 * to over a fifth as arrows get longer with the level.
 */
fun generate(spec: LevelSpec): Puzzle {
    var best = build(spec); var bestDepth = mustClear(best)
    fun need(p: Puzzle) = p.paths.size * (10 + maxOf(0, spec.maxLen - 8)) / 80
    var k = 1
    while (bestDepth < need(best) && k <= 10) {
        val p = build(spec.copy(seed = spec.seed + k * 1_000_003L)); val d = mustClear(p)
        if (d > bestDepth) { best = p; bestDepth = d }
        k++
    }
    return best
}

/** Arrows that must be removed before the pink one is free: those on its way out, those on theirs, and so on. */
fun mustClear(p: Puzzle): Int {
    val occ = IntArray(p.cube.size) { -1 }
    p.paths.forEachIndexed { i, path -> path.forEach { occ[it] = i } }
    val seen = BooleanArray(p.paths.size).also { it[0] = true }
    val queue = ArrayDeque(listOf(0)); var n = 0
    while (queue.isNotEmpty()) {
        val a = queue.removeFirst()
        for (c in p.cube.ray(p.paths[a].last(), p.dirs[a])) {
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
    while (filled < goal && fails < 400) {
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
        val pick = options.firstOrNull { (p, d) -> cube.ray(p.last(), d).all { occ[it] < 0 && it !in inPath } }
        if (pick == null) { fails++; continue }
        val id = paths.size
        paths.add(pick.first); dirs.add(pick.second)
        for (c in pick.first) { occ[c] = id; free.take(c) }
        filled += pick.first.size; fails = 0
    }
    return Puzzle(cube, paths, dirs.toIntArray())
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
    @Synchronized fun get(spec: LevelSpec): Puzzle = map.getOrPut(spec) { generate(spec) }
    @Synchronized fun has(spec: LevelSpec) = map.containsKey(spec)
}
