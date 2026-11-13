package main

import (
	"encoding/json"
	"errors"
	"io/fs"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

type Device struct {
	Name      string `json:"name"`
	TokenHash string `json:"tokenHash"` // sha256 of the bearer secret; the secret itself is never stored
	XP        int    `json:"xp"`        // only from verified runs
	Banned    bool   `json:"banned,omitempty"`
	Created   int64  `json:"created"`
	Runs      int    `json:"runs"`
	CleanRuns int    `json:"cleanRuns,omitempty"`
	Code      string `json:"code,omitempty"` // public friend code: 8 hex digits, upper case
	// EndlessBest is the most cubes in one (non-daily) endless run, counted by the server.
	EndlessBest int      `json:"endlessBest,omitempty"`
	Profile     *Profile `json:"profile,omitempty"` // opt-in public page; nil when off
}

// credit books a verified run to its player. Call with the store's mu held.
func (d *Device) credit(sc Score) {
	d.XP += sc.XP
	d.Runs++
	if sc.Difficulty == "Clean" {
		d.CleanRuns++
	}
}

type Score struct {
	RunID      string     `json:"run"`
	Device     string     `json:"device"`
	Score      int        `json:"score"`
	XP         int        `json:"xp"`
	Label      string     `json:"label"`
	Difficulty string     `json:"difficulty"`
	Arrows     int        `json:"arrows"`
	Ms         int64      `json:"ms"`
	Time       int64      `json:"time"`
	Mistakes   int        `json:"mistakes"`
	Size       int        `json:"size"`
	Powerups   []string   `json:"powerups,omitempty"`
	Spec       LevelSpec  `json:"spec"`
	Day        int        `json:"day,omitempty"`   // daily cube runs: days since 1970 (UTC)
	Week       int        `json:"week,omitempty"`  // weekly challenge runs: weeks since 1970
	Moves      [][2]int64 `json:"moves,omitempty"` // kept so anyone can watch the run back
}

// Held is a valid run that looked automated; it earns nothing until an admin approves it.
type Held struct {
	Score   Score    `json:"score"`
	Reasons []string `json:"reasons"`
}

type DB struct {
	Devices map[string]*Device `json:"devices"`
	Scores  []Score            `json:"scores"` // sorted by score, best first
	Held    []Held             `json:"held"`
	// Open endless runs by id, and each player's best daily endless run per day.
	Endless      map[string]*EndlessRun `json:"endless,omitempty"`
	EndlessDaily []EndlessScore         `json:"endlessDaily,omitempty"`
}

const maxScores = 5000

type Store struct {
	mu    sync.Mutex
	db    DB
	file  string
	dirty chan struct{}
}

func openStore(file string) (*Store, error) {
	s := &Store{file: file, dirty: make(chan struct{}, 1), db: DB{Devices: map[string]*Device{}}}
	raw, err := os.ReadFile(file)
	if errors.Is(err, fs.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	// Refuse to start on a corrupt file rather than overwrite it with an empty board.
	if err := json.Unmarshal(raw, &s.db); err != nil {
		return nil, err
	}
	if s.db.Devices == nil {
		s.db.Devices = map[string]*Device{}
	}
	for id, d := range s.db.Devices {
		if d.Code == "" { // players from before friend codes
			d.Code = s.newCode()
			s.markDirty() // save them now: a restart before the next write would hand out different codes
		}
		if offensive(d.Name) { // names from before the filter, or that a longer list now catches
			log.Printf("renamed %s: %q is not allowed on the board", id, d.Name)
			d.Name = "Player"
			s.markDirty()
		}
	}
	return s, nil
}

func (s *Store) markDirty() {
	select {
	case s.dirty <- struct{}{}:
	default:
	}
}

// saver batches writes so a burst of submissions costs one disk write.
func (s *Store) saver() {
	for range s.dirty {
		time.Sleep(300 * time.Millisecond)
		if err := s.save(); err != nil {
			log.Println("save failed:", err)
		}
	}
}

// backups copies the database to backups/cubit-db-YYYY-MM-DD.json once a day and keeps the last 14.
func (s *Store) backups() {
	for {
		if err := s.backupOnce(time.Now().UTC().Format("2006-01-02")); err != nil {
			log.Println("backup failed:", err)
		}
		time.Sleep(time.Hour)
	}
}

func (s *Store) backupOnce(day string) error {
	if err := os.MkdirAll("backups", 0o700); err != nil {
		return err
	}
	name := "backups/cubit-db-" + day + ".json"
	if _, err := os.Stat(name); err == nil {
		return nil // today's copy exists
	}
	s.mu.Lock()
	b, err := json.Marshal(s.db)
	s.mu.Unlock()
	if err != nil {
		return err
	}
	if err := os.WriteFile(name, b, 0o600); err != nil {
		return err
	}
	old, _ := filepath.Glob("backups/cubit-db-*.json")
	sort.Strings(old)
	for len(old) > 14 {
		os.Remove(old[0])
		old = old[1:]
	}
	return nil
}

// save writes a temp file, fsyncs it, keeps the previous file as .bak, then atomically renames into place.
func (s *Store) save() error {
	s.mu.Lock()
	b, err := json.Marshal(s.db)
	s.mu.Unlock()
	if err != nil {
		return err
	}
	tmp := s.file + ".tmp"
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err = f.Write(b); err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return err
	}
	// Hard-link the old file as .bak, then rename over it: the database path never goes missing, so a crash here
	// can't make the next start see no file and begin (and save) an empty board over the backup.
	_ = os.Remove(s.file + ".bak")
	_ = os.Link(s.file, s.file+".bak")
	return os.Rename(tmp, s.file)
}

// addScore must be called with mu held.
func (s *Store) addScore(sc Score) int {
	i := sort.Search(len(s.db.Scores), func(i int) bool { return s.db.Scores[i].Score < sc.Score })
	s.db.Scores = append(s.db.Scores, Score{})
	copy(s.db.Scores[i+1:], s.db.Scores[i:])
	s.db.Scores[i] = sc
	if len(s.db.Scores) > maxScores {
		s.db.Scores = s.db.Scores[:maxScores]
	}
	return s.rankOf(sc.Device)
}

// rankOf is the player's position on the public board, which lists one best run per player, so a run that
// doesn't beat the player's best still reports where they stand. 0 if they aren't on it. Call with mu held.
func (s *Store) rankOf(device string) int {
	seen := map[string]bool{}
	rank := 0
	for _, sc := range s.db.Scores {
		d := s.db.Devices[sc.Device]
		if d == nil || d.Banned || seen[sc.Device] {
			continue
		}
		seen[sc.Device] = true
		rank++
		if sc.Device == device {
			return rank
		}
	}
	return 0
}

type publicPlayer struct {
	Name      string `json:"name"`
	Level     int    `json:"level"`
	XP        int    `json:"xp"`
	CleanRuns int    `json:"cleanRuns"`
}

type publicScore struct {
	Run        string   `json:"run"`
	Name       string   `json:"name"`
	Level      int      `json:"level"`
	Score      int      `json:"score"`
	Label      string   `json:"label"`
	Difficulty string   `json:"difficulty"`
	Time       int64    `json:"time"`
	Arrows     int      `json:"arrows"`
	Ms         int64    `json:"ms"`
	Mistakes   int      `json:"mistakes"`
	Size       int      `json:"size"`
	Powerups   []string `json:"powerups"`
}

// board is the public view: no device ids, banned players hidden, one best run per player.
func (s *Store) board() map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	players := []publicPlayer{}
	for _, d := range s.db.Devices {
		if !d.Banned && d.XP > 0 {
			players = append(players, publicPlayer{d.Name, playerLevel(d.XP), d.XP, d.CleanRuns})
		}
	}
	total := len(players) // everyone with a verified run, not just the 50 listed
	sort.Slice(players, func(i, j int) bool { return players[i].XP > players[j].XP })
	if len(players) > 50 {
		players = players[:50]
	}
	scores := []publicScore{}
	seen := map[string]bool{}
	for _, sc := range s.db.Scores {
		d := s.db.Devices[sc.Device]
		if d == nil || d.Banned || seen[sc.Device] {
			continue
		}
		seen[sc.Device] = true
		pu := sc.Powerups
		if pu == nil {
			pu = []string{}
		}
		scores = append(scores, publicScore{sc.RunID, d.Name, playerLevel(d.XP), sc.Score, sc.Label, sc.Difficulty, sc.Time, sc.Arrows, sc.Ms, sc.Mistakes, sc.Size, pu})
		if len(scores) == 50 {
			break
		}
	}
	return map[string]any{"players": players, "scores": scores, "total": total}
}

// ranked counts the players the board counts: not banned, with XP from at least one verified run. Call with mu held.
func (s *Store) ranked() int {
	n := 0
	for _, d := range s.db.Devices {
		if !d.Banned && d.XP > 0 {
			n++
		}
	}
	return n
}

// daily is one day's board: best run per player on that day's cube, banned players hidden.
func (s *Store) daily(day int) []publicScore {
	return s.boardWhere(func(sc Score) bool { return sc.Day == day })
}

// weekly is one week's challenge board.
func (s *Store) weekly(week int) []publicScore {
	return s.boardWhere(func(sc Score) bool { return sc.Week == week })
}

// boardWhere is the best run per player among the matching scores (already sorted best first), top 50.
func (s *Store) boardWhere(match func(Score) bool) []publicScore {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := []publicScore{}
	seen := map[string]bool{}
	for _, sc := range s.db.Scores {
		d := s.db.Devices[sc.Device]
		if !match(sc) || d == nil || d.Banned || seen[sc.Device] {
			continue
		}
		seen[sc.Device] = true
		pu := sc.Powerups
		if pu == nil {
			pu = []string{}
		}
		out = append(out, publicScore{sc.RunID, d.Name, playerLevel(d.XP), sc.Score, sc.Label, sc.Difficulty, sc.Time, sc.Arrows, sc.Ms, sc.Mistakes, sc.Size, pu})
		if len(out) == 50 {
			break
		}
	}
	return out
}

// replayOf returns a board run's cube and taps, or nil. Banned players' runs stay hidden.
func (s *Store) replayOf(run string) map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, sc := range s.db.Scores {
		if sc.RunID != run {
			continue
		}
		if d := s.db.Devices[sc.Device]; d == nil || d.Banned || sc.Moves == nil {
			return nil
		}
		return map[string]any{"n": sc.Spec.N, "maxLen": sc.Spec.MaxLen, "seed": sc.Spec.Seed, "lap": sc.Spec.Lap, "special": sc.Spec.Special, "moves": sc.Moves,
			"all": strings.HasSuffix(sc.Label, "· full clear")} // full clear: the pink arrow leaves last
	}
	return nil
}

// newCode is an unused friend code. Call with mu held (or before the store is shared).
func (s *Store) newCode() string {
	for {
		c := strings.ToUpper(randID(4))
		if s.byCode(c) == "" {
			return c
		}
	}
}

// byCode finds a device id by friend code, or "". Call with mu held.
// shortcut: a linear scan over devices, fine for thousands of players; add a code index if the board grows past that.
func (s *Store) byCode(code string) string {
	for id, d := range s.db.Devices {
		if d.Code == code {
			return id
		}
	}
	return ""
}

type friendView struct {
	Code      string       `json:"code"`
	Name      string       `json:"name"`
	Level     int          `json:"level"`
	XP        int          `json:"xp"`
	CleanRuns int          `json:"cleanRuns"`
	Best      *publicScore `json:"best"`
}

// friends is the public view of the players behind these codes (unknown and banned ones left out), each with their
// best run. It shows no more than the public board would.
func (s *Store) friends(codes []string) []friendView {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := []friendView{}
	for _, c := range codes {
		id := s.byCode(c)
		d := s.db.Devices[id]
		if d == nil || d.Banned {
			continue
		}
		f := friendView{Code: c, Name: d.Name, Level: playerLevel(d.XP), XP: d.XP, CleanRuns: d.CleanRuns}
		for _, sc := range s.db.Scores {
			if sc.Device == id {
				pu := sc.Powerups
				if pu == nil {
					pu = []string{}
				}
				f.Best = &publicScore{sc.RunID, d.Name, f.Level, sc.Score, sc.Label, sc.Difficulty, sc.Time, sc.Arrows, sc.Ms, sc.Mistakes, sc.Size, pu}
				break
			}
		}
		out = append(out, f)
	}
	return out
}

// playerLevel mirrors playerLevel() in Prefs.kt.
func playerLevel(xp int) int {
	k := 1
	for xp >= 100+60*(k-1) {
		xp -= 100 + 60*(k-1)
		k++
	}
	return k
}
