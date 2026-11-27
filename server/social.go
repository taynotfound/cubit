package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"regexp"
	"slices"
	"sort"
	"strings"
	"time"
	"unicode"
)

// Social features (1.8): bios, reports, friend notes (quick messages and challenges), the friend activity feed, weekly
// leagues, invites and the road to 1,000 players. There is no free text between players except the bio, which is
// short, filtered, reportable and can be removed by an admin.

// ---------- bios ----------

const maxBio = 140

// Off-platform contact (links, handles, numbers) is what makes a bio risky for young players, so none of it.
var bioContact = regexp.MustCompile(`(?i)(https?:|www\.|\.(com|net|org|de|io|gg|xyz|ru|me|tv|co|app|link|ly|to)\b|discord|t\.me|telegram|whatsapp|snapchat|\bsnap\b|insta|tiktok|onlyfans|\bdm\b|\d[\d\s().-]{6,}\d|@\w)`)

// cleanBio squeezes a bio to one line of at most maxBio characters, or says why it can't be shown.
func cleanBio(s string) (string, string) {
	var b strings.Builder
	space := false
	for _, r := range strings.TrimSpace(s) {
		switch {
		case unicode.IsSpace(r):
			if !space {
				b.WriteRune(' ')
			}
			space = true
		case unicode.IsControl(r) || unicode.Is(unicode.Cf, r): // control and invisible format characters
		default:
			b.WriteRune(r)
			space = false
		}
	}
	out := strings.TrimSpace(b.String())
	if n := len([]rune(out)); n > maxBio {
		return "", fmt.Sprintf("a bio is at most %d characters", maxBio)
	}
	if bioContact.MatchString(out) {
		return "", "no links, handles or numbers in a bio"
	}
	words := strings.Fields(out)
	for i, w := range words {
		if offensive(w) || (i+1 < len(words) && offensive(w+words[i+1])) {
			return "", "that bio isn't allowed"
		}
	}
	return out, ""
}

// publicBio is the bio others see: none while it's hidden for review.
func (d *Device) publicBio() string {
	if d.BioHidden {
		return ""
	}
	return d.Bio
}

// POST /v1/me/bio {"bio": "..."}: set or clear (empty) the caller's bio.
func (a *App) setBio(w http.ResponseWriter, r *http.Request) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	if !a.limits.allow("bio:"+dev, 5, 2) {
		fail(w, http.StatusTooManyRequests, "slow down")
		return
	}
	var b struct{ Bio string }
	if !decode(w, r, 2048, &b) {
		return
	}
	bio, why := cleanBio(b.Bio)
	if why != "" {
		fail(w, http.StatusBadRequest, why)
		return
	}
	a.store.mu.Lock()
	if d.BioLocked && bio != "" {
		a.store.mu.Unlock()
		fail(w, http.StatusForbidden, "your bio was removed by a moderator")
		return
	}
	d.Bio, d.BioHidden = bio, false
	a.store.db.Reports = slices.DeleteFunc(a.store.db.Reports, func(rp Report) bool { return rp.Code == d.Code }) // a new bio starts clean
	a.store.mu.Unlock()
	a.store.markDirty()
	send(w, http.StatusOK, map[string]string{"bio": bio})
}

// ---------- reports ----------

// Report is one player flagging another's bio. Three different reporters hide it until an admin looks.
type Report struct {
	Code string `json:"code"` // the reported player's friend code
	By   string `json:"by"`   // the reporter's device id
	At   int64  `json:"at"`
}

const hideAfterReports = 3

// POST /v1/report/{code}: report a player's bio.
func (a *App) report(w http.ResponseWriter, r *http.Request) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	c, ok := friendCode(r)
	if !ok {
		fail(w, http.StatusBadRequest, "bad code")
		return
	}
	if !a.limits.allow("report:"+dev, 10, 1) {
		fail(w, http.StatusTooManyRequests, "slow down")
		return
	}
	a.store.mu.Lock()
	t := a.store.db.Devices[a.store.byCode(c)]
	if t == nil || t.Bio == "" || c == d.Code {
		a.store.mu.Unlock()
		fail(w, http.StatusNotFound, "nothing to report")
		return
	}
	n, dup := 0, false
	for _, rp := range a.store.db.Reports {
		if rp.Code == c {
			n++
			dup = dup || rp.By == dev
		}
	}
	if !dup && len(a.store.db.Reports) < 5000 {
		a.store.db.Reports = append(a.store.db.Reports, Report{c, dev, a.now().Unix()})
		n++
	}
	hidden := n >= hideAfterReports && !t.BioHidden
	if hidden {
		t.BioHidden = true
	}
	name, bio := t.Name, t.Bio
	a.store.mu.Unlock()
	a.store.markDirty()
	if !dup {
		a.notifyAdmin(fmt.Sprintf("Bio reported (%d): %s %s: %q", n, name, c, bio))
	}
	send(w, http.StatusOK, map[string]bool{"ok": true})
}

// notifyAdmin pushes a line to the admin's ntfy topic when NTFY_URL is set (e.g. https://ntfy.example.org/cubit-admin).
func (a *App) notifyAdmin(msg string) { notify(msg) }

func notify(msg string) {
	u := os.Getenv("NTFY_URL")
	if u == "" {
		log.Println("admin:", msg)
		return
	}
	go func() {
		req, err := http.NewRequest(http.MethodPost, u, strings.NewReader(msg))
		if err != nil {
			return
		}
		req.Header.Set("Title", "Cubit moderation")
		if t := os.Getenv("NTFY_TOKEN"); t != "" {
			req.Header.Set("Authorization", "Bearer "+t)
		}
		c := &http.Client{Timeout: 10 * time.Second}
		if res, err := c.Do(req); err == nil {
			res.Body.Close()
		}
	}()
}

// GET /v1/admin/reports: reported bios, most reported first.
func (a *App) adminReports(w http.ResponseWriter, r *http.Request) {
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	count := map[string]int{}
	for _, rp := range a.store.db.Reports {
		count[rp.Code]++
	}
	out := []map[string]any{}
	for c, n := range count {
		if d := a.store.db.Devices[a.store.byCode(c)]; d != nil {
			out = append(out, map[string]any{"code": c, "name": d.Name, "bio": d.Bio, "reports": n, "hidden": d.BioHidden})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i]["reports"].(int) > out[j]["reports"].(int) })
	send(w, http.StatusOK, out)
}

// POST /v1/admin/reports/{code} {"action": "keep"|"remove"|"lock"}: keep the bio (and clear its reports), remove it,
// or remove it and stop the player from writing another.
func (a *App) adminReport(w http.ResponseWriter, r *http.Request) {
	var b struct{ Action string }
	if !decode(w, r, 256, &b) {
		return
	}
	if b.Action != "keep" && b.Action != "remove" && b.Action != "lock" {
		fail(w, http.StatusBadRequest, "action is keep, remove or lock")
		return
	}
	c := strings.ToUpper(r.PathValue("code"))
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	d := a.store.db.Devices[a.store.byCode(c)]
	if d == nil {
		fail(w, http.StatusNotFound, "no such player")
		return
	}
	switch b.Action {
	case "keep":
		d.BioHidden = false
	case "remove":
		d.Bio, d.BioHidden = "", false
	case "lock":
		d.Bio, d.BioHidden, d.BioLocked = "", false, true
	}
	a.store.db.Reports = slices.DeleteFunc(a.store.db.Reports, func(rp Report) bool { return rp.Code == c })
	a.store.markDirty()
	send(w, http.StatusOK, map[string]string{"ok": b.Action})
}

// ---------- notes: quick messages and challenges between friends ----------

// Note is something a friend sent: one of the app's fixed messages ([Msg], an index), or a challenge (a cube and their
// taps, in the challenge link format). Only between players who added each other.
type Note struct {
	ID        string `json:"id"`
	From      string `json:"from"` // sender's friend code
	Msg       int    `json:"msg"`
	Challenge string `json:"challenge,omitempty"`
	At        int64  `json:"at"`
}

const (
	quickMessages = 12 // the app's list (Social.kt); the server only checks the index
	maxNotes      = 50
)

var challengeChars = regexp.MustCompile(`^[0-9A-Za-z._~%-]+$`)

// POST /v1/friends/{code}/note {"msg": n} or {"challenge": "<payload>"}.
func (a *App) sendNote(w http.ResponseWriter, r *http.Request) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	c, ok := friendCode(r)
	if !ok {
		fail(w, http.StatusBadRequest, "bad code")
		return
	}
	var b struct {
		Msg       int
		Challenge string
	}
	if !decode(w, r, 10<<10, &b) {
		return
	}
	if b.Challenge != "" && (len(b.Challenge) < 8 || len(b.Challenge) > 8000 || !challengeChars.MatchString(b.Challenge)) || b.Challenge == "" && (b.Msg < 0 || b.Msg >= quickMessages) {
		fail(w, http.StatusBadRequest, "bad note")
		return
	}
	if !a.limits.allow("note:"+dev, 20, 4) || !a.limits.allow("note:"+dev+":"+c, 5, 0.5) {
		fail(w, http.StatusTooManyRequests, "slow down")
		return
	}
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	t := a.store.db.Devices[a.store.byCode(c)]
	if t == nil || t.Banned || !slices.Contains(d.Friends, c) || !slices.Contains(t.Friends, d.Code) {
		fail(w, http.StatusForbidden, "you can only send to friends who added you back")
		return
	}
	t.Inbox = append(t.Inbox, Note{randID(6), d.Code, b.Msg, b.Challenge, a.now().Unix()})
	if len(t.Inbox) > maxNotes {
		t.Inbox = t.Inbox[len(t.Inbox)-maxNotes:]
	}
	a.store.markDirty()
	send(w, http.StatusOK, map[string]bool{"ok": true})
}

// DELETE /v1/me/inbox/{id}: done with a note.
func (a *App) dropNote(w http.ResponseWriter, r *http.Request) {
	_, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	id := r.PathValue("id")
	a.store.mu.Lock()
	d.Inbox = slices.DeleteFunc(d.Inbox, func(n Note) bool { return n.ID == id })
	a.store.mu.Unlock()
	a.store.markDirty()
	send(w, http.StatusOK, map[string]bool{"ok": true})
}

type noteView struct {
	Note
	Name string `json:"name"`
}

// inboxFor is the caller's notes, newest first, from players who aren't banned. Call with mu held.
func (s *Store) inboxFor(d *Device) []noteView {
	out := []noteView{}
	for i := len(d.Inbox) - 1; i >= 0; i-- {
		n := d.Inbox[i]
		if f := s.db.Devices[s.byCode(n.From)]; f != nil && !f.Banned {
			out = append(out, noteView{n, f.Name})
		}
	}
	return out
}

// ---------- activity feed ----------

// Activity is one thing a player did that their friends see: a verified run, or a new player level.
type Activity struct {
	Kind  string `json:"kind"` // "run" or "level"
	Label string `json:"label,omitempty"`
	Score int    `json:"score,omitempty"`
	Ms    int64  `json:"ms,omitempty"`
	Level int    `json:"level,omitempty"`
	At    int64  `json:"at"`
}

type feedItem struct {
	Activity
	Code string `json:"code"`
	Name string `json:"name"`
}

// feedFor is what the caller's friends did in the last week, newest first. Call with mu held.
func (s *Store) feedFor(d *Device, now time.Time) []feedItem {
	out := []feedItem{}
	since := now.Add(-7 * 24 * time.Hour).Unix()
	for _, c := range d.Friends {
		f := s.db.Devices[s.byCode(c)]
		if f == nil || f.Banned {
			continue
		}
		for _, a := range f.Recent {
			if a.At >= since {
				out = append(out, feedItem{a, c, f.Name})
			}
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].At > out[j].At })
	if len(out) > 40 {
		out = out[:40]
	}
	return out
}

// ---------- leagues ----------

// League is a player's place in one week's league: a group of up to [leagueSize] players of the same tier, ranked by
// the XP they earn that week. The top [leagueMove] go up a tier, the bottom [leagueMove] go down.
type League struct {
	Week  int `json:"week"`
	Tier  int `json:"tier"`
	Group int `json:"group"`
	XP    int `json:"xp"`
}

// LeagueResult is how a player's last league week ended, shown once in the app.
type LeagueResult struct {
	Week int    `json:"week"`
	Rank int    `json:"rank"`
	Of   int    `json:"of"`
	Move string `json:"move"` // "up", "down" or "stay"
	Tier int    `json:"tier"` // the tier they're in now
}

const (
	leagueSize = 20
	leagueMove = 3
)

var tierNames = []string{"Bronze", "Silver", "Gold", "Platinum", "Diamond"}

type leagueSlot struct {
	Group int `json:"group"`
	Size  int `json:"size"`
}

// groupOf is everyone in league group (week, tier, group), with their XP that week, best first. Call with mu held.
func (s *Store) groupOf(week, tier, group int) []struct {
	ID string
	D  *Device
	XP int
} {
	var out []struct {
		ID string
		D  *Device
		XP int
	}
	for id, d := range s.db.Devices {
		for _, l := range []*League{d.League, d.PrevLeague} {
			if l != nil && l.Week == week && l.Tier == tier && l.Group == group && !d.Banned {
				out = append(out, struct {
					ID string
					D  *Device
					XP int
				}{id, d, l.XP})
				break
			}
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].XP != out[j].XP {
			return out[i].XP > out[j].XP
		}
		return out[i].ID < out[j].ID
	})
	return out
}

// tierAfter works out a player's tier after league week [l] ended, and how it went.
func (s *Store) tierAfter(id string, l *League) LeagueResult {
	g := s.groupOf(l.Week, l.Tier, l.Group)
	rank := len(g)
	for i, m := range g {
		if m.ID == id {
			rank = i + 1
		}
	}
	res := LeagueResult{Week: l.Week, Rank: rank, Of: len(g), Move: "stay", Tier: l.Tier}
	switch {
	case len(g) >= 5 && rank <= leagueMove && l.Tier < len(tierNames)-1:
		res.Move, res.Tier = "up", l.Tier+1
	case len(g) >= 10 && rank > len(g)-leagueMove && l.Tier > 0:
		res.Move, res.Tier = "down", l.Tier-1
	}
	return res
}

// leagueEarn books verified XP to the player's league week, joining one if this is their first XP of the week.
// Call with mu held.
func (s *Store) leagueEarn(id string, d *Device, xp int, now time.Time) {
	if xp <= 0 {
		return
	}
	week := utcWeek(int(now.Unix() / 86400))
	if d.League == nil || d.League.Week != week {
		tier := 0
		if d.League != nil {
			tier = d.League.Tier
			if d.League.Week == week-1 { // played last week: promotion or relegation
				res := s.tierAfter(id, d.League)
				d.LastLeague = &res
				tier = res.Tier
			}
			d.PrevLeague = d.League
		}
		if s.db.LeagueSlots == nil {
			s.db.LeagueSlots = map[string]*leagueSlot{}
		}
		key := fmt.Sprintf("%d:%d", week, tier)
		slot := s.db.LeagueSlots[key]
		if slot == nil {
			slot = &leagueSlot{}
			s.db.LeagueSlots[key] = slot
			for k := range s.db.LeagueSlots { // old weeks' slots are no longer needed
				var w int
				if _, err := fmt.Sscanf(k, "%d:", &w); err != nil || w < week-1 {
					delete(s.db.LeagueSlots, k)
				}
			}
		}
		if slot.Size >= leagueSize {
			slot.Group++
			slot.Size = 0
		}
		slot.Size++
		d.League = &League{Week: week, Tier: tier, Group: slot.Group}
	}
	d.League.XP += xp
}

// GET /v1/me/league: the caller's league this week (their group's table), and how last week went.
func (a *App) myLeague(w http.ResponseWriter, r *http.Request) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	now := a.now()
	week := utcWeek(int(now.Unix() / 86400))
	ends := int64((week*7 - 3 + 7) * 86400) // next Monday 00:00 UTC
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	out := map[string]any{"week": week, "ends": ends, "tiers": tierNames, "size": leagueSize, "move": leagueMove}
	tier := 0
	if d.League != nil {
		tier = d.League.Tier
		if d.League.Week < week && d.League.Week == week-1 {
			tier = a.store.tierAfter(dev, d.League).Tier
		}
	}
	out["tier"] = tier
	if d.LastLeague != nil && d.LastLeague.Week >= week-1 {
		out["last"] = d.LastLeague
	}
	players := []map[string]any{}
	if d.League != nil && d.League.Week == week {
		for _, m := range a.store.groupOf(week, d.League.Tier, d.League.Group) {
			players = append(players, map[string]any{"name": m.D.Name, "level": playerLevel(m.D.XP), "xp": m.XP, "code": m.D.publicCode(), "me": m.ID == dev, "dev": m.D.Dev})
		}
	}
	out["players"] = players
	send(w, http.StatusOK, out)
}

// ---------- invites ----------

const inviteWindow = 14 * 24 * time.Hour // how long after joining a player can say who invited them

// POST /v1/me/invited-by/{code}: the friend who brought the caller to Cubit. Both get a reward once the caller
// finishes a verified run.
func (a *App) invitedBy(w http.ResponseWriter, r *http.Request) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	c, ok := friendCode(r)
	if !ok {
		fail(w, http.StatusBadRequest, "bad code")
		return
	}
	if !a.limits.allow("invite:"+dev, 5, 1) {
		fail(w, http.StatusTooManyRequests, "slow down")
		return
	}
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	t := a.store.db.Devices[a.store.byCode(c)]
	switch {
	case t == nil || t.Banned:
		fail(w, http.StatusNotFound, "no player has that code")
	case c == d.Code:
		fail(w, http.StatusBadRequest, "that's your own code")
	case d.InvitedBy != "":
		fail(w, http.StatusConflict, "you already said who invited you")
	case a.now().Sub(time.Unix(d.Created, 0)) > inviteWindow || t.Created > d.Created:
		fail(w, http.StatusConflict, "invites only count for new players")
	default:
		d.InvitedBy = c
		if d.Runs > 0 { // already played: reward now
			a.store.inviteReward(d)
		}
		a.store.markDirty()
		send(w, http.StatusOK, map[string]any{"ok": true, "name": t.Name})
	}
}

// inviteReward pays out an invite once: one reward each to the new player and to who invited them. Call with mu held.
func (s *Store) inviteReward(d *Device) {
	if d.InvitedBy == "" || d.InviteDone {
		return
	}
	d.InviteDone = true
	d.Rewards++
	if t := s.db.Devices[s.byCode(d.InvitedBy)]; t != nil && !t.Banned {
		t.Rewards++
		t.Invited++
	}
}

// POST /v1/me/rewards: hands the app its unclaimed invite rewards (and zeroes them). The app gives 2 hints and a
// streak freeze for each.
func (a *App) claimRewards(w http.ResponseWriter, r *http.Request) {
	_, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	a.store.mu.Lock()
	n, invited := d.Rewards, d.Invited
	d.Rewards = 0
	a.store.mu.Unlock()
	if n > 0 {
		a.store.markDirty()
	}
	send(w, http.StatusOK, map[string]int{"rewards": n, "invited": invited})
}

// ---------- after every verified run ----------

const goalPlayers = 1000 // Road to 1,000: everyone unlocks the Gold colourway when the board gets there

// afterRun updates everything social that a verified run changes: the player's activity, league and invite.
// Call with mu held, right after d.credit.
func (s *Store) afterRun(id string, d *Device, sc Score, levelBefore int, now time.Time) {
	add := func(a Activity) {
		d.Recent = append(d.Recent, a)
		if len(d.Recent) > 8 {
			d.Recent = d.Recent[len(d.Recent)-8:]
		}
	}
	add(Activity{Kind: "run", Label: sc.Label, Score: sc.Score, Ms: sc.Ms, At: now.Unix()})
	if lv := playerLevel(d.XP); lv > levelBefore {
		add(Activity{Kind: "level", Level: lv, At: now.Unix()})
	}
	s.leagueEarn(id, d, sc.XP, now)
	s.inviteReward(d)
	s.cubeWon(id, sc, now)
}
