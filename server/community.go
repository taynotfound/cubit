package main

import (
	"fmt"
	"net/http"
	"slices"
	"sort"
	"strconv"
	"strings"
)

// Community cubes (1.9): players build a cube in the app's cube maker (size, arrow length, special arrows, and
// rerolls until they like it), give it a title and publish it here. Everyone can browse, play and like them. A cube
// is only its spec, so the title is the only text: it goes through the name filter, three reports hide it, and an
// admin can keep or remove it. Wins on a community cube are counted when the run verifies (afterRun).

const (
	cubesPerDay    = 5  // a player can publish this many cubes a day
	cubesPerPlayer = 50 // and keep this many up at once
	cubePage       = 30 // cubes per page of the list
	cubeReports    = 3  // distinct reporters that hide a cube until an admin decides
	maxCubes       = 20000
	maxCubeWinners = 5000 // winners remembered per cube (each counts once); beyond that wins still count
)

type CommunityCube struct {
	ID        string    `json:"id"`
	Title     string    `json:"title"`
	Author    string    `json:"author"` // device id
	Spec      LevelSpec `json:"spec"`
	Arrows    int       `json:"arrows"`
	Par       int       `json:"par"`
	Created   int64     `json:"created"`
	Likes     []string  `json:"likes,omitempty"`   // device ids
	Winners   []string  `json:"winners,omitempty"` // device ids, first maxCubeWinners
	Wins      int       `json:"wins,omitempty"`
	Reporters []string  `json:"reporters,omitempty"`
	Hidden    bool      `json:"hidden,omitempty"` // reported; out of the list until an admin keeps it
	Kept      bool      `json:"kept,omitempty"`   // an admin looked and kept it: reports no longer hide it
}

func specKey(s LevelSpec) string {
	return fmt.Sprintf("%d:%d:%d:%t", s.N, s.MaxLen, s.Seed, s.Special)
}

// cubeBySpec finds the community cube a run was played on. Call with mu held.
func (s *Store) cubeBySpec(spec LevelSpec) *CommunityCube {
	k := specKey(LevelSpec{N: spec.N, MaxLen: spec.MaxLen, Seed: spec.Seed, Special: spec.Special})
	for _, c := range s.db.Cubes {
		if specKey(c.Spec) == k {
			return c
		}
	}
	return nil
}

// cubeWon counts a verified win on a community cube, once per player. Call with mu held.
func (s *Store) cubeWon(id string, spec LevelSpec) {
	if spec.Lap {
		return
	}
	c := s.cubeBySpec(spec)
	if c == nil || c.Author == id || slices.Contains(c.Winners, id) {
		return
	}
	c.Wins++
	if len(c.Winners) < maxCubeWinners {
		c.Winners = append(c.Winners, id)
	}
}

type cubeView struct {
	ID      string `json:"id"`
	Title   string `json:"title"`
	Author  string `json:"author"`
	N       int    `json:"n"`
	MaxLen  int    `json:"maxLen"`
	Seed    int64  `json:"seed"`
	Special bool   `json:"special"`
	Arrows  int    `json:"arrows"`
	Par     int    `json:"par"`
	Likes   int    `json:"likes"`
	Wins    int    `json:"wins"`
	Liked   bool   `json:"liked"`
	Won     bool   `json:"won"`
	Mine    bool   `json:"mine"`
	Created int64  `json:"created"`
}

func (s *Store) viewCube(c *CommunityCube, me string) cubeView {
	author := "Player"
	if d := s.db.Devices[c.Author]; d != nil {
		author = d.Name
	}
	return cubeView{c.ID, c.Title, author, c.Spec.N, c.Spec.MaxLen, c.Spec.Seed, c.Spec.Special, c.Arrows, c.Par,
		len(c.Likes), c.Wins, me != "" && slices.Contains(c.Likes, me), me != "" && slices.Contains(c.Winners, me), c.Author == me, c.Created}
}

// POST /v1/cubes {"title","n","maxLen","seed","special"}: publishes a cube from the cube maker.
func (a *App) publishCube(w http.ResponseWriter, r *http.Request) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	var b struct {
		Title   string `json:"title"`
		N       int    `json:"n"`
		MaxLen  int    `json:"maxLen"`
		Seed    int64  `json:"seed"`
		Special bool   `json:"special"`
	}
	if !decode(w, r, 1<<10, &b) {
		return
	}
	title, ok := cleanName(b.Title)
	if !ok {
		fail(w, http.StatusBadRequest, "pick another title: 2 to 16 letters or digits, nothing rude")
		return
	}
	if b.N < 5 || b.N > 18 || b.MaxLen < 3 || b.MaxLen > 16 {
		fail(w, http.StatusBadRequest, "cubes are 5 to 18 wide with arrows 3 to 16 long")
		return
	}
	spec := LevelSpec{N: b.N, MaxLen: b.MaxLen, Seed: b.Seed, Special: b.Special && b.N >= 5, NoLocks: b.Special}
	now := a.now()
	a.store.mu.Lock()
	if d.XP == 0 {
		a.store.mu.Unlock()
		fail(w, http.StatusForbidden, "finish a ranked run first")
		return
	}
	today, mine := 0, 0
	for _, c := range a.store.db.Cubes {
		if c.Author == dev {
			mine++
			if now.Unix()-c.Created < 86400 {
				today++
			}
		}
	}
	dup := a.store.cubeBySpec(spec) != nil
	full := len(a.store.db.Cubes) >= maxCubes
	a.store.mu.Unlock()
	switch {
	case dup:
		fail(w, http.StatusConflict, "someone already published this exact cube")
		return
	case today >= cubesPerDay:
		fail(w, http.StatusTooManyRequests, fmt.Sprintf("that's %d cubes today; publish more tomorrow", cubesPerDay))
		return
	case mine >= cubesPerPlayer:
		fail(w, http.StatusConflict, fmt.Sprintf("you have %d cubes up; remove one first", cubesPerPlayer))
		return
	case full:
		fail(w, http.StatusServiceUnavailable, "the community board is full right now")
		return
	}
	// Build it outside the lock: big cubes take a moment. The arrow count and par show on the list.
	p := generate(spec)
	c := &CommunityCube{ID: randID(4), Title: title, Author: dev, Spec: spec, Arrows: len(p.paths), Par: mustClear(p) + 1, Created: now.Unix()}
	a.store.mu.Lock()
	if a.store.db.Cubes == nil {
		a.store.db.Cubes = map[string]*CommunityCube{}
	}
	a.store.db.Cubes[c.ID] = c
	v := a.store.viewCube(c, dev)
	a.store.mu.Unlock()
	a.store.markDirty()
	send(w, http.StatusOK, v)
}

// GET /v1/cubes?sort=top|new&page=0: published cubes (hidden ones left out), 30 a page. Signed in, each says
// whether you liked or won it. ?mine=1 lists your own, hidden ones included.
func (a *App) listCubes(w http.ResponseWriter, r *http.Request) {
	dev, _, code := a.auth(r)
	if code != 0 {
		dev = ""
	}
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 0 || page > 1000 {
		page = 0
	}
	sortBy := r.URL.Query().Get("sort")
	mine := r.URL.Query().Get("mine") == "1" && dev != ""
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	list := make([]*CommunityCube, 0, len(a.store.db.Cubes))
	for _, c := range a.store.db.Cubes {
		if mine && c.Author != dev || !mine && c.Hidden {
			continue
		}
		if d := a.store.db.Devices[c.Author]; d != nil && d.Banned && !mine {
			continue
		}
		list = append(list, c)
	}
	sort.Slice(list, func(i, j int) bool {
		if sortBy != "new" && len(list[i].Likes) != len(list[j].Likes) {
			return len(list[i].Likes) > len(list[j].Likes)
		}
		if list[i].Created != list[j].Created {
			return list[i].Created > list[j].Created
		}
		return list[i].ID < list[j].ID
	})
	total := len(list)
	from := min(page*cubePage, total)
	out := []cubeView{}
	for _, c := range list[from:min(from+cubePage, total)] {
		out = append(out, a.store.viewCube(c, dev))
	}
	send(w, http.StatusOK, map[string]any{"cubes": out, "total": total, "page": page, "size": cubePage})
}

func (a *App) cubeAction(w http.ResponseWriter, r *http.Request, act func(dev string, d *Device, c *CommunityCube) (int, string)) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	a.store.mu.Lock()
	c := a.store.db.Cubes[strings.ToLower(r.PathValue("id"))]
	if c == nil {
		a.store.mu.Unlock()
		fail(w, http.StatusNotFound, "no such cube")
		return
	}
	st, msg := act(dev, d, c)
	var v any = map[string]bool{"ok": true}
	if st == http.StatusOK && a.store.db.Cubes[c.ID] != nil {
		v = a.store.viewCube(c, dev)
	}
	a.store.mu.Unlock()
	if st != http.StatusOK {
		fail(w, st, msg)
		return
	}
	a.store.markDirty()
	send(w, st, v)
}

// POST /v1/cubes/{id}/like and DELETE /v1/cubes/{id}/like
func (a *App) likeCube(w http.ResponseWriter, r *http.Request) {
	on := r.Method == http.MethodPost
	a.cubeAction(w, r, func(dev string, d *Device, c *CommunityCube) (int, string) {
		if c.Author == dev {
			return http.StatusBadRequest, "that's your own cube"
		}
		has := slices.Contains(c.Likes, dev)
		if on && !has {
			c.Likes = append(c.Likes, dev)
		} else if !on && has {
			c.Likes = slicesDelete(c.Likes, dev)
		}
		return http.StatusOK, ""
	})
}

// POST /v1/cubes/{id}/report: three different players hide a cube until an admin looks.
func (a *App) reportCube(w http.ResponseWriter, r *http.Request) {
	a.cubeAction(w, r, func(dev string, d *Device, c *CommunityCube) (int, string) {
		if c.Author == dev || slices.Contains(c.Reporters, dev) {
			return http.StatusOK, ""
		}
		c.Reporters = append(c.Reporters, dev)
		if len(c.Reporters) >= cubeReports && !c.Kept && !c.Hidden {
			c.Hidden = true
			a.notifyAdmin(fmt.Sprintf("Community cube %q (%s) hidden after %d reports", c.Title, c.ID, len(c.Reporters)))
		}
		return http.StatusOK, ""
	})
}

// DELETE /v1/cubes/{id}: the author takes their cube down.
func (a *App) removeCube(w http.ResponseWriter, r *http.Request) {
	a.cubeAction(w, r, func(dev string, d *Device, c *CommunityCube) (int, string) {
		if c.Author != dev {
			return http.StatusForbidden, "only its author can remove a cube"
		}
		delete(a.store.db.Cubes, c.ID)
		return http.StatusOK, ""
	})
}

// GET /v1/admin/cubes: reported cubes, hidden first.
func (a *App) adminCubes(w http.ResponseWriter, r *http.Request) {
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	out := []map[string]any{}
	for _, c := range a.store.db.Cubes {
		if len(c.Reporters) == 0 || c.Kept {
			continue
		}
		v := a.store.viewCube(c, "")
		out = append(out, map[string]any{"id": c.ID, "title": c.Title, "author": v.Author, "reports": len(c.Reporters), "hidden": c.Hidden})
	}
	sort.Slice(out, func(i, j int) bool { return out[i]["reports"].(int) > out[j]["reports"].(int) })
	send(w, http.StatusOK, out)
}

// POST /v1/admin/cubes/{id} {"action": "keep"|"remove"}
func (a *App) adminCube(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Action string `json:"action"`
	}
	if !decode(w, r, 1<<10, &b) {
		return
	}
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	c := a.store.db.Cubes[strings.ToLower(r.PathValue("id"))]
	switch {
	case c == nil:
		fail(w, http.StatusNotFound, "no such cube")
		return
	case b.Action == "keep":
		c.Hidden, c.Kept = false, true
	case b.Action == "remove":
		delete(a.store.db.Cubes, c.ID)
	default:
		fail(w, http.StatusBadRequest, "action is keep or remove")
		return
	}
	a.store.markDirty()
	send(w, http.StatusOK, map[string]bool{"ok": true})
}
