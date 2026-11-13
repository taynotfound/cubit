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

class NightmareGoldenTest {
    // Pinned against TestLapFingerprintsMatchApp in server/puzzle_test.go.
    @Test fun lapFingerprintsMatchServer() {
        val specs = listOf(specForLevel(1), specForLevel(30), dailySpec(20394), LevelSpec(5, 4, 9)).map { it.copy(lap = true) }
        val expected = listOf(7105386055276243586L, 6209352114291553430L, 2357449551511870926L, 1908624766125275580L)
        assertEquals(expected, specs.map { fingerprint(generate(it)) })
    }

    @Test fun lapCubesAreSolvableUnderTheLapRule() {
        for (level in listOf(1, 5, 20, 60)) {
            val p = generate(specForLevel(level).copy(lap = true))
            assertTrue(p.lap)
            val occ = IntArray(p.cube.size) { -1 }
            p.paths.forEachIndexed { i, path -> path.forEach { occ[it] = i } }
            val left = p.paths.indices.toMutableSet()
            while (left.isNotEmpty()) {
                val free = left.firstOrNull { a -> p.exit(a).all { occ[it] < 0 || occ[it] == a } }
                assertTrue("lap cube stuck at level $level with ${left.size} left", free != null)
                p.paths[free!!].forEach { occ[it] = -1 }; left.remove(free)
            }
        }
    }

    @Test fun aLapCrossesFourFacesAndComesBack() {
        val cube = Cube(4)
        for (c in 0 until cube.size) for (d in cube.tangents(c)) {
            val l = cube.lap(c, d)
            assertEquals(4 * 4 - 1, l.size)
            assertEquals(4, l.map { cube.face[it] }.toSet().size)
        }
    }
}

/** Bomb arrows and what each one blasts, hashed; TestBombsMatchApp in server/puzzle_test.go pins the same values. */
fun bombPrint(p: Puzzle): Long {
    var h = -3750763034362895579L
    fun mix(v: Long) { h = (h xor v) * 1099511628211L }
    p.paths.indices.filter { p.bombs[it] }.forEach { a -> mix(a.toLong()); p.blast(a).sorted().forEach { mix(10_000L + it) } }
    return h
}

class BombTest {
    private val specs = listOf(specForLevel(4), specForLevel(30), LevelSpec(14, 20, -987654321))

    @Test fun bombsMatchServer() {
        val expected = listOf(3104308799670819663L, -2661303133342797834L, 6122402782112281754L)
        assertEquals(expected, specs.map { bombPrint(generate(it)) })
    }

    @Test fun bombsLeaveTheArrowsAlone() {
        for (s in specs) {
            val with = generate(s); val without = generate(s.copy(bombs = false))
            assertEquals(fingerprint(without), fingerprint(with))
            assertTrue("no bombs on ${s}", with.bombs.count { it } > 0)
            assertTrue(without.bombs.none { it })
        }
        assertTrue(generate(dailySpec(20394)).bombs.none { it })
        assertTrue(generate(specForLevel(30).copy(lap = true)).bombs.none { it })
        assertTrue(!specForLevel(1).bombs) // 10×10: the first levels have none
    }

    @Test fun blastsSparePinkAndKeepTheCubeSolvable() {
        for (s in specs) {
            val p = generate(s)
            val occ = IntArray(p.cube.size) { -1 }
            p.paths.forEachIndexed { i, path -> path.forEach { occ[it] = i } }
            for (a in p.paths.indices) assertTrue(p.target !in p.blast(a) && a !in p.blast(a))
            // Greedy, bombs first: blasts only ever take arrows away, so the cube must still clear.
            val left = p.paths.indices.toMutableSet()
            while (left.isNotEmpty()) {
                val free = left.filter { a -> p.exit(a).all { occ[it] < 0 || occ[it] == a } }.maxByOrNull { if (p.bombs[it]) 1 else 0 }
                assertTrue("stuck with ${left.size} left", free != null)
                (listOf(free!!) + p.blast(free).filter { it in left }).forEach { b -> p.paths[b].forEach { occ[it] = -1 }; left.remove(b) }
            }
        }
    }
}
