package main

import (
	"os"
	"testing"
)

func TestOffensiveNames(t *testing.T) {
	for _, n := range []string{"nigga", "NIGGA", "N1gga", "n i g g a", "n.i.g.g.a", "niiiggaaa", "nigger", "xXniggerXx", "niga", "Niga 420",
		"Hitler", "H1tler", "adolf_hitler", "Sieg Heil", "fuck you", "FuuuCK", "faggot", "nazi", "the nazis", "KKK", "Neger", "nіggа" /* Cyrillic і, а */} {
		if !offensive(n) {
			t.Errorf("%q should be blocked", n)
		}
		if _, ok := cleanName(n); ok {
			t.Errorf("cleanName accepted %q", n)
		}
	}
	// Names on the board today, and innocent ones that contain a blocked word.
	for _, n := range []string{"David", "Master", "Sava", "BEF", "yann", "Owl", "SolarRay", "imnotabot", "akaremii26", "Player",
		"Nigel", "Niger", "Nigeria", "Night Owl", "Knight", "Nazim", "Nazia", "Scunthorpe", "grape", "Spice", "Schwarzenegger", "Hancock",
		"Dick", "Tay März", "Kike", "raccoon", "therapist", "Montenegro", "snigel", "Ginger", "Regen", "Enrique"} {
		if offensive(n) {
			t.Errorf("%q should be allowed", n)
		}
	}
}

func TestOffensiveNameNeverReachesBoard(t *testing.T) {
	h := newHarness(t)
	// A new player who picks one registers as "Player".
	code, reg := h.call("POST", "/v1/register", "", map[string]string{"name": "n1gga"})
	if code != 200 || reg["name"] != "Player" {
		t.Fatalf("register: %d %v", code, reg)
	}
	// An existing player who renames to one keeps the old name.
	_, reg = h.call("POST", "/v1/register", "", map[string]string{"name": "Tay"})
	tok := reg["token"].(string)
	if code, _ := h.call("POST", "/v1/runs", tok, map[string]any{"mode": "campaign", "level": 1, "difficulty": "Zen", "name": "nigga"}); code != 200 {
		t.Fatalf("start run: %d", code)
	}
	if _, me := h.call("GET", "/v1/me", tok, nil); me["name"] != "Tay" {
		t.Fatalf("rename to a slur went through: %v", me["name"])
	}
}

func TestOffensiveNamesClearedOnStart(t *testing.T) {
	file := t.TempDir() + "/db.json"
	os.WriteFile(file, []byte(`{"devices":{"a":{"name":"nigga","code":"AAAAAAAA","xp":5},"b":{"name":"Owl","code":"BBBBBBBB","xp":5}},"scores":[]}`), 0o600)
	st, _ := openStore(file)
	if a, b := st.db.Devices["a"].Name, st.db.Devices["b"].Name; a != "Player" || b != "Owl" {
		t.Fatalf("after start: %q, %q", a, b)
	}
	select {
	case <-st.dirty:
	default:
		t.Fatal("renamed players not queued for saving")
	}
}

func TestPlayerCounts(t *testing.T) {
	h := newHarness(t)
	h.app.store.db.Devices["a"] = &Device{Name: "A1", XP: 10, Created: h.now.Unix()}
	h.app.store.db.Devices["b"] = &Device{Name: "B2", XP: 20, Created: h.now.Unix() - 30*86400}
	h.app.store.db.Devices["c"] = &Device{Name: "C3", Created: h.now.Unix()} // registered, no verified run yet
	h.app.store.db.Devices["d"] = &Device{Name: "D4", XP: 99, Banned: true}
	if _, board := h.call("GET", "/v1/leaderboard", "", nil); board["total"] != 2.0 {
		t.Fatalf("public total: %v", board["total"])
	}
	_, st := h.call("GET", "/v1/admin/stats", "admin-token-for-tests-123456", nil)
	if st["registered"] != 4.0 || st["ranked"] != 2.0 || st["banned"] != 1.0 || st["newLast7Days"] != 2.0 {
		t.Fatalf("admin stats: %v", st)
	}
	if code, _ := h.call("GET", "/v1/admin/stats", "", nil); code != 401 {
		t.Fatalf("stats without the admin token: %d", code)
	}
}
