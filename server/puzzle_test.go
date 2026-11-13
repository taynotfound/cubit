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

// Special arrows, pinned against SpecialTest in PuzzleTest.kt: each bomb and its blast, each lock and its key, each
// two-way arrow and its tail's way out.
func TestSpecialsMatchApp(t *testing.T) {
	specs := []LevelSpec{specForLevel(4), specForLevel(30), {N: 14, MaxLen: 20, Seed: -987654321}, endlessSpec(dailyEndlessSeed(20800), 6)}
	want := []int64{-2168532955706612572, 893908734425870072, 8367443714046685816, 776641724789738145}
	for i, s := range specs {
		s.Special = true
		p := generate(s)
		occ := occupancy(p)
		h := uint64(14695981039346656037)
		mix := func(v int64) { h = (h ^ uint64(v)) * 1099511628211 }
		for a := range p.paths {
			if p.bombs[a] {
				mix(int64(a))
				b := p.blast(a, occ)
				sort.Ints(b)
				for _, x := range b {
					mix(10_000 + int64(x))
				}
			}
			if p.keys[a] >= 0 {
				mix(20_000 + int64(a))
				mix(30_000 + int64(p.keys[a]))
			}
			if p.twoWay[a] {
				mix(40_000 + int64(a))
				for _, c := range p.tailExit(a) {
					mix(50_000 + int64(c))
				}
			}
		}
		for c := range p.warp {
			if p.warp[c] > c {
				mix(60_000 + int64(c))
				mix(70_000 + int64(p.warp[c]))
			}
		}
		if got := int64(h); got != want[i] {
			t.Errorf("spec %+v: special print %d, app has %d", s, got, want[i])
		}
		if fingerprint(p) != fingerprint(generate(LevelSpec{N: s.N, MaxLen: s.MaxLen, Seed: s.Seed})) {
			t.Errorf("spec %+v: special arrows changed the arrows", s)
		}
	}
}
