package main

import (
	"strconv"
	"testing"
	"time"
)

// A twist is a move: it turns a face's arrowheads for the rest of the replay, and the mode allows three.
func TestTwistReplays(t *testing.T) {
	spec := LevelSpec{N: 10, MaxLen: 8, Seed: 99}
	tw := difficulties["Twist"]
	// An arrow that's stuck as the cube starts but free once its face is twisted: the server must agree.
	base := generate(spec)
	occ := occupancy(base)
	gone := make([]bool, len(base.paths))
	for f := 0; f < 6; f++ {
		q := generate(spec)
		q.turn(f)
		for a := range q.paths {
			if a == 0 || base.free(a, occ, gone) || !q.free(a, occ, gone) {
				continue
			}
			r, _ := replay(generate(spec), tw, toMoves([][2]int64{{int64(twistTap - f), 100}, {int64(a), 900}}), false)
			if r.Cleared == 0 || r.Mistakes != 0 {
				t.Fatalf("twisted face %d, arrow %d: cleared %d, mistakes %d", f, a, r.Cleared, r.Mistakes)
			}
			r, _ = replay(generate(spec), tw, toMoves([][2]int64{{int64(a), 900}}), false)
			if r.Mistakes != 1 {
				t.Fatalf("untwisted arrow %d didn't bump", a)
			}
			if _, err := replay(generate(spec), difficulties["Normal"], toMoves([][2]int64{{int64(twistTap - f), 100}}), false); err == nil || err.Error() == "pink arrow never freed" {
				t.Fatalf("twisted outside Twist mode: %v", err)
			}
			return
		}
	}
	t.Fatal("no twist frees a stuck arrow on this cube")
}

func TestWorldCubeIsShared(t *testing.T) {
	h := newHarness(t)
	_, r1 := h.call("POST", "/v1/register", "", map[string]string{"name": "Ann"})
	_, r2 := h.call("POST", "/v1/register", "", map[string]string{"name": "Bob"})
	ann, bob := r1["token"].(string), r2["token"].(string)
	_, st := h.call("GET", "/v1/world", "", nil)
	id := int(st["id"].(float64))
	seed, _ := strconv.ParseInt(st["seed"].(string), 10, 64)
	p := generate(worldSpec(seed))
	if int(st["arrows"].(float64)) != len(p.paths) || st["left"] != st["arrows"] {
		t.Fatalf("fresh world: %v", st)
	}
	// Blocked: the pink arrow is buried.
	if _, out := h.call("POST", "/v1/world/tap", ann, map[string]any{"id": id, "arrow": 0}); out["ok"] != false || out["why"] != "blocked" {
		t.Fatalf("pink tap: %v", out)
	}
	// Ann and Bob take turns clearing it, greedily, until the pink arrow goes.
	moves := playOut(p, false)
	var won map[string]any
	for i, m := range moves {
		tok := ann
		if i%2 == 1 {
			tok = bob
		}
		h.now = h.now.Add(time.Second)
		h.app.limits = newLimiter() // the tap rate limit is real time; this test isn't
		_, out := h.call("POST", "/v1/world/tap", tok, map[string]any{"id": id, "arrow": m[0]})
		if out["ok"] != true {
			// Someone's bomb may already have taken it; that's fine.
			if out["why"] == "gone" {
				continue
			}
			t.Fatalf("tap %d (arrow %d): %v", i, m[0], out)
		}
		if out["won"] == true {
			won = out
			break
		}
	}
	if won == nil {
		t.Fatal("the pink arrow never went")
	}
	h.app.store.mu.Lock()
	cubes, wins := 0, 0
	for _, d := range h.app.store.db.Devices {
		cubes += d.WorldCubes
		wins += d.WorldWins
	}
	h.app.store.mu.Unlock()
	if cubes != 2 || wins != 1 {
		t.Fatalf("after the win: %d players credited with the cube, %d wins; want 2 and 1", cubes, wins)
	}
	_, st = h.call("GET", "/v1/world", "", nil)
	if int(st["id"].(float64)) != id+1 || st["last"].(map[string]any)["players"] != float64(2) {
		t.Fatalf("after the win: %v", st)
	}
	// A new store counts World Cubes as they're freed, so the startup backfill must not count them again.
	h.app.store.mu.Lock()
	counted := h.app.store.db.WorldCounted
	h.app.store.mu.Unlock()
	if !counted {
		t.Fatal("a fresh store would credit past winners twice")
	}
	// Taps on the old cube are turned away with the new id.
	if _, out := h.call("POST", "/v1/world/tap", ann, map[string]any{"id": id, "arrow": 1}); out["ok"] != false || out["id"] != float64(id+1) {
		t.Fatalf("old cube tap: %v", out)
	}
	// The world survives a restart: same cube, same removals.
	h.app.store.mu.Lock()
	h.app.world = nil
	h.app.store.mu.Unlock()
	_, again := h.call("GET", "/v1/world", "", nil)
	if again["id"] != st["id"] || again["left"] != st["left"] {
		t.Fatalf("rebuilt world differs: %v vs %v", again, st)
	}
}
