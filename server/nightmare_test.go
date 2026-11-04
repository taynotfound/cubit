package main

import (
	"testing"
	"time"
)

// Every Nightmare cube must be solvable under the lap rule, and the lap rule must actually bite: on the same cube,
// some arrow that could slide off its face is still stuck because something sits elsewhere on its lap.
func TestNightmareCubes(t *testing.T) {
	stricter := 0
	for _, s := range []LevelSpec{specForLevel(1), specForLevel(20), specForLevel(60), dailySpec(20394), weekSpec(2965), {N: 5, MaxLen: 4, Seed: 9}} {
		s.Lap = true
		p := generate(s)
		if !p.lap || len(p.paths) < 3 {
			t.Fatalf("%+v: %d arrows, lap=%v", s, len(p.paths), p.lap)
		}
		if _, err := replay(p, difficulties["Nightmare"], toMoves(solve(p, human)), false); err != nil {
			t.Fatalf("%+v: solution rejected: %v", s, err)
		}
		occ := occupancy(p)
		for a, path := range p.paths {
			head := path[len(path)-1]
			if laneClear(occ, a, p.cube.ray(head, p.dirs[a])) && !laneClear(occ, a, p.cube.exit(head, p.dirs[a], true)) {
				stricter++
			}
		}
	}
	if stricter == 0 {
		t.Fatal("no arrow was held back by its lap: the lap rule isn't doing anything")
	}
}

func TestLapGoesAllTheWayRound(t *testing.T) {
	c := newCube(5)
	for cell := 0; cell < c.size; cell++ {
		for _, d := range c.tangents(cell) {
			l := c.lap(cell, d)
			if len(l) != 4*5-1 {
				t.Fatalf("cell %d dir %d: lap of %d cells, want %d", cell, d, len(l), 4*5-1)
			}
			faces := map[int]bool{}
			for _, x := range l {
				faces[c.fc[x]] = true
			}
			if len(faces) != 4 {
				t.Fatalf("cell %d dir %d: lap crosses %d faces", cell, d, len(faces))
			}
		}
	}
}

// A tap that only clears the arrow's own face is a bump on Nightmare, and powerups are refused.
func TestNightmareReplayRules(t *testing.T) {
	s := specForLevel(3)
	s.Lap = true
	p := generate(s)
	occ := occupancy(p)
	trap := -1
	for a, path := range p.paths {
		head := path[len(path)-1]
		if a != 0 && laneClear(occ, a, p.cube.ray(head, p.dirs[a])) && !laneClear(occ, a, p.cube.exit(head, p.dirs[a], true)) {
			trap = a
			break
		}
	}
	if trap < 0 {
		t.Skip("this cube has no arrow whose face is clear but lap isn't")
	}
	nm := difficulties["Nightmare"]
	if _, err := replay(p, nm, append([]Move{{trap, 100}}, toMoves(solve(p, human))...), false); err == nil {
		t.Fatal("a bump on the lap should cost Nightmare's only life")
	}
	if _, err := replay(p, nm, append([]Move{{puShield, 50}}, toMoves(solve(p, human))...), false); err == nil {
		t.Fatal("powerup accepted on Nightmare")
	}
}

func TestNightmareRunEndToEnd(t *testing.T) {
	h := newHarness(t)
	_, reg := h.call("POST", "/v1/register", "", map[string]string{"name": "Dreamer"})
	tok := reg["token"].(string)
	code, st := h.call("POST", "/v1/runs", tok, map[string]any{"mode": "campaign", "level": 4, "difficulty": "Nightmare"})
	if code != 200 {
		t.Fatalf("start: %d %v", code, st)
	}
	s := specForLevel(4)
	s.Lap = true
	moves := solve(generate(s), human)
	h.now = h.now.Add(time.Duration(moves[len(moves)-1][1]+800) * time.Millisecond)
	code, fin := h.call("POST", "/v1/runs/"+st["run"].(string)+"/finish", tok, map[string]any{"moves": moves})
	if code != 200 || fin["status"] != "accepted" {
		t.Fatalf("finish: %d %v", code, fin)
	}
	_, board := h.call("GET", "/v1/leaderboard", "", nil)
	top := board["scores"].([]any)[0].(map[string]any)
	code, rp := h.call("GET", "/v1/runs/"+top["run"].(string)+"/replay", "", nil)
	if code != 200 || top["difficulty"] != "Nightmare" || rp["lap"] != true {
		t.Fatalf("board/replay: %v %v", top, rp)
	}
}

func toMoves(m [][2]int64) []Move {
	out := make([]Move, 0, len(m))
	for _, x := range m {
		out = append(out, Move{int(x[0]), x[1]})
	}
	return out
}

func occupancy(p *Puzzle) []int {
	occ := make([]int, p.cube.size)
	for i := range occ {
		occ[i] = -1
	}
	for i, path := range p.paths {
		for _, c := range path {
			occ[c] = i
		}
	}
	return occ
}

func laneClear(occ []int, a int, cells []int) bool {
	for _, c := range cells {
		if occ[c] >= 0 && occ[c] != a {
			return false
		}
	}
	return true
}
