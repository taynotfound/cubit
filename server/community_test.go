package main

import (
	"testing"
	"time"
)

func TestCommunityCubes(t *testing.T) {
	h := newHarness(t)
	ann, annCode := h.player("Ann")
	bob, bobCode := h.player("Bob")
	cube := map[string]any{"title": "Twisty", "n": 7, "maxLen": 5, "seed": 4242, "special": false}
	if st, _ := h.call("POST", "/v1/cubes", ann, cube); st != 403 {
		t.Fatalf("publish before any run: %d", st)
	}
	h.earn(annCode, 100)
	h.earn(bobCode, 100)
	if st, _ := h.call("POST", "/v1/cubes", ann, map[string]any{"title": "fuck cube", "n": 7, "maxLen": 5, "seed": 1}); st != 400 {
		t.Fatalf("rude title: %d", st)
	}
	if st, _ := h.call("POST", "/v1/cubes", ann, map[string]any{"title": "Huge", "n": 30, "maxLen": 5, "seed": 1}); st != 400 {
		t.Fatalf("too big: %d", st)
	}
	st, out := h.call("POST", "/v1/cubes", ann, cube)
	if st != 200 || out["arrows"].(float64) < 1 || out["par"].(float64) < 1 || out["mine"] != true {
		t.Fatalf("publish: %d %v", st, out)
	}
	id := out["id"].(string)
	if st, _ := h.call("POST", "/v1/cubes", bob, cube); st != 409 {
		t.Fatalf("duplicate cube: %d", st)
	}
	if st, _ := h.call("POST", "/v1/cubes/"+id+"/like", ann, nil); st != 400 {
		t.Fatalf("liked own cube: %d", st)
	}
	h.call("POST", "/v1/cubes/"+id+"/like", bob, nil)
	h.call("POST", "/v1/cubes/"+id+"/like", bob, nil) // twice counts once
	_, list := h.call("GET", "/v1/cubes?sort=top", bob, nil)
	cs := list["cubes"].([]any)
	if len(cs) != 1 || cs[0].(map[string]any)["likes"] != float64(1) || cs[0].(map[string]any)["liked"] != true || cs[0].(map[string]any)["author"] != "Ann" {
		t.Fatalf("list: %v", list)
	}

	// Bob plays it as a free run: the run is labelled with the cube, and the win counts once.
	for i := 0; i < 2; i++ {
		st, run := h.call("POST", "/v1/runs", bob, map[string]any{"mode": "free", "n": 7, "maxLen": 5, "seed": 4242, "difficulty": "Normal"})
		if st != 200 {
			t.Fatalf("start: %d %v", st, run)
		}
		moves := solve(generate(LevelSpec{N: 7, MaxLen: 5, Seed: 4242}), human)
		h.now = h.now.Add(time.Duration(moves[len(moves)-1][1]+800) * time.Millisecond)
		if st, fin := h.call("POST", "/v1/runs/"+run["run"].(string)+"/finish", bob, map[string]any{"moves": moves}); st != 200 {
			t.Fatalf("finish: %d %v", st, fin)
		}
	}
	_, list = h.call("GET", "/v1/cubes?sort=new", bob, nil)
	if c := list["cubes"].([]any)[0].(map[string]any); c["wins"] != float64(1) || c["won"] != true {
		t.Fatalf("wins: %v", c)
	}
	h.app.store.mu.Lock()
	label := h.app.store.db.Scores[0].Label
	h.app.store.mu.Unlock()
	if label != "Community · Twisty" {
		t.Fatalf("label: %q", label)
	}

	// Three reports hide it; an admin keeps it, and it comes back for good.
	for _, n := range []string{"Cat", "Dan", "Eve"} {
		tok, _ := h.player(n)
		h.call("POST", "/v1/cubes/"+id+"/report", tok, nil)
	}
	if _, list := h.call("GET", "/v1/cubes", "", nil); list["total"] != float64(0) {
		t.Fatalf("reported cube still listed: %v", list)
	}
	adm := "admin-token-for-tests-123456"
	if st, l := h.callList("GET", "/v1/admin/cubes", adm); st != 200 || len(l) != 1 {
		t.Fatalf("admin list: %d %v", st, l)
	}
	h.call("POST", "/v1/admin/cubes/"+id, adm, map[string]string{"action": "keep"})
	if _, list := h.call("GET", "/v1/cubes", "", nil); list["total"] != float64(1) {
		t.Fatalf("kept cube not back: %v", list)
	}
	if st, _ := h.call("DELETE", "/v1/cubes/"+id, bob, nil); st != 403 {
		t.Fatalf("non-author removed: %d", st)
	}
	h.call("DELETE", "/v1/cubes/"+id, ann, nil)
	if _, list := h.call("GET", "/v1/cubes", "", nil); list["total"] != float64(0) {
		t.Fatalf("removed cube listed: %v", list)
	}
}
