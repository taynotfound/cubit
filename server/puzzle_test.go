package main

import (
	"sort"
	"testing"
)

func fingerprint(p *Puzzle) int64 {
	h := uint64(14695981039346656037)
	mix := func(v int64) { h = (h ^ uint64(v)) * 1099511628211 }
	for i, path := range p.paths {
		for _, c := range path {
			mix(int64(c))
		}
		mix(1000 + int64(p.dirs[i]))
		mix(0xFFFF)
	}
	return int64(h)
}

// Same specs and values as GoldenTest in the app's PuzzleTest.kt: the app and server must build identical cubes.
func TestFingerprintsMatchApp(t *testing.T) {
	specs := []LevelSpec{specForLevel(1), specForLevel(7), specForLevel(30), {N: 2, MaxLen: 2, Seed: 123}, {N: 14, MaxLen: 20, Seed: -987654321}, dailySpec(20394), weekSpec(2965)}
	want := []int64{-7336180328909340747, -3800580958801716592, -1263255144737735575, -2333106797818041265, 1859335250571087320, -7805004108394037706, 2451205338930652789}
	for i, s := range specs {
		if got := fingerprint(generate(s)); got != want[i] {
			t.Errorf("spec %+v: fingerprint %d, app has %d", s, got, want[i])
		}
	}
}

// Nightmare (lap) cubes, pinned against NightmareGoldenTest in PuzzleTest.kt.
func TestLapFingerprintsMatchApp(t *testing.T) {
	specs := []LevelSpec{specForLevel(1), specForLevel(30), dailySpec(20394), {N: 5, MaxLen: 4, Seed: 9}}
	want := []int64{7105386055276243586, 6209352114291553430, 2357449551511870926, 1908624766125275580}
	for i, s := range specs {
		s.Lap = true
		if got := fingerprint(generate(s)); got != want[i] {
			t.Errorf("spec %+v: fingerprint %d, app has %d", s, got, want[i])
		}
	}
}

// Bomb arrows and their blasts, pinned against BombTest in PuzzleTest.kt.
func TestBombsMatchApp(t *testing.T) {
	specs := []LevelSpec{specForLevel(4), specForLevel(30), {N: 14, MaxLen: 20, Seed: -987654321}}
	want := []int64{3104308799670819663, -2661303133342797834, 6122402782112281754}
	for i, s := range specs {
		s.Bombs = true
		p := generate(s)
		occ := make([]int, p.cube.size)
		for j := range occ {
			occ[j] = -1
		}
		for a, path := range p.paths {
			for _, c := range path {
				occ[c] = a
			}
		}
		h := uint64(14695981039346656037)
		mix := func(v int64) { h = (h ^ uint64(v)) * 1099511628211 }
		for a := range p.paths {
			if !p.bombs[a] {
				continue
			}
			mix(int64(a))
			b := p.blast(a, occ)
			sort.Ints(b)
			for _, x := range b {
				mix(10_000 + int64(x))
			}
		}
		if got := int64(h); got != want[i] {
			t.Errorf("spec %+v: bomb print %d, app has %d", s, got, want[i])
		}
		if fingerprint(p) != fingerprint(generate(LevelSpec{N: s.N, MaxLen: s.MaxLen, Seed: s.Seed})) {
			t.Errorf("spec %+v: bombs changed the arrows", s)
		}
	}
}
