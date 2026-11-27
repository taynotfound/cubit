package main

import (
	"net/http"
	"strings"
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
	// Until Ann playtests it, only she sees it, and nobody's win counts.
	if out["state"] != "testing" {
		t.Fatalf("new cube not in testing: %v", out)
	}
	if _, list := h.call("GET", "/v1/cubes", bob, nil); list["total"] != float64(0) {
		t.Fatalf("untested cube listed: %v", list)
	}
	if _, list := h.call("GET", "/v1/cubes?mine=1", ann, nil); list["total"] != float64(1) {
		t.Fatalf("author can't see her cube: %v", list)
	}
	if label := playCube(t, h, ann, 7, 5, 4242); label != "Playtest · Twisty" {
		t.Fatalf("playtest label: %q", label)
	}
	_, mine := h.call("GET", "/v1/cubes?mine=1", ann, nil)
	if c := mine["cubes"].([]any)[0].(map[string]any); c["state"] != nil || c["authorMs"] == nil || c["wins"] != float64(0) {
		t.Fatalf("after playtest: %v", c)
	}
	h.call("POST", "/v1/cubes/"+id+"/like", bob, nil)
	h.call("POST", "/v1/cubes/"+id+"/like", bob, nil) // twice counts once
	_, list := h.call("GET", "/v1/cubes?sort=top", bob, nil)
	cs := list["cubes"].([]any)
	if len(cs) != 1 || cs[0].(map[string]any)["likes"] != float64(1) || cs[0].(map[string]any)["liked"] != true || cs[0].(map[string]any)["author"] != "Ann" {
		t.Fatalf("list: %v", list)
	}

	// Bob can't rate it before clearing it. He plays it as a free run: the run is labelled with the cube, and the
	// win counts once. Then he says it was hard.
	if st, _ := h.call("POST", "/v1/cubes/"+id+"/rate", bob, map[string]int{"rating": 3}); st != 403 {
		t.Fatalf("rated before clearing: %d", st)
	}
	for i := 0; i < 2; i++ {
		if label := playCube(t, h, bob, 7, 5, 4242); label != "Community · Twisty" {
			t.Fatalf("label: %q", label)
		}
	}
	if st, _ := h.call("POST", "/v1/cubes/"+id+"/rate", bob, map[string]int{"rating": 3}); st != 200 {
		t.Fatalf("rate: %d", st)
	}
	_, list = h.call("GET", "/v1/cubes?sort=new", bob, nil)
	if c := list["cubes"].([]any)[0].(map[string]any); c["wins"] != float64(1) || c["won"] != true || c["rating"] != float64(3) || c["votes"] != float64(1) || c["myRating"] != float64(3) {
		t.Fatalf("wins: %v", c)
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
	if st, l := h.callList("GET", "/v1/admin/cubes?view=reported", adm); st != 200 || len(l) != 1 {
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

// playCube clears a free cube in a verified run and returns the run's label on the board.
func playCube(t *testing.T, h *harness, tok string, n, maxLen int, seed int64) string {
	t.Helper()
	st, run := h.call("POST", "/v1/runs", tok, map[string]any{"mode": "free", "n": n, "maxLen": maxLen, "seed": seed, "difficulty": "Normal"})
	if st != 200 {
		t.Fatalf("start: %d %v", st, run)
	}
	moves := solve(generate(LevelSpec{N: n, MaxLen: maxLen, Seed: seed}), human)
	h.now = h.now.Add(time.Duration(moves[len(moves)-1][1]+800) * time.Millisecond)
	if st, fin := h.call("POST", "/v1/runs/"+run["run"].(string)+"/finish", tok, map[string]any{"moves": moves}); st != 200 || fin["status"] != "accepted" {
		t.Fatalf("finish: %d %v", st, fin)
	}
	h.app.store.mu.Lock()
	defer h.app.store.mu.Unlock()
	d := h.app.store.db.Devices[strings.SplitN(tok, ".", 2)[0]]
	for i := len(d.Recent) - 1; i >= 0; i-- {
		if d.Recent[i].Kind == "run" {
			return d.Recent[i].Label
		}
	}
	return ""
}

func TestCubeReview(t *testing.T) {
	h := newHarness(t)
	ann, annCode := h.player("Ann")
	h.earn(annCode, 100)
	adm := "admin-token-for-tests-123456"
	if st, out := h.call("POST", "/v1/admin/settings", adm, map[string]bool{"reviewCubes": true}); st != 200 || out["reviewCubes"] != true {
		t.Fatalf("settings: %d %v", st, out)
	}
	_, a := h.call("POST", "/v1/cubes", ann, map[string]any{"title": "First", "n": 6, "maxLen": 4, "seed": 11})
	_, b := h.call("POST", "/v1/cubes", ann, map[string]any{"title": "Second", "n": 6, "maxLen": 4, "seed": 12})
	playCube(t, h, ann, 6, 4, 11)
	playCube(t, h, ann, 6, 4, 12)
	if _, l := h.callList("GET", "/v1/admin/cubes?view=review", adm); len(l) != 2 {
		t.Fatalf("review queue: %v", l)
	}
	if _, list := h.call("GET", "/v1/cubes", "", nil); list["total"] != float64(0) {
		t.Fatalf("unreviewed cube listed: %v", list)
	}
	h.call("POST", "/v1/admin/cubes/"+a["id"].(string), adm, map[string]string{"action": "approve"})
	h.call("POST", "/v1/admin/cubes/"+a["id"].(string), adm, map[string]string{"action": "feature"})
	if _, list := h.call("GET", "/v1/cubes", "", nil); list["total"] != float64(1) || list["cubes"].([]any)[0].(map[string]any)["featured"] != true {
		t.Fatalf("approved cube: %v", list)
	}
	// Review off: the one still waiting goes live too.
	h.call("POST", "/v1/admin/settings", adm, map[string]bool{"reviewCubes": false})
	if _, list := h.call("GET", "/v1/cubes", "", nil); list["total"] != float64(2) {
		t.Fatalf("review off: %v", list)
	}
	_ = b
	// A cube never playtested is dropped after a week.
	_, c := h.call("POST", "/v1/cubes", ann, map[string]any{"title": "Third", "n": 6, "maxLen": 4, "seed": 13})
	h.app.store.mu.Lock()
	h.app.store.pruneCubes(h.now.Unix() + 8*86400)
	_, kept := h.app.store.db.Cubes[c["id"].(string)]
	h.app.store.mu.Unlock()
	if kept {
		t.Fatal("untested cube kept after a week")
	}
	if st, s := h.call("GET", "/v1/admin/stats", adm, nil); st != 200 || s["cubesLive"] != float64(2) {
		t.Fatalf("stats: %v", s)
	}
	if st := h.page("/admin"); st != 200 {
		t.Fatalf("admin page: %d", st)
	}
}

func (h *harness) page(path string) int {
	res, err := http.Get(h.srv.URL + path)
	if err != nil {
		h.t.Fatal(err)
	}
	res.Body.Close()
	return res.StatusCode
}
