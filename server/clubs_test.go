package main

import (
	"testing"
	"time"
)

// earn gives a player verified XP this week, as a run would.
func (h *harness) earn(code string, xp int) {
	s := h.app.store
	s.mu.Lock()
	defer s.mu.Unlock()
	id := s.byCode(code)
	d := s.db.Devices[id]
	d.XP += xp
	s.leagueEarn(id, d, xp, h.now)
}

func TestClubs(t *testing.T) {
	h := newHarness(t)
	ann, annCode := h.player("Ann")
	bob, bobCode := h.player("Bob")
	cat, _ := h.player("Cat")
	if st, _ := h.call("POST", "/v1/clubs", ann, map[string]string{"name": "Cube Crew"}); st != 403 {
		t.Fatalf("club before any run: %d", st)
	}
	h.earn(annCode, 300)
	h.earn(bobCode, 120)
	if st, _ := h.call("POST", "/v1/clubs", ann, map[string]string{"name": "fuck you"}); st != 400 {
		t.Fatalf("rude club name accepted: %d", st)
	}
	st, out := h.call("POST", "/v1/clubs", ann, map[string]string{"name": "Cube Crew"})
	if st != 200 || len(out["code"].(string)) != 6 {
		t.Fatalf("create: %d %v", st, out)
	}
	club := out["code"].(string)
	if st, _ := h.call("POST", "/v1/clubs", bob, map[string]string{"name": "cube crew"}); st != 409 {
		t.Fatalf("duplicate name: %d", st)
	}
	if st, _ := h.call("POST", "/v1/clubs/"+club+"/join", bob, nil); st != 200 {
		t.Fatalf("join: %d", st)
	}
	_, me := h.call("GET", "/v1/me/club", bob, nil)
	c := me["club"].(map[string]any)
	if c["xp"] != float64(420) || c["rank"] != float64(1) || len(c["members"].([]any)) != 2 || c["owner"] != false {
		t.Fatalf("club: %v", me)
	}
	if first := c["members"].([]any)[0].(map[string]any); first["name"] != "Ann" || first["owner"] != true {
		t.Fatalf("members not ranked by XP: %v", c["members"])
	}
	// Only the owner can remove members; Bob can't kick Ann.
	if st, _ := h.call("DELETE", "/v1/me/club/members/"+annCode, bob, nil); st != 403 {
		t.Fatalf("member kicked the owner: %d", st)
	}
	if st, _ := h.call("POST", "/v1/me/club/leave", ann, nil); st != 200 {
		t.Fatalf("leave: %d", st)
	}
	_, me = h.call("GET", "/v1/me/club", bob, nil)
	if me["club"].(map[string]any)["owner"] != true {
		t.Fatalf("Bob didn't become owner: %v", me)
	}
	if st, _ := h.call("POST", "/v1/clubs/"+club+"/join", ann, nil); st != 429 {
		t.Fatalf("rejoin inside the cooldown: %d", st)
	}
	h.now = h.now.Add(11 * time.Minute)
	h.call("POST", "/v1/clubs/"+club+"/join", ann, nil)
	if st, _ := h.call("DELETE", "/v1/me/club/members/"+annCode, bob, nil); st != 200 {
		t.Fatalf("owner kick: %d", st)
	}
	if st, _ := h.call("POST", "/v1/clubs/NOPE99/join", cat, nil); st != 404 {
		t.Fatalf("bad code: %d", st)
	}
	adm := "admin-token-for-tests-123456"
	if st, list := h.callList("GET", "/v1/admin/clubs", adm); st != 200 || len(list) != 1 {
		t.Fatalf("admin clubs: %d %v", st, list)
	}
	h.call("POST", "/v1/admin/clubs/"+club, adm, map[string]string{"action": "remove"})
	if _, me := h.call("GET", "/v1/me/club", bob, nil); me["club"] != nil || me["of"] != float64(0) {
		t.Fatalf("club not removed: %v", me)
	}
}
