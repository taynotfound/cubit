package main

import (
	"crypto/rand"
	"encoding/binary"
	"net/http"
	"sort"
	"strconv"
)

// An endless run: cube after cube on one seed, each one verified as its own ranked run (mode "endless"). The server
// keeps the count, so a run only grows by cubes it has seen cleared. Daily endless runs share the day's seed.
type EndlessRun struct {
	Device  string `json:"device"`
	Seed    int64  `json:"seed"`
	Day     int    `json:"day,omitempty"` // daily endless: days since 1970
	Cleared int    `json:"cleared"`
	Started int64  `json:"started"`
}

// EndlessScore is a player's best daily endless run on one day.
type EndlessScore struct {
	Device string `json:"device"`
	Day    int    `json:"day"`
	Cubes  int    `json:"cubes"`
	Time   int64  `json:"time"`
}

type endlessView struct {
	Name  string `json:"name"`
	Level int    `json:"level"`
	Cubes int    `json:"cubes"`
	Code  string `json:"code,omitempty"`
}

// startEndless opens an endless run and hands back its seed: random, or the day's for a daily endless run.
func (a *App) startEndless(w http.ResponseWriter, r *http.Request) {
	dev, _, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	if !a.limits.allow("endless:"+dev, 10, 10) {
		fail(w, http.StatusTooManyRequests, "slow down")
		return
	}
	var b struct{ Daily bool }
	if !decode(w, r, 256, &b) {
		return
	}
	e := &EndlessRun{Device: dev, Started: a.now().Unix()}
	if b.Daily {
		e.Day = int(a.now().Unix() / 86400)
		e.Seed = dailyEndlessSeed(e.Day)
	} else {
		var buf [8]byte
		rand.Read(buf[:])
		e.Seed = int64(binary.BigEndian.Uint64(buf[:]))
	}
	id := randID(12)
	a.store.mu.Lock()
	if a.store.db.Endless == nil {
		a.store.db.Endless = map[string]*EndlessRun{}
	}
	// Keep at most 3 open endless runs per player: a fourth drops the oldest.
	var mine []string
	for k, x := range a.store.db.Endless {
		if x.Device == dev {
			mine = append(mine, k)
		}
	}
	sort.Slice(mine, func(i, j int) bool { return a.store.db.Endless[mine[i]].Started < a.store.db.Endless[mine[j]].Started })
	for len(mine) >= 3 {
		delete(a.store.db.Endless, mine[0])
		mine = mine[1:]
	}
	a.store.db.Endless[id] = e
	a.store.mu.Unlock()
	a.store.markDirty()
	send(w, http.StatusOK, map[string]any{"id": id, "seed": strconv.FormatInt(e.Seed, 10), "day": e.Day})
}

// endlessCleared books cube k of endless run id as cleared, if it is the next one. Call with the store's mu held.
func (s *Store) endlessCleared(id string, k int, d *Device, dev string, now int64) {
	e := s.db.Endless[id]
	if e == nil || e.Device != dev || e.Cleared != k {
		return
	}
	e.Cleared++
	e.Started = now // pruning counts from the last cleared cube
	if e.Day == 0 && e.Cleared > d.EndlessBest {
		d.EndlessBest = e.Cleared
	}
	if e.Day > 0 {
		for i := range s.db.EndlessDaily {
			if x := &s.db.EndlessDaily[i]; x.Device == dev && x.Day == e.Day {
				if e.Cleared > x.Cubes {
					x.Cubes, x.Time = e.Cleared, now
				}
				return
			}
		}
		s.db.EndlessDaily = append(s.db.EndlessDaily, EndlessScore{dev, e.Day, e.Cleared, now})
	}
}

// endlessBoard is the top 50: everyone's best endless run, or one day's daily endless runs (day > 0).
func (s *Store) endlessBoard(day int) []endlessView {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := []endlessView{}
	add := func(dev string, cubes int) {
		if d := s.db.Devices[dev]; d != nil && !d.Banned && cubes > 0 {
			out = append(out, endlessView{d.Name, playerLevel(d.XP), cubes, d.publicCode()})
		}
	}
	if day == 0 {
		for id, d := range s.db.Devices {
			add(id, d.EndlessBest)
		}
	} else {
		for _, x := range s.db.EndlessDaily {
			if x.Day == day {
				add(x.Device, x.Cubes)
			}
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Cubes > out[j].Cubes })
	if len(out) > 50 {
		out = out[:50]
	}
	return out
}

// pruneEndless drops endless runs untouched for two days and daily scores older than 60 days.
func (s *Store) pruneEndless(now int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for id, e := range s.db.Endless {
		if now-e.Started > 2*86400 {
			delete(s.db.Endless, id)
		}
	}
	keep := s.db.EndlessDaily[:0]
	for _, x := range s.db.EndlessDaily {
		if now/86400-int64(x.Day) <= 60 {
			keep = append(keep, x)
		}
	}
	s.db.EndlessDaily = keep
}
