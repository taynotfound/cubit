package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

type harness struct {
	t   *testing.T
	app *App
	srv *httptest.Server
	now time.Time
}

func newHarness(t *testing.T) *harness {
	st, _ := openStore(t.TempDir() + "/db.json")
	h := &harness{t: t, now: time.Unix(1_800_000_000, 0)}
	h.app = &App{store: st, limits: newLimiter(), adminToken: "admin-token-for-tests-123456", allowDirect: true, now: func() time.Time { return h.now }, runs: map[string]*Run{}}
	h.srv = httptest.NewServer(h.app.routes())
	t.Cleanup(h.srv.Close)
	return h
}

func (h *harness) call(method, path, token string, body any) (int, map[string]any) {
	var buf bytes.Buffer
	if body != nil {
		json.NewEncoder(&buf).Encode(body)
	}
	req, _ := http.NewRequest(method, h.srv.URL+path, &buf)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		h.t.Fatal(err)
	}
	defer res.Body.Close()
	var out map[string]any
	json.NewDecoder(res.Body).Decode(&out)
	return res.StatusCode, out
}

// solve clears every other arrow greedily, then the pink one, with human-like irregular gaps.
func solve(p *Puzzle, gap func(i int) int64) [][2]int64 {
	occ := make([]int, p.cube.size)
	for i := range occ {
		occ[i] = -1
	}
	for i, path := range p.paths {
		for _, c := range path {
			occ[c] = i
		}
	}
	free := func(a int) bool {
		path := p.paths[a]
		for _, c := range p.cube.exit(path[len(path)-1], p.dirs[a], p.lap) {
			if occ[c] >= 0 && occ[c] != a {
				return false
			}
		}
		return true
	}
	gone := make([]bool, len(p.paths))
	var moves [][2]int64
	t := int64(0)
	for left := len(p.paths) - 1; left > 0; left-- { // every other arrow first: pink leaves last
		for a := len(p.paths) - 1; a >= 1; a-- {
			if !gone[a] && free(a) {
				gone[a] = true
				for _, c := range p.paths[a] {
					occ[c] = -1
				}
				t += gap(len(moves))
				moves = append(moves, [2]int64{int64(a), t})
				break
			}
		}
	}
	t += gap(len(moves))
	return append(moves, [2]int64{0, t})
}

func human(i int) int64 { return int64(600 + (i*373)%900) }

func TestRankedRunEndToEnd(t *testing.T) {
	h := newHarness(t)
	code, reg := h.call("POST", "/v1/register", "", map[string]string{"name": "  Tay <script> "})
	if code != 200 || reg["name"] != "Tay script" {
		t.Fatalf("register: %d %v", code, reg)
	}
	tok := reg["token"].(string)

	code, _ = h.call("POST", "/v1/runs", "", map[string]any{"mode": "campaign", "level": 1, "difficulty": "Normal"})
	if code != 401 {
		t.Fatalf("unauthenticated start allowed: %d", code)
	}
	code, _ = h.call("POST", "/v1/runs", tok+"x", map[string]any{"mode": "campaign", "level": 1, "difficulty": "Normal"})
	if code != 401 {
		t.Fatalf("wrong secret accepted: %d", code)
	}

	code, st := h.call("POST", "/v1/runs", tok, map[string]any{"mode": "campaign", "level": 1, "difficulty": "Normal"})
	if code != 200 {
		t.Fatalf("start: %d %v", code, st)
	}
	run := st["run"].(string)
	moves := solve(generate(specForLevel(1)), human)
	h.now = h.now.Add(time.Duration(moves[len(moves)-1][1]+800) * time.Millisecond)

	code, fin := h.call("POST", "/v1/runs/"+run+"/finish", tok, map[string]any{"moves": moves})
	if code != 200 || fin["status"] != "accepted" || fin["rank"].(float64) != 1 {
		t.Fatalf("finish: %d %v", code, fin)
	}
	code, _ = h.call("POST", "/v1/runs/"+run+"/finish", tok, map[string]any{"moves": moves})
	if code != 404 {
		t.Fatalf("replayed run accepted: %d", code)
	}
	// A weaker run than the player's best still reports where they stand, not "unranked".
	code, st = h.call("POST", "/v1/runs", tok, map[string]any{"mode": "campaign", "level": 1, "difficulty": "Zen"})
	if code != 200 {
		t.Fatalf("second start: %d %v", code, st)
	}
	h.now = h.now.Add(time.Duration(moves[len(moves)-1][1]+800) * time.Millisecond)
	code, fin2 := h.call("POST", "/v1/runs/"+st["run"].(string)+"/finish", tok, map[string]any{"moves": moves})
	if code != 200 || fin2["status"] != "accepted" || fin2["score"].(float64) >= fin["score"].(float64) || fin2["rank"].(float64) != 1 {
		t.Fatalf("second finish: %d %v", code, fin2)
	}
	_, board := h.call("GET", "/v1/leaderboard", "", nil)
	scores := board["scores"].([]any)
	if len(scores) != 1 || scores[0].(map[string]any)["name"] != "Tay script" {
		t.Fatalf("board: %v", board)
	}
	if _, leaked := scores[0].(map[string]any)["device"]; leaked {
		t.Fatal("device id leaked on public board")
	}
}

func TestForgedAndBotRuns(t *testing.T) {
	h := newHarness(t)
	_, reg := h.call("POST", "/v1/register", "", map[string]string{"name": "Bot"})
	tok := reg["token"].(string)
	start := func() string {
		_, st := h.call("POST", "/v1/runs", tok, map[string]any{"mode": "free", "n": 6, "maxLen": 6, "seed": 42, "difficulty": "Easy"})
		return st["run"].(string)
	}
	p := generate(LevelSpec{N: 6, MaxLen: 6, Seed: 42})
	cases := []struct {
		name  string
		moves [][2]int64
	}{
		{"pink only (blocked)", [][2]int64{{0, 900}}},
		{"unknown arrow", [][2]int64{{9999, 900}}},
		{"clock backwards", [][2]int64{{3, 900}, {0, 100}}},
	}
	for _, c := range cases {
		h.now = h.now.Add(time.Minute)
		run := start()
		h.now = h.now.Add(time.Minute)
		if code, out := h.call("POST", "/v1/runs/"+run+"/finish", tok, map[string]any{"moves": c.moves}); code != 422 {
			t.Errorf("%s: got %d %v, want 422", c.name, code, out)
		}
	}

	run := start()
	bot := solve(p, func(int) int64 { return 40 })
	h.now = h.now.Add(10 * time.Second)
	code, out := h.call("POST", "/v1/runs/"+run+"/finish", tok, map[string]any{"moves": bot})
	if code != 200 || out["status"] != "review" {
		t.Fatalf("bot run not held: %d %v", code, out)
	}
	_, board := h.call("GET", "/v1/leaderboard", "", nil)
	if len(board["scores"].([]any)) != 0 {
		t.Fatal("held run reached the board")
	}
	code, _ = h.call("GET", "/v1/admin/held", "wrong", nil)
	if code != 401 {
		t.Fatalf("admin without token: %d", code)
	}
	req, _ := http.NewRequest("GET", h.srv.URL+"/v1/admin/held", nil)
	req.Header.Set("Authorization", "Bearer "+h.app.adminToken)
	res, _ := http.DefaultClient.Do(req)
	var held []map[string]any
	json.NewDecoder(res.Body).Decode(&held)
	if len(held) != 1 {
		t.Fatalf("held list: %v", held)
	}
	code, _ = h.call("POST", fmt.Sprintf("/v1/admin/held/%s", held[0]["run"]), h.app.adminToken, map[string]string{"action": "approve"})
	_, board = h.call("GET", "/v1/leaderboard", "", nil)
	if code != 200 || len(board["scores"].([]any)) != 1 {
		t.Fatalf("approve: %d %v", code, board)
	}
}

func TestDirectAccessBlocked(t *testing.T) {
	a := &App{serverIP: "45.131.65.248"}
	cases := map[string]bool{"203.0.113.9:5000": false, "172.18.0.1:5000": true, "45.131.65.248:5000": true}
	for remote, want := range cases {
		r := httptest.NewRequest("GET", "/", nil)
		r.RemoteAddr = remote
		r.Header.Set("X-Real-IP", "198.51.100.7")
		ip, via := a.clientIP(r)
		if via != want || (want && ip != "198.51.100.7") || (!want && ip != "203.0.113.9") {
			t.Errorf("%s: ip=%s via=%v", remote, ip, via)
		}
	}
}

func TestPowerupsReplay(t *testing.T) {
	p := generate(LevelSpec{N: 6, MaxLen: 6, Seed: 42})
	sol := solve(p, human)
	blocker := Move{0, 100} // the pink arrow before anything is cleared: a bump
	toMoves := func(m [][2]int64) []Move {
		out := []Move{}
		for _, x := range m {
			out = append(out, Move{int(x[0]), x[1]})
		}
		return out
	}
	expert := difficulties["Expert"]

	// Expert has one life: a bump ends the run, unless a shield absorbs it.
	if _, err := replay(p, expert, append([]Move{blocker}, toMoves(sol)...), false); err == nil {
		t.Fatal("bump on Expert should be out of lives")
	}
	r, err := replay(p, expert, append([]Move{{puShield, 50}, blocker}, toMoves(sol)...), false)
	if err != nil || r.Mistakes != 0 {
		t.Fatalf("shielded bump: %v %+v", err, r)
	}
	if _, err := replay(p, expert, append([]Move{{puLife, 50}, blocker}, toMoves(sol)...), false); err != nil {
		t.Fatalf("spare life: %v", err)
	}
	r, err = replay(p, expert, append([]Move{{puFreeze, 50}}, toMoves(sol)...), false)
	if err != nil || r.Freezes != 1 {
		t.Fatalf("freeze: %v %+v", err, r)
	}
	if _, err := replay(p, expert, append([]Move{{puLife, 50}, {puLife, 60}}, toMoves(sol)...), false); err == nil {
		t.Fatal("powerup used twice should be rejected")
	}
	if _, err := replay(p, difficulties["Zen"], append([]Move{{puFreeze, 50}}, toMoves(sol)...), false); err == nil {
		t.Fatal("freeze without a timer should be rejected")
	}
	easy := difficulties["Easy"]
	r, err = replay(p, easy, append([]Move{blocker, {puUndo, 150}}, toMoves(sol)...), false)
	if err != nil || r.Mistakes != 0 {
		t.Fatalf("undo: %v %+v", err, r)
	}
	if _, err := replay(p, easy, append([]Move{{puUndo, 50}}, toMoves(sol)...), false); err == nil {
		t.Fatal("undo with nothing to undo should be rejected")
	}
	// Full clear: tapping pink while anything else is left is a bump, even when its own way out is clear.
	last := toMoves(sol)
	early := append(append([]Move{}, last[:len(last)-1]...), Move{0, last[len(last)-2].Ms + 1})
	early = append(early[:len(early)-2], early[len(early)-1], early[len(early)-2]) // pink just before the final arrow
	early[len(early)-1].Ms, early[len(early)-2].Ms = early[len(early)-2].Ms+500, early[len(early)-3].Ms+500
	if r, err := replay(p, easy, append(early, Move{0, early[len(early)-1].Ms + 500}), true); err != nil || r.Mistakes != 1 {
		t.Fatalf("early pink on full clear: %v %+v", err, r)
	}
	// Undo tap: Zen can put the last arrow back and take it again; modes with lives can't.
	first := toMoves(sol)[0]
	zen := []Move{first, {undoTap, first.Ms + 10}}
	for _, m := range toMoves(sol) {
		zen = append(zen, Move{m.Arrow, m.Ms + 20})
	}
	if r, err := replay(p, difficulties["Zen"], zen, false); err != nil || r.Cleared != len(sol) {
		t.Fatalf("undo tap on Zen: %v %+v", err, r)
	}
	if _, err := replay(p, easy, zen, false); err == nil {
		t.Fatal("undo tap on Easy should be rejected")
	}
	if _, err := replay(p, expert, append([]Move{{-9, 50}}, toMoves(sol)...), false); err == nil {
		t.Fatal("unknown powerup should be rejected")
	}
}

func TestCleanRunTagged(t *testing.T) {
	h := newHarness(t)
	_, reg := h.call("POST", "/v1/register", "", map[string]string{"name": "Calm"})
	tok := reg["token"].(string)
	_, st := h.call("POST", "/v1/runs", tok, map[string]any{"mode": "free", "n": 6, "maxLen": 6, "seed": 42, "difficulty": "Clean"})
	h.now = h.now.Add(2 * time.Minute)
	code, out := h.call("POST", "/v1/runs/"+st["run"].(string)+"/finish", tok, map[string]any{"moves": solve(generate(LevelSpec{N: 6, MaxLen: 6, Seed: 42}), human)})
	if code != 200 || out["status"] != "accepted" {
		t.Fatalf("clean run: %d %v", code, out)
	}
	_, board := h.call("GET", "/v1/leaderboard", "", nil)
	sc := board["scores"].([]any)[0].(map[string]any)
	pl := board["players"].([]any)[0].(map[string]any)
	if sc["difficulty"] != "Clean" || pl["cleanRuns"] != float64(1) {
		t.Fatalf("clean run not tagged: %v", board)
	}
}

func TestDailyAndRunDetails(t *testing.T) {
	h := newHarness(t)
	_, reg := h.call("POST", "/v1/register", "", map[string]string{"name": "Early"})
	tok := reg["token"].(string)
	today := int(h.now.Unix() / 86400)
	if code, _ := h.call("POST", "/v1/runs", tok, map[string]any{"mode": "daily", "day": today - 5, "difficulty": "Hard"}); code != 400 {
		t.Fatalf("old daily accepted: %d", code)
	}
	_, st := h.call("POST", "/v1/runs", tok, map[string]any{"mode": "daily", "day": today, "difficulty": "Hard"})
	moves := append([][2]int64{{puScan, 200}, {puFreeze, 300}}, solve(generate(dailySpec(today)), human)...)
	h.now = h.now.Add(5 * time.Minute) // a full clear takes a while
	if code, out := h.call("POST", "/v1/runs/"+st["run"].(string)+"/finish", tok, map[string]any{"moves": moves}); code != 200 || out["status"] != "accepted" {
		t.Fatalf("daily run: %d %v", code, out)
	}
	_, board := h.call("GET", "/v1/leaderboard", "", nil)
	sc := board["scores"].([]any)[0].(map[string]any)
	pu := sc["powerups"].([]any)
	if sc["size"] != float64(12) || sc["mistakes"] != float64(0) || len(pu) != 2 || pu[0] != "Scan" || sc["ms"].(float64) <= 0 {
		t.Fatalf("details missing: %v", sc)
	}
	code, rp := h.call("GET", "/v1/runs/"+sc["run"].(string)+"/replay", "", nil)
	if code != 200 || rp["seed"] != float64(dailySpec(today).Seed) || len(rp["moves"].([]any)) != len(moves) {
		t.Fatalf("replay: %d %v", code, rp)
	}
	_, daily := h.call("GET", fmt.Sprintf("/v1/daily/%d", today), "", nil)
	if ds := daily["scores"].([]any); len(ds) != 1 || ds[0].(map[string]any)["name"] != "Early" {
		t.Fatalf("daily board: %v", daily)
	}
	_, other := h.call("GET", fmt.Sprintf("/v1/daily/%d", today-1), "", nil)
	if len(other["scores"].([]any)) != 0 {
		t.Fatalf("yesterday's board should be empty: %v", other)
	}
	if code, _ := h.call("GET", "/v1/runs/nope/replay", "", nil); code != 404 {
		t.Fatalf("missing replay: %d", code)
	}
}

func TestWeekly(t *testing.T) {
	h := newHarness(t)
	_, reg := h.call("POST", "/v1/register", "", map[string]string{"name": "Weekly"})
	tok := reg["token"].(string)
	week := utcWeek(int(h.now.Unix() / 86400))
	if code, _ := h.call("POST", "/v1/runs", tok, map[string]any{"mode": "weekly", "week": week - 3, "difficulty": "Zen"}); code != 400 {
		t.Fatalf("old week accepted: %d", code)
	}
	_, st := h.call("POST", "/v1/runs", tok, map[string]any{"mode": "weekly", "week": week, "difficulty": "Zen"})
	h.now = h.now.Add(20 * time.Minute)
	if code, out := h.call("POST", "/v1/runs/"+st["run"].(string)+"/finish", tok, map[string]any{"moves": solve(generate(weekSpec(week)), human)}); code != 200 || out["status"] != "accepted" {
		t.Fatalf("weekly run: %d %v", code, out)
	}
	_, b := h.call("GET", fmt.Sprintf("/v1/weekly/%d", week), "", nil)
	sc := b["scores"].([]any)
	if len(sc) != 1 || sc[0].(map[string]any)["size"] != float64(16) {
		t.Fatalf("weekly board: %v", b)
	}
}
