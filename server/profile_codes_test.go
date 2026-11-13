package main

import "testing"

// Friend codes show on public boards only for players who made their profile public.
func TestBoardsShowCodesOnlyForPublicProfiles(t *testing.T) {
	h := newHarness(t)
	_, reg := h.call("POST", "/v1/register", "", map[string]string{"name": "Pub"})
	tok := reg["token"].(string)
	h.app.store.mu.Lock()
	for _, d := range h.app.store.db.Devices {
		d.XP = 100
	}
	h.app.store.mu.Unlock()
	code := func() any {
		_, b := h.call("GET", "/v1/leaderboard", "", nil)
		return b["players"].([]any)[0].(map[string]any)["code"]
	}
	if c := code(); c != nil {
		t.Fatalf("private player's code on the board: %v", c)
	}
	h.call("POST", "/v1/me/profile", tok, map[string]any{"public": true})
	_, me := h.call("GET", "/v1/me", tok, nil)
	if c := code(); c != me["code"] {
		t.Fatalf("public player's code: %v, want %v", c, me["code"])
	}
}
