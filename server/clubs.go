package main

import (
	"net/http"
	"sort"
	"strings"
	"time"
)

// Clubs (1.9): small teams that add up their members' league XP each week. A club has a name (the same filter as
// player names), a 6-character join code and up to maxClub members. There is no club chat; the name is the only
// text, and an admin can remove a club.

const (
	maxClub        = 20
	clubCooldown   = 10 * time.Minute // between joining, leaving or creating clubs, so names can't be churned
	clubBoardSize  = 20
	clubCodeLength = 6
)

type Club struct {
	Code    string   `json:"code"`
	Name    string   `json:"name"`
	Owner   string   `json:"owner"`   // device id
	Members []string `json:"members"` // device ids, owner included, in join order
	Created int64    `json:"created"`
}

// clubCode is 6 characters people can read out loud: no 0/O or 1/I.
func (s *Store) newClubCode() string {
	const abc = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
	for {
		b := []byte(randID(clubCodeLength))
		for i := range b {
			b[i] = abc[int(b[i])%len(abc)]
		}
		if c := string(b[:clubCodeLength]); s.db.Clubs[c] == nil {
			return c
		}
	}
}

// weekXP is what a player earned toward this week's league. Call with mu held.
func weekXP(d *Device, week int) int {
	if d.League != nil && d.League.Week == week {
		return d.League.XP
	}
	return 0
}

func (s *Store) clubXP(c *Club, week int) int {
	t := 0
	for _, id := range c.Members {
		if d := s.db.Devices[id]; d != nil && !d.Banned {
			t += weekXP(d, week)
		}
	}
	return t
}

type clubRow struct {
	Code    string `json:"code"`
	Name    string `json:"name"`
	Members int    `json:"members"`
	XP      int    `json:"xp"`
}

// clubBoard ranks every club by this week's XP, then by size and age. Call with mu held.
func (s *Store) clubBoard(week int) []clubRow {
	rows := make([]clubRow, 0, len(s.db.Clubs))
	created := map[string]int64{}
	for _, c := range s.db.Clubs {
		rows = append(rows, clubRow{c.Code, c.Name, len(c.Members), s.clubXP(c, week)})
		created[c.Code] = c.Created
	}
	sort.Slice(rows, func(i, j int) bool {
		if rows[i].XP != rows[j].XP {
			return rows[i].XP > rows[j].XP
		}
		if rows[i].Members != rows[j].Members {
			return rows[i].Members > rows[j].Members
		}
		return created[rows[i].Code] < created[rows[j].Code]
	})
	return rows
}

// leaveClub takes the player out of their club; the next member becomes owner, and an empty club is gone.
// Call with mu held.
func (s *Store) leaveClub(id string, d *Device) {
	c := s.db.Clubs[d.Club]
	d.Club = ""
	if c == nil {
		return
	}
	c.Members = slicesDelete(c.Members, id)
	if len(c.Members) == 0 {
		delete(s.db.Clubs, c.Code)
		return
	}
	if c.Owner == id {
		c.Owner = c.Members[0]
	}
}

func slicesDelete(list []string, v string) []string {
	out := list[:0]
	for _, x := range list {
		if x != v {
			out = append(out, x)
		}
	}
	return out
}

func (a *App) clubReady(w http.ResponseWriter, d *Device, now time.Time) bool {
	if left := time.Unix(d.ClubAt, 0).Add(clubCooldown).Sub(now); d.ClubAt > 0 && left > 0 {
		fail(w, http.StatusTooManyRequests, "wait a few minutes before changing clubs again")
		return false
	}
	return true
}

// POST /v1/clubs {"name"}: start a club and join it as its owner.
func (a *App) createClub(w http.ResponseWriter, r *http.Request) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	var body struct {
		Name string `json:"name"`
	}
	if !decode(w, r, 1<<10, &body) {
		return
	}
	name, ok := cleanName(body.Name)
	if !ok {
		fail(w, http.StatusBadRequest, "pick another name: 2 to 16 letters or digits, nothing rude")
		return
	}
	now := a.now()
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	if d.Club != "" {
		fail(w, http.StatusConflict, "leave your club first")
		return
	}
	if d.XP == 0 {
		fail(w, http.StatusForbidden, "finish a ranked run first")
		return
	}
	if !a.clubReady(w, d, now) {
		return
	}
	for _, c := range a.store.db.Clubs {
		if strings.EqualFold(c.Name, name) {
			fail(w, http.StatusConflict, "a club already has that name")
			return
		}
	}
	if a.store.db.Clubs == nil {
		a.store.db.Clubs = map[string]*Club{}
	}
	c := &Club{Code: a.store.newClubCode(), Name: name, Owner: dev, Members: []string{dev}, Created: now.Unix()}
	a.store.db.Clubs[c.Code] = c
	d.Club, d.ClubAt = c.Code, now.Unix()
	a.store.markDirty()
	send(w, http.StatusOK, map[string]string{"code": c.Code, "name": c.Name})
}

// POST /v1/clubs/{code}/join
func (a *App) joinClub(w http.ResponseWriter, r *http.Request) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	now := a.now()
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	c := a.store.db.Clubs[strings.ToUpper(r.PathValue("code"))]
	switch {
	case c == nil:
		fail(w, http.StatusNotFound, "no club has that code")
	case d.Club == c.Code:
		send(w, http.StatusOK, map[string]string{"code": c.Code, "name": c.Name})
	case d.Club != "":
		fail(w, http.StatusConflict, "leave your club first")
	case len(c.Members) >= maxClub:
		fail(w, http.StatusConflict, "that club is full")
	case !a.clubReady(w, d, now):
	default:
		c.Members = append(c.Members, dev)
		d.Club, d.ClubAt = c.Code, now.Unix()
		a.store.markDirty()
		send(w, http.StatusOK, map[string]string{"code": c.Code, "name": c.Name})
	}
}

// POST /v1/me/club/leave
func (a *App) leaveClubHandler(w http.ResponseWriter, r *http.Request) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	if d.Club != "" {
		a.store.leaveClub(dev, d)
		d.ClubAt = a.now().Unix()
		a.store.markDirty()
	}
	send(w, http.StatusOK, map[string]bool{"ok": true})
}

// DELETE /v1/me/club/members/{code}: the owner removes a member, by friend code.
func (a *App) kickClubMember(w http.ResponseWriter, r *http.Request) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	c := a.store.db.Clubs[d.Club]
	if c == nil || c.Owner != dev {
		fail(w, http.StatusForbidden, "only the club's owner can do that")
		return
	}
	id := a.store.byCode(strings.ToUpper(r.PathValue("code")))
	m := a.store.db.Devices[id]
	if m == nil || m.Club != c.Code || id == dev {
		fail(w, http.StatusNotFound, "not in your club")
		return
	}
	a.store.leaveClub(id, m)
	a.store.markDirty()
	send(w, http.StatusOK, map[string]bool{"ok": true})
}

// GET /v1/me/club: the caller's club with its members' XP this week, and the top clubs.
func (a *App) myClub(w http.ResponseWriter, r *http.Request) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	now := a.now()
	week := utcWeek(int(now.Unix() / 86400))
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	board := a.store.clubBoard(week)
	out := map[string]any{"week": week, "ends": int64((week*7 - 3 + 7) * 86400), "max": maxClub, "of": len(board)}
	top := board
	if len(top) > clubBoardSize {
		top = top[:clubBoardSize]
	}
	out["top"] = top
	if c := a.store.db.Clubs[d.Club]; c != nil {
		members := []map[string]any{}
		for _, id := range c.Members {
			m := a.store.db.Devices[id]
			if m == nil || m.Banned {
				continue
			}
			members = append(members, map[string]any{"name": m.Name, "level": playerLevel(m.XP), "xp": weekXP(m, week),
				"code": m.Code, "me": id == dev, "owner": id == c.Owner})
		}
		sort.SliceStable(members, func(i, j int) bool { return members[i]["xp"].(int) > members[j]["xp"].(int) })
		rank := 0
		for i, row := range board {
			if row.Code == c.Code {
				rank = i + 1
			}
		}
		out["club"] = map[string]any{"code": c.Code, "name": c.Name, "owner": c.Owner == dev, "members": members,
			"xp": a.store.clubXP(c, week), "rank": rank}
	}
	send(w, http.StatusOK, out)
}

// GET /v1/admin/clubs: every club, newest first, to look over the names.
func (a *App) adminClubs(w http.ResponseWriter, r *http.Request) {
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	out := []map[string]any{}
	for _, c := range a.store.db.Clubs {
		owner := ""
		if d := a.store.db.Devices[c.Owner]; d != nil {
			owner = d.Name + " (" + d.Code + ")"
		}
		out = append(out, map[string]any{"code": c.Code, "name": c.Name, "owner": owner, "members": len(c.Members), "created": c.Created})
	}
	sort.Slice(out, func(i, j int) bool { return out[i]["created"].(int64) > out[j]["created"].(int64) })
	send(w, http.StatusOK, out)
}

// POST /v1/admin/clubs/{code} {"action": "remove"}: disbands a club (a bad name); its members are free to join another.
func (a *App) adminClub(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Action string `json:"action"`
	}
	if !decode(w, r, 1<<10, &body) {
		return
	}
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	c := a.store.db.Clubs[strings.ToUpper(r.PathValue("code"))]
	if c == nil || body.Action != "remove" {
		fail(w, http.StatusBadRequest, "no such club, or action isn't remove")
		return
	}
	for _, id := range c.Members {
		if d := a.store.db.Devices[id]; d != nil {
			d.Club = ""
		}
	}
	delete(a.store.db.Clubs, c.Code)
	a.store.markDirty()
	send(w, http.StatusOK, map[string]bool{"ok": true})
}
