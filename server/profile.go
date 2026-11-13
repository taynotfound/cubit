package main

import (
	"net/http"
	"regexp"
	"strings"
)

// Profile is what a player chose to show on their public page (off by default). Badges and the daily numbers come
// from their phone and aren't verified; XP, runs and endless best are the server's own.
type Profile struct {
	Public      bool     `json:"public"`
	Badges      []string `json:"badges,omitempty"`
	Streak      int      `json:"streak,omitempty"`
	Solved      int      `json:"solved,omitempty"`
	DailySolved int      `json:"dailySolved,omitempty"`
	Updated     int64    `json:"updated"`
}

var badgeName = regexp.MustCompile(`^[A-Z0-9_]{1,24}$`)

// setProfile turns the caller's public page on or off and refreshes what it shows.
func (a *App) setProfile(w http.ResponseWriter, r *http.Request) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	if !a.limits.allow("profile:"+dev, 10, 10) {
		fail(w, http.StatusTooManyRequests, "slow down")
		return
	}
	var b Profile
	if !decode(w, r, 4096, &b) {
		return
	}
	if len(b.Badges) > 40 {
		fail(w, http.StatusBadRequest, "too many badges")
		return
	}
	for _, x := range b.Badges {
		if !badgeName.MatchString(x) {
			fail(w, http.StatusBadRequest, "bad badge name")
			return
		}
	}
	clamp := func(v int) int { return max(0, min(v, 100_000)) }
	p := &Profile{Public: b.Public, Badges: b.Badges, Streak: clamp(b.Streak), Solved: clamp(b.Solved), DailySolved: clamp(b.DailySolved), Updated: a.now().Unix()}
	if !p.Public {
		p = nil // off means gone: nothing kept to show
	}
	a.store.mu.Lock()
	d.Profile = p
	a.store.mu.Unlock()
	a.store.markDirty()
	send(w, http.StatusOK, map[string]bool{"public": p != nil})
}

// profile is a public page's data, by friend code. Players who haven't opted in (or are banned) don't exist here.
func (a *App) profile(w http.ResponseWriter, r *http.Request) {
	c := strings.ToUpper(r.PathValue("code"))
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	id := a.store.byCode(c)
	d := a.store.db.Devices[id]
	if d == nil || d.Banned || d.Profile == nil || !d.Profile.Public {
		fail(w, http.StatusNotFound, "no public profile with that code")
		return
	}
	best := []publicScore{}
	for _, sc := range a.store.db.Scores {
		if sc.Device == id && len(best) < 5 {
			pu := sc.Powerups
			if pu == nil {
				pu = []string{}
			}
			best = append(best, publicScore{sc.RunID, d.Name, playerLevel(d.XP), sc.Score, sc.Label, sc.Difficulty, sc.Time, sc.Arrows, sc.Ms, sc.Mistakes, sc.Size, pu})
		}
	}
	p := d.Profile
	badges := p.Badges
	if badges == nil {
		badges = []string{}
	}
	w.Header().Set("Cache-Control", "public, max-age=30")
	send(w, http.StatusOK, map[string]any{"code": c, "name": d.Name, "level": playerLevel(d.XP), "xp": d.XP, "runs": d.Runs, "cleanRuns": d.CleanRuns,
		"endlessBest": d.EndlessBest, "since": d.Created, "badges": badges, "streak": p.Streak, "solved": p.Solved, "dailySolved": p.DailySolved, "best": best})
}

// stats is the public player count for the website: everyone with a verified run, and everyone who turned rankings on.
func (a *App) stats(w http.ResponseWriter, r *http.Request) {
	a.store.mu.Lock()
	out := map[string]int{"players": a.store.ranked(), "registered": len(a.store.db.Devices)}
	a.store.mu.Unlock()
	w.Header().Set("Cache-Control", "public, max-age=60")
	send(w, http.StatusOK, out)
}
