package main

import (
	"crypto/rand"
	"encoding/binary"
	"log"
	"net/http"
	"sort"
	"strconv"
)

// The World Cube: one big cube every player clears together. Each tap is checked against the shared board here and
// what it clears is gone for everyone; whoever frees the pink arrow wins that cube, and a fresh one starts.
const (
	worldN      = 24
	worldMaxLen = 12
)

type World struct {
	ID      int            `json:"id"`
	Seed    int64          `json:"seed"`
	Removed []int          `json:"removed"` // in the order they went; its length is the board's version
	Clears  map[string]int `json:"clears"`  // arrows each player cleared on this cube
	Started int64          `json:"started"`
	History []WorldWin     `json:"history,omitempty"` // newest last, the last 50
}

type WorldWin struct {
	ID       int    `json:"id"`
	Winner   string `json:"winner"` // name at the time
	Code     string `json:"code,omitempty"`
	Players  int    `json:"players"`
	Arrows   int    `json:"arrows"`
	Seconds  int64  `json:"seconds"`
	Finished int64  `json:"finished"`
}

func worldSpec(seed int64) LevelSpec {
	return LevelSpec{N: worldN, MaxLen: worldMaxLen, Seed: seed, Special: true}
}

// worldBoard is the live board for the current cube, rebuilt from the stored seed and removals. Call with store mu held.
type worldBoard struct {
	id    int
	p     *Puzzle
	start []int // untouched board, for blasts
	occ   []int
	gone  []bool
	left  int
}

func newWorld(id int, now int64) *World {
	var b [8]byte
	rand.Read(b[:])
	return &World{ID: id, Seed: int64(binary.BigEndian.Uint64(b[:])), Clears: map[string]int{}, Started: now}
}

// board returns the live board, building it (and the world itself, the first time) when needed. Call with mu held.
func (a *App) worldBoard() *worldBoard {
	s := a.store
	if s.db.World == nil {
		s.db.World = newWorld(1, a.now().Unix())
		s.markDirty()
	}
	w := s.db.World
	if a.world != nil && a.world.id == w.ID {
		return a.world
	}
	p := generate(worldSpec(w.Seed))
	b := &worldBoard{id: w.ID, p: p, occ: occupancy(p), gone: make([]bool, len(p.paths)), left: len(p.paths)}
	b.start = append([]int(nil), b.occ...)
	for _, x := range w.Removed {
		b.take(x)
	}
	a.world = b
	return b
}

func (b *worldBoard) take(x int) {
	if x < 0 || x >= len(b.gone) || b.gone[x] {
		return
	}
	b.gone[x] = true
	b.left--
	for _, c := range b.p.paths[x] {
		b.occ[c] = -1
	}
}

func occupancy(p *Puzzle) []int {
	occ := make([]int, p.cube.size)
	for i := range occ {
		occ[i] = -1
	}
	for i, path := range p.paths {
		for _, c := range path {
			occ[c] = i
		}
	}
	return occ
}

// worldState: the cube, and the removals since version v (all of them for another cube or no v).
func (a *App) worldState(w http.ResponseWriter, r *http.Request) {
	since, _ := strconv.Atoi(r.URL.Query().Get("since"))
	id, _ := strconv.Atoi(r.URL.Query().Get("id"))
	a.store.mu.Lock()
	b := a.worldBoard()
	wd := a.store.db.World
	removed := wd.Removed
	if id == wd.ID && since >= 0 && since <= len(removed) {
		removed = removed[since:]
	}
	out := map[string]any{"id": wd.ID, "seed": strconv.FormatInt(wd.Seed, 10), "n": worldN, "maxLen": worldMaxLen, "v": len(wd.Removed),
		"removed": append([]int{}, removed...), "left": b.left, "arrows": len(b.p.paths), "players": len(wd.Clears), "top": a.worldTop(5)}
	if len(wd.History) > 0 {
		out["last"] = wd.History[len(wd.History)-1]
	}
	a.store.mu.Unlock()
	w.Header().Set("Cache-Control", "no-store")
	send(w, 200, out)
}

// worldTop is the cube's biggest contributors. Call with mu held.
func (a *App) worldTop(n int) []map[string]any {
	type row struct {
		name   string
		arrows int
	}
	var rows []row
	for dev, k := range a.store.db.World.Clears {
		if d := a.store.db.Devices[dev]; d != nil && !d.Banned {
			rows = append(rows, row{d.Name, k})
		}
	}
	sort.Slice(rows, func(i, j int) bool { return rows[i].arrows > rows[j].arrows || rows[i].arrows == rows[j].arrows && rows[i].name < rows[j].name })
	out := []map[string]any{}
	for i := 0; i < len(rows) && i < n; i++ {
		out = append(out, map[string]any{"name": rows[i].name, "arrows": rows[i].arrows})
	}
	return out
}

// worldTap is one player's tap on the shared cube. A blocked or gone arrow just says so (there are no lives here).
func (a *App) worldTap(w http.ResponseWriter, r *http.Request) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	if d.Banned {
		fail(w, http.StatusForbidden, "banned")
		return
	}
	// About as fast as a person taps, and no faster for long.
	if !a.limits.allow("world:"+dev, 8, 150) {
		fail(w, http.StatusTooManyRequests, "slow down")
		return
	}
	var b struct{ ID, Arrow int }
	if !decode(w, r, 256, &b) {
		return
	}
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	bd := a.worldBoard()
	wd := a.store.db.World
	switch {
	case b.ID != wd.ID:
		send(w, 200, map[string]any{"ok": false, "why": "new cube", "id": wd.ID})
		return
	case b.Arrow < 0 || b.Arrow >= len(bd.gone):
		fail(w, http.StatusBadRequest, "no such arrow")
		return
	case bd.gone[b.Arrow]:
		send(w, 200, map[string]any{"ok": false, "why": "gone", "v": len(wd.Removed)})
		return
	case !bd.p.free(b.Arrow, bd.occ, bd.gone):
		send(w, 200, map[string]any{"ok": false, "why": "blocked", "v": len(wd.Removed)})
		return
	}
	group := bd.p.cleared(b.Arrow, bd.start, bd.gone)
	for _, x := range group {
		bd.take(x)
	}
	wd.Removed = append(wd.Removed, group...)
	wd.Clears[dev] += len(group)
	out := map[string]any{"ok": true, "removed": group, "v": len(wd.Removed), "left": bd.left}
	if bd.gone[0] { // the pink arrow: this player wins the cube, and a new one starts
		now := a.now().Unix()
		win := WorldWin{wd.ID, d.Name, d.Code, len(wd.Clears), len(bd.p.paths) - bd.left, now - wd.Started, now}
		hist := append(wd.History, win)
		if len(hist) > 50 {
			hist = hist[len(hist)-50:]
		}
		log.Printf("world cube %d freed by %s after %d arrows from %d players", wd.ID, dev[:8], win.Arrows, win.Players)
		next := newWorld(wd.ID+1, now)
		next.History = hist
		a.store.db.World = next
		out["won"] = true
	}
	a.store.markDirty()
	send(w, 200, out)
}
