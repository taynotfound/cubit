package main

import (
	"net/http"
	"strconv"
	"testing"
	"time"
)

// playOut plays a cube greedily under the full rules (locks, two-way arrows, chain reactions), bombs first if asked,
// the pink arrow last of all.
func playOut(p *Puzzle, bombsFirst bool) [][2]int64 {
	occ := occupancy(p)
	start := append([]int(nil), occ...)
	gone := make([]bool, len(p.paths))
	var moves [][2]int64
	ms := int64(0)
	for {
		pick := -1
		for a := range p.paths {
			if gone[a] || a == 0 || !p.free(a, occ, gone) {
				continue
			}
			if pick < 0 || bombsFirst && p.bombs[a] && !p.bombs[pick] {
				pick = a
			}
		}
		if pick < 0 {
			if !gone[0] && p.free(0, occ, gone) {
				pick = 0
			} else {
				return moves
			}
		}
		ms += 400 + int64(len(moves)%7)*137
		moves = append(moves, [2]int64{int64(pick), ms})
		for _, a := range p.cleared(pick, start, gone) {
			gone[a] = true
			for _, c := range p.paths[a] {
				occ[c] = -1
			}
		}
		if pick == 0 {
			return moves
		}
	}
}

func TestSpecialRunReplays(t *testing.T) {
	spec := LevelSpec{N: 14, MaxLen: 10, Seed: 4242, Special: true}
	p := generate(spec)
	for _, bombsFirst := range []bool{true, false} {
		moves := playOut(p, bombsFirst)
		if moves[len(moves)-1][0] != 0 {
			t.Fatalf("stuck before the pink arrow (bombs first %v)", bombsFirst)
		}
		r, err := replay(p, difficulties["Zen"], toMoves(moves), true)
		if err != nil {
			t.Fatal(err)
		}
		if bombsFirst && r.Cleared <= len(moves) {
			t.Fatalf("bombs cleared nothing extra: %d cleared in %d taps", r.Cleared, len(moves))
		}
	}
	moves := playOut(p, true)
	// The same taps on the plain cube hit arrows the blasts would have cleared.
	if _, err := replay(generate(LevelSpec{N: 14, MaxLen: 10, Seed: 4242}), difficulties["Zen"], toMoves(moves), true); err == nil {
		t.Fatal("special run verified on a plain cube")
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
	r1, _ := replay(p, difficulties["Zen"], toMoves(moves), true)
	r2, err := replay(p, difficulties["Zen"], toMoves(withUndo), true)
	if err != nil || r2.Cleared != r1.Cleared {
		t.Fatalf("undo of a bomb: %v, cleared %d want %d", err, r2.Cleared, r1.Cleared)
	}
}

func TestLockedArrowBumpsUntilItsKeyIsGone(t *testing.T) {
	p := generate(LevelSpec{N: 14, MaxLen: 10, Seed: 4242, Special: true})
	occ := make([]int, p.cube.size)
	for i := range occ {
		occ[i] = -1
	}
	gone := make([]bool, len(p.paths))
	for a, k := range p.keys {
		if k >= 0 {
			if p.free(a, occ, gone) {
				t.Fatal("locked arrow free with its key still there")
			}
			gone[k] = true
			if !p.free(a, occ, gone) {
				t.Fatal("locked arrow stuck on an empty board after its key left")
			}
			return
		}
	}
	t.Fatal("no locked arrow in the cube")
}

func TestSpecialsOnlyWhereAsked(t *testing.T) {
	h := newHarness(t)
	_, reg := h.call("POST", "/v1/register", "", map[string]string{"name": "Boom"})
	tok := reg["token"].(string)
	spec := LevelSpec{N: 14, MaxLen: 10, Seed: 4242, Special: true}
	_, st := h.call("POST", "/v1/runs", tok, map[string]any{"mode": "free", "n": 14, "maxLen": 10, "seed": 4242, "special": true, "difficulty": "Zen"})
	h.now = h.now.Add(5 * time.Minute)
	if code, out := h.call("POST", "/v1/runs/"+st["run"].(string)+"/finish", tok, map[string]any{"moves": playOut(generate(spec), true)}); code != 200 || out["status"] != "accepted" {
		t.Fatalf("special run: %d %v", code, out)
	}
	day := int(h.now.Unix() / 86400)
	h.now = h.now.Add(time.Minute)
	_, st = h.call("POST", "/v1/runs", tok, map[string]any{"mode": "daily", "day": day, "special": true, "difficulty": "Zen"})
	h.app.mu.Lock()
	special := h.app.runs[st["run"].(string)].Spec.Special
	h.app.mu.Unlock()
	if special {
		t.Fatal("daily run got special arrows")
	}
}

func TestEndlessRunCountsVerifiedCubes(t *testing.T) {
	h := newHarness(t)
	_, reg := h.call("POST", "/v1/register", "", map[string]string{"name": "Runner"})
	tok := reg["token"].(string)
	for _, daily := range []bool{false, true} {
		_, e := h.call("POST", "/v1/endless", tok, map[string]any{"daily": daily})
		id := e["id"].(string)
		seed, _ := strconv.ParseInt(e["seed"].(string), 10, 64)
		if daily && seed != dailyEndlessSeed(int(h.now.Unix()/86400)) {
			t.Fatal("daily endless got its own seed")
		}
		for k := 0; k < 3; k++ {
			h.now = h.now.Add(time.Minute)
			// As the app does: special arrows from 11×11 up (LevelSpec.special's default).
			_, st := h.call("POST", "/v1/runs", tok, map[string]any{"mode": "endless", "endless": id, "special": endlessSpec(seed, k).N >= 11, "difficulty": "Easy", "lives": 99})
			h.app.mu.Lock()
			run := h.app.runs[st["run"].(string)]
			h.app.mu.Unlock()
			if run.K != k || run.Lives != 5+k { // lives are capped at the mode's plus one a cube
				t.Fatalf("cube %d: k %d lives %d", k, run.K, run.Lives)
			}
			spec := endlessSpec(seed, k)
			spec.Special = spec.N >= 11
			h.now = h.now.Add(2 * time.Minute)
			if code, out := h.call("POST", "/v1/runs/"+run.ID+"/finish", tok, map[string]any{"moves": playOut(generate(spec), false)}); code != 200 || out["status"] != "accepted" {
				t.Fatalf("cube %d: %d %v", k, code, out)
			}
		}
	}
	_, all := h.call("GET", "/v1/endless", "", nil)
	if p := all["players"].([]any); len(p) != 1 || p[0].(map[string]any)["cubes"] != float64(3) {
		t.Fatalf("endless board: %v", all)
	}
	code, today := h.call("GET", "/v1/endless/"+strconv.Itoa(int(h.now.Unix()/86400)), "", nil)
	if p := today["players"].([]any); code != 200 || len(p) != 1 || p[0].(map[string]any)["cubes"] != float64(3) {
		t.Fatalf("daily endless board: %d %v", code, today)
	}
	// Someone else's endless run can't be played.
	_, reg2 := h.call("POST", "/v1/register", "", map[string]string{"name": "Other"})
	_, e := h.call("POST", "/v1/endless", tok, map[string]any{})
	if code, _ := h.call("POST", "/v1/runs", reg2["token"].(string), map[string]any{"mode": "endless", "endless": e["id"], "difficulty": "Easy"}); code != http.StatusNotFound {
		t.Fatalf("played another player's endless run: %d", code)
	}
}

func TestPublicProfileIsOptIn(t *testing.T) {
	h := newHarness(t)
	_, reg := h.call("POST", "/v1/register", "", map[string]string{"name": "Shown"})
	tok := reg["token"].(string)
	_, me := h.call("GET", "/v1/me", tok, nil)
	code := me["code"].(string)
	if c, _ := h.call("GET", "/v1/profile/"+code, "", nil); c != 404 {
		t.Fatalf("profile visible before opting in: %d", c)
	}
	if c, _ := h.call("POST", "/v1/me/profile", tok, map[string]any{"public": true, "badges": []string{"FIRST", "<script>"}}); c != 400 {
		t.Fatalf("bad badge accepted: %d", c)
	}
	h.call("POST", "/v1/me/profile", tok, map[string]any{"public": true, "badges": []string{"FIRST", "CHAIN"}, "streak": 4})
	c, p := h.call("GET", "/v1/profile/"+code, "", nil)
	if c != 200 || p["name"] != "Shown" || len(p["badges"].([]any)) != 2 || p["streak"] != float64(4) {
		t.Fatalf("profile: %d %v", c, p)
	}
	h.call("POST", "/v1/me/profile", tok, map[string]any{"public": false})
	if c, _ := h.call("GET", "/v1/profile/"+code, "", nil); c != 404 {
		t.Fatalf("profile still visible after opting out: %d", c)
	}
}

func TestStatsAndCORS(t *testing.T) {
	h := newHarness(t)
	h.call("POST", "/v1/register", "", map[string]string{"name": "One"})
	res, err := http.Get(h.srv.URL + "/v1/stats")
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.Header.Get("Access-Control-Allow-Origin") != "*" {
		t.Fatal("stats not readable from the website")
	}
	_, st := h.call("GET", "/v1/stats", "", nil)
	if st["registered"] != float64(1) || st["players"] != float64(0) {
		t.Fatalf("stats: %v", st)
	}
	req, _ := http.NewRequest("GET", h.srv.URL+"/v1/admin/stats", nil)
	res, _ = http.DefaultClient.Do(req)
	res.Body.Close()
	if res.Header.Get("Access-Control-Allow-Origin") != "" {
		t.Fatal("admin endpoints open to other sites")
	}
}
