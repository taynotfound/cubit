package com.cubit.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PuzzleTest {
    @Test fun stepsWrapAroundTheCube() {
        val cube = Cube(3)
        // Walking straight 12 cells (4 faces × 3) in any direction returns to the start.
        for (c in 0 until cube.size) for (d in cube.tangents(c)) {
            var cur = c; var dir = d
            repeat(12) { val s = cube.step(cur, dir); cur = s / 8; dir = s % 8; assertTrue(cur >= 0) }
            assertEquals(c, cur)
        }
    }

    @Test fun generatedLevelsAreValidAndSolvable() {
        for (level in listOf(1, 2, 5, 10, 25, 60, 120)) {
            val p = generate(specForLevel(level))
            val cube = p.cube
            val occ = IntArray(cube.size) { -1 }
            p.paths.forEachIndexed { i, path ->
                assertTrue(path.size >= 2)
                for (c in path) { assertEquals("overlap", -1, occ[c]); occ[c] = i }
                for (k in 1 until path.size) {
                    assertTrue("body not connected", cube.tangents(path[k - 1]).any { cube.step(path[k - 1], it) / 8 == path[k] })
                }
                assertTrue("head dir not tangent", p.dirs[i] in cube.tangents(path.last()))
            }
            // Greedy: repeatedly remove any free arrow; must clear the whole cube.
            val left = p.paths.indices.toMutableSet()
            while (left.isNotEmpty()) {
                val free = left.firstOrNull { a -> cube.ray(p.paths[a].last(), p.dirs[a]).all { occ[it] < 0 || occ[it] == a } }
                assertTrue("stuck at level $level with ${left.size} left", free != null)
                p.paths[free!!].forEach { occ[it] = -1 }; left.remove(free)
            }
            val cover = p.paths.sumOf { it.size } * 100 / cube.size
            println("level $level n=${cube.n} arrows=${p.paths.size} cover=$cover%")
        }
    }
}

/** FNV-1a over every arrow's cells and direction; server/puzzle_test.go asserts the same values. */
fun fingerprint(p: Puzzle): Long {
    var h = -3750763034362895579L // 14695981039346656037 as signed
    fun mix(v: Long) { h = (h xor v) * 1099511628211L }
    p.paths.forEachIndexed { i, path -> path.forEach { mix(it.toLong()) }; mix(1000L + p.dirs[i]); mix(0xFFFFL) }
    return h
}

val GOLDEN_SPECS = listOf(specForLevel(1), specForLevel(7), specForLevel(30), LevelSpec(2, 2, 123), LevelSpec(14, 20, -987654321), dailySpec(20394), weekSpec(2965))

class GoldenTest {
    // If these change, the server can no longer replay runs: update server/puzzle_test.go in the same commit.
    @Test fun fingerprintsMatchServer() {
        val expected = listOf(-7336180328909340747L, -3800580958801716592L, -1263255144737735575L, -2333106797818041265L, 1859335250571087320L, -7805004108394037706L, 2451205338930652789L)
        assertEquals(expected, GOLDEN_SPECS.map { fingerprint(generate(it)) })
    }
}
