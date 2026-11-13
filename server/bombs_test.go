package main

import (
	"testing"
	"time"
)

// solveBombs plays greedily, bombs first, letting each bomb's blast clear its neighbours; pink last.
func solveBombs(p *Puzzle) [][2]int64 {
	occ := make([]int, p.cube.size)
	for i := range occ {
		occ[i] = -1
	}
	for i, path := range p.paths {
		for _, c := range path {
			occ[c] = i
		}
	}
	start := append([]int(nil), occ...)
	gone := make([]bool, len(p.paths))
	var moves [][2]int64
	ms := int64(0)
	for {
		pick := -1
		for a := range p.paths {
			if gone[a] || a == 0 && len(moves) == 0 {
				continue
			}
			path := p.paths[a]
			free := true
			for _, c := range p.cube.exit(path[len(path)-1], p.dirs[a], p.lap) {
				if occ[c] >= 0 && occ[c] != a {
					free = false
					break
				}
			}
			if free && a != 0 && (pick < 0 || p.bombs[a] && !p.bombs[pick]) {
				pick = a
			}
			if free && a == 0 && pick < 0 {
				pick = 0
			}
		}
		if pick < 0 {
			return moves
		}
		ms += 400 + int64(len(moves)%7)*137
		moves = append(moves, [2]int64{int64(pick), ms})
		for _, a := range append([]int{pick}, p.blast(pick, start)...) {
			if !gone[a] {
				gone[a] = true
				for _, c := range p.paths[a] {
					occ[c] = -1
				}
			}
		}
		if pick == 0 {
			return moves
		}
	}
}

func TestBombRunReplays(t *testing.T) {
	spec := LevelSpec{N: 12, MaxLen: 10, Seed: 4242, Bombs: true}
	p := generate(spec)
	moves := solveBombs(p)
	r, err := replay(p, difficulties["Zen"], toMoves(moves), false)
	if err != nil {
		t.Fatal(err)
	}
	if r.Cleared <= len(moves) {
		t.Fatalf("bombs cleared nothing extra: %d cleared in %d taps", r.Cleared, len(moves))
	}
	// The same taps on the cube without bombs hit arrows the blasts would have cleared.
	if _, err := replay(generate(LevelSpec{N: 12, MaxLen: 10, Seed: 4242}), difficulties["Zen"], toMoves(moves), false); err == nil {
		t.Fatal("bomb run verified on a cube without bombs")
	}
	// Zen's take-back puts a bomb's whole blast back.
	first := -1
	for i, m := range moves {
		if p.bombs[m[0]] {
			first = i
			break
		}
	}
	withUndo := append(append(append([][2]int64{}, moves[:first+1]...), [2]int64{undoTap, moves[first][1] + 1}, [2]int64{moves[first][0], moves[first][1] + 2}), moves[first+1:]...)
	for i := first + 3; i < len(withUndo); i++ {
		withUndo[i][1] += 2
	}
	r2, err := replay(p, difficulties["Zen"], toMoves(withUndo), false)
	if err != nil || r2.Cleared != r.Cleared {
		t.Fatalf("undo of a bomb: %v, cleared %d want %d", err, r2.Cleared, r.Cleared)
	}
}

func TestBombsOnlyWhereAsked(t *testing.T) {
	h := newHarness(t)
	_, reg := h.call("POST", "/v1/register", "", map[string]string{"name": "Boom"})
	tok := reg["token"].(string)
	spec := LevelSpec{N: 12, MaxLen: 10, Seed: 4242, Bombs: true}
	_, st := h.call("POST", "/v1/runs", tok, map[string]any{"mode": "free", "n": 12, "maxLen": 10, "seed": 4242, "bombs": true, "difficulty": "Zen"})
	h.now = h.now.Add(5 * time.Minute)
	if code, out := h.call("POST", "/v1/runs/"+st["run"].(string)+"/finish", tok, map[string]any{"moves": solveBombs(generate(spec))}); code != 200 || out["status"] != "accepted" {
		t.Fatalf("bomb run: %d %v", code, out)
	}
	// Daily cubes never have bombs, whatever the app sends.
	day := int(h.now.Unix() / 86400)
	h.now = h.now.Add(time.Minute)
	_, st = h.call("POST", "/v1/runs", tok, map[string]any{"mode": "daily", "day": day, "bombs": true, "difficulty": "Zen"})
	h.app.mu.Lock()
	bombs := h.app.runs[st["run"].(string)].Spec.Bombs
	h.app.mu.Unlock()
	if bombs {
		t.Fatal("daily run got bombs")
	}
}
