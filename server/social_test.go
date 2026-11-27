package main

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestCleanBio(t *testing.T) {
	ok := map[string]string{
		"  Cube   enjoyer\n from Berlin ": "Cube enjoyer from Berlin",
		"":                                "",
		"Level 140 and counting":          "Level 140 and counting",
	}
	for in, want := range ok {
		if got, why := cleanBio(in); why != "" || got != want {
			t.Errorf("cleanBio(%q) = %q, %q; want %q", in, got, why, want)
		}
	}
	for _, bad := range []string{
		strings.Repeat("a", maxBio+1),
		"add me on discord", "see www.example.org", "my insta is cubefan", "call 0151 2345 678", "dm me @someone",
		"https://x.y", "white power", "fuck this",
	} {
		if _, why := cleanBio(bad); why == "" {
			t.Errorf("cleanBio(%q) should be refused", bad)
		}
	}
}

func TestBioReportsHideAndAdmin(t *testing.T) {
	h := newHarness(t)
	ann, annCode := h.player("Ann")
	if st, out := h.call("POST", "/v1/me/bio", ann, map[string]string{"bio": "I love cubes"}); st != 200 || out["bio"] != "I love cubes" {
		t.Fatalf("set bio: %d %v", st, out)
	}
	if st, _ := h.call("POST", "/v1/me/bio", ann, map[string]string{"bio": "discord me"}); st != 400 {
		t.Fatalf("contact bio accepted: %d", st)
	}
	var reporters []string
	for _, n := range []string{"Bob", "Cat", "Dan"} {
		tok, _ := h.player(n)
		reporters = append(reporters, tok)
	}
	h.call("POST", "/v1/report/"+annCode, reporters[0], nil)
	h.call("POST", "/v1/report/"+annCode, reporters[0], nil) // the same reporter twice counts once
	h.call("POST", "/v1/report/"+annCode, reporters[1], nil)
	if _, me := h.call("GET", "/v1/me", ann, nil); me["bioHidden"] != false {
		t.Fatalf("hidden after two reporters: %v", me)
	}
	h.call("POST", "/v1/report/"+annCode, reporters[2], nil)
	if _, me := h.call("GET", "/v1/me", ann, nil); me["bioHidden"] != true {
		t.Fatalf("not hidden after three reporters: %v", me)
	}
	adm := "admin-token-for-tests-123456"
	st, list := h.callList("GET", "/v1/admin/reports", adm)
	if st != 200 || len(list) != 1 || list[0]["reports"] != float64(3) {
		t.Fatalf("admin reports: %d %v", st, list)
	}
	h.call("POST", "/v1/admin/reports/"+annCode, adm, map[string]string{"action": "lock"})
	if _, me := h.call("GET", "/v1/me", ann, nil); me["bio"] != "" || me["bioLocked"] != true {
		t.Fatalf("after lock: %v", me)
	}
	if st, _ := h.call("POST", "/v1/me/bio", ann, map[string]string{"bio": "back again"}); st != 403 {
		t.Fatalf("locked player set a bio: %d", st)
	}
}

func TestNotesOnlyBetweenMutualFriends(t *testing.T) {
	h := newHarness(t)
	ann, annCode := h.player("Ann")
	bob, bobCode := h.player("Bob")
	h.call("POST", "/v1/friends/"+bobCode, ann, nil)
	if st, _ := h.call("POST", "/v1/friends/"+bobCode+"/note", ann, map[string]int{"msg": 1}); st != 403 {
		t.Fatalf("note before Bob added Ann back: %d", st)
	}
	h.call("POST", "/v1/friends/"+annCode, bob, nil)
	if st, out := h.call("POST", "/v1/friends/"+bobCode+"/note", ann, map[string]int{"msg": 1}); st != 200 {
		t.Fatalf("note: %d %v", st, out)
	}
	if st, _ := h.call("POST", "/v1/friends/"+bobCode+"/note", ann, map[string]int{"msg": quickMessages}); st != 400 {
		t.Fatalf("out-of-range message accepted: %d", st)
	}
	if st, _ := h.call("POST", "/v1/friends/"+bobCode+"/note", ann, map[string]string{"challenge": "2_13_11_21c5_0_1_2e.3gz_Ann"}); st != 200 {
		t.Fatalf("challenge: %d", st)
	}
	if st, _ := h.call("POST", "/v1/friends/"+bobCode+"/note", ann, map[string]string{"challenge": "<script>alert(1)</script>"}); st != 400 {
		t.Fatalf("bad challenge accepted: %d", st)
	}
	_, out := h.call("GET", "/v1/me/friends", bob, nil)
	inbox := out["inbox"].([]any)
	if len(inbox) != 2 || inbox[0].(map[string]any)["challenge"] == "" || inbox[1].(map[string]any)["name"] != "Ann" {
		t.Fatalf("inbox: %v", inbox)
	}
	id := inbox[0].(map[string]any)["id"].(string)
	h.call("DELETE", "/v1/me/inbox/"+id, bob, nil)
	if _, out := h.call("GET", "/v1/me/friends", bob, nil); len(out["inbox"].([]any)) != 1 {
		t.Fatalf("note not deleted: %v", out["inbox"])
	}
}

func TestFeedLeaguesAndInvites(t *testing.T) {
	h := newHarness(t)
	ann, annCode := h.player("Ann")
	bob, bobCode := h.player("Bob")
	h.call("POST", "/v1/friends/"+bobCode, ann, nil)
	if st, out := h.call("POST", "/v1/me/invited-by/"+annCode, bob, nil); st != 200 || out["name"] != "Ann" {
		t.Fatalf("invited-by: %d %v", st, out)
	}
	if st, _ := h.call("POST", "/v1/me/invited-by/"+annCode, bob, nil); st != 409 {
		t.Fatalf("second invited-by: %d", st)
	}

	// Bob's first verified run: Ann sees it in her feed, Bob joins a league, and the invite pays both.
	s := h.app.store
	s.mu.Lock()
	bobID := s.byCode(bobCode)
	bd := s.db.Devices[bobID]
	before := playerLevel(bd.XP)
	sc := Score{Label: "Level 3", Score: 900, XP: 250, Ms: 42_000}
	bd.credit(sc)
	s.afterRun(bobID, bd, sc, before, h.now)
	s.mu.Unlock()

	_, out := h.call("GET", "/v1/me/friends", ann, nil)
	feed := out["feed"].([]any)
	if len(feed) != 2 || feed[0].(map[string]any)["name"] != "Bob" {
		t.Fatalf("feed: %v", feed)
	}
	_, lg := h.call("GET", "/v1/me/league", bob, nil)
	if ps := lg["players"].([]any); len(ps) != 1 || ps[0].(map[string]any)["xp"] != float64(250) || ps[0].(map[string]any)["me"] != true {
		t.Fatalf("league: %v", lg)
	}
	for _, tok := range []string{ann, bob} {
		if _, r := h.call("POST", "/v1/me/rewards", tok, nil); r["rewards"] != float64(1) {
			t.Fatalf("rewards: %v", r)
		}
		if _, r := h.call("POST", "/v1/me/rewards", tok, nil); r["rewards"] != float64(0) {
			t.Fatalf("rewards claimed twice: %v", r)
		}
	}
	if _, me := h.call("GET", "/v1/me", ann, nil); me["invited"] != float64(1) {
		t.Fatalf("Ann's invited count: %v", me)
	}
}

func TestLeaguePromotionAndRelegation(t *testing.T) {
	st, _ := openStore(t.TempDir() + "/db.json")
	now := time.Unix(1_800_000_000, 0)
	ids := []string{}
	for i := 0; i < 12; i++ {
		id := strings.Repeat(string(rune('a'+i)), 32)
		st.db.Devices[id] = &Device{Name: id[:1], Code: strings.ToUpper(randID(4))}
		ids = append(ids, id)
	}
	for i, id := range ids { // a earns most, l least
		st.leagueEarn(id, st.db.Devices[id], 1000-i*10, now)
		st.db.Devices[id].League.Tier = 1 // everyone in Silver this week
	}
	next := now.Add(7 * 24 * time.Hour)
	for _, id := range ids {
		st.leagueEarn(id, st.db.Devices[id], 5, next)
	}
	want := map[int]string{0: "up", 2: "up", 3: "stay", 8: "stay", 9: "down", 11: "down"}
	for i, move := range want {
		d := st.db.Devices[ids[i]]
		if d.LastLeague == nil || d.LastLeague.Move != move || d.LastLeague.Rank != i+1 {
			t.Errorf("player %d: %+v, want %s", i, d.LastLeague, move)
		}
	}
	if st.db.Devices[ids[0]].League.Tier != 2 || st.db.Devices[ids[11]].League.Tier != 0 {
		t.Errorf("tiers after the week: %d, %d", st.db.Devices[ids[0]].League.Tier, st.db.Devices[ids[11]].League.Tier)
	}
}

// callList is call for endpoints that answer with a JSON array.
func (h *harness) callList(method, path, token string) (int, []map[string]any) {
	req, _ := http.NewRequest(method, h.srv.URL+path, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		h.t.Fatal(err)
	}
	defer res.Body.Close()
	var out []map[string]any
	json.NewDecoder(res.Body).Decode(&out)
	return res.StatusCode, out
}
