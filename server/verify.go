package main

import (
	"errors"
	"fmt"
	"math"
	"sort"
)

// Difficulty mirrors the Difficulty enum in Prefs.kt (same float32 maths so scores match the app).
type Difficulty struct {
	Lives       int
	SecPerArrow float32
	XPMult      float32
	Nightmare   bool // arrows lap the whole cube before leaving (a lap cube), and no powerups
}

var difficulties = map[string]Difficulty{
	"Zen":       {0, 0, 0.5, false},
	"Easy":      {5, 0, 1, false},
	"Normal":    {3, 4, 1.3, false},
	"Hard":      {3, 2, 1.7, false},
	"Expert":    {1, 1.2, 2.4, false},
	"Clean":     {0, 0, 0.5, false}, // Zen rules with nothing on screen but the cube; tagged on the board
	"Nightmare": {1, 2.5, 3.5, true},
}

// Powerup codes a run records as a negative arrow index (Powerup.code in Prefs.kt). Each works once per run.
const (
	puShield = -1 // the next bump costs nothing
	puFreeze = -2 // +freezeSec on the clock
	puLife   = -3 // one extra life
	puScan   = -4 // shows free arrows; cosmetic, recorded for the run details
	puUndo   = -5 // takes back the last bump: the mistake is struck off
	undoTap  = -6 // Zen/Clean only, any number of times: puts the last removed arrow back
)

var powerupNames = map[int]string{puShield: "Shield", puFreeze: "Freeze", puLife: "Spare life", puScan: "Scan", puUndo: "Undo"}

const freezeSec = 15

func (d Difficulty) timeLimitSec(arrows int) int {
	if d.SecPerArrow == 0 {
		return 0
	}
	return int(float32(10) + float32(arrows)*d.SecPerArrow)
}

// Move is one tap: which arrow (or a negative powerup code), and the client's game clock in ms (paused while the
// app is backgrounded).
type Move struct {
	Arrow int
	Ms    int64
}

type Replay struct {
	Cleared, Mistakes int
	Freezes           int
	Powerups          []string // names, in the order used
	LastMs            int64
	Gaps              []int64 // ms between consecutive taps
}

// replay re-runs the taps against the regenerated puzzle under the game's rules. Any error means the run is
// impossible (forged or tampered), not merely suspicious.
// With all set the run is a full clear: the pink arrow only leaves once every other arrow has gone.
func replay(p *Puzzle, d Difficulty, moves []Move, all bool) (Replay, error) {
	var r Replay
	n := len(p.paths)
	if len(moves) == 0 {
		return r, errors.New("no moves")
	}
	if len(moves) > n+250 {
		return r, errors.New("too many moves")
	}
	occ := make([]int, p.cube.size)
	for i := range occ {
		occ[i] = -1
	}
	for i, path := range p.paths {
		for _, c := range path {
			occ[c] = i
		}
	}
	start := append([]int(nil), occ...) // the untouched board, for working out a bomb's neighbours
	removed := make([]bool, n)
	var stack [][]int // arrows removed by each tap (a bomb takes its neighbours too), newest last, for undoTap
	prev, prevTap := int64(0), int64(-1)
	lives, shield := d.Lives, false
	used := map[int]bool{}
	for i, m := range moves {
		if m.Ms < prev || m.Ms < 0 {
			return r, fmt.Errorf("move %d: clock went backwards", i)
		}
		prev = m.Ms
		if m.Arrow == undoTap {
			if d.Lives != 0 || len(stack) == 0 {
				return r, fmt.Errorf("move %d: nothing to take back here", i)
			}
			for _, a := range stack[len(stack)-1] {
				removed[a] = false
				r.Cleared--
				for _, c := range p.paths[a] {
					occ[c] = a
				}
			}
			stack = stack[:len(stack)-1]
			continue
		}
		if m.Arrow < 0 {
			if d.Nightmare {
				return r, fmt.Errorf("move %d: no powerups on Nightmare", i)
			}
			if used[m.Arrow] {
				return r, fmt.Errorf("move %d: powerup used twice", i)
			}
			used[m.Arrow] = true
			switch {
			case m.Arrow == puShield && d.Lives > 0:
				shield = true
			case m.Arrow == puLife && d.Lives > 0:
				lives++
			case m.Arrow == puFreeze && d.SecPerArrow > 0:
				r.Freezes++
			case m.Arrow == puScan:
			case m.Arrow == puUndo && r.Mistakes > 0:
				r.Mistakes--
			default:
				return r, fmt.Errorf("move %d: powerup %d not allowed here", i, m.Arrow)
			}
			r.Powerups = append(r.Powerups, powerupNames[m.Arrow])
			continue
		}
		if m.Arrow >= n {
			return r, fmt.Errorf("move %d: no such arrow", i)
		}
		if prevTap >= 0 {
			r.Gaps = append(r.Gaps, m.Ms-prevTap)
		}
		prevTap = m.Ms
		if removed[m.Arrow] {
			return r, fmt.Errorf("move %d: arrow %d already gone", i, m.Arrow)
		}
		blocked := all && m.Arrow == 0 && r.Cleared < n-1 || !p.free(m.Arrow, occ, removed) // full clear: pink leaves last
		if blocked {
			if shield {
				shield = false
				continue
			}
			r.Mistakes++
			if d.Lives > 0 && r.Mistakes >= lives {
				return r, fmt.Errorf("move %d: out of lives", i)
			}
			continue
		}
		group := p.cleared(m.Arrow, start, removed)
		for _, a := range group {
			removed[a] = true
			r.Cleared++
			for _, c := range p.paths[a] {
				occ[c] = -1
			}
		}
		stack = append(stack, group)
		if m.Arrow == 0 { // the pink target
			if i != len(moves)-1 {
				return r, errors.New("moves after the pink arrow was freed")
			}
			r.LastMs = m.Ms
			return r, nil
		}
	}
	return r, errors.New("pink arrow never freed")
}

// score mirrors MainActivity.win(); the time bonus uses the server's own clock so it can't be forged.
func score(r Replay, spec LevelSpec, d Difficulty, free bool, timeLimit int, serverMs int64) (scoreV, xp int) {
	perfect := r.Mistakes == 0
	secsLeft := 0
	if timeLimit > 0 {
		secsLeft = max(0, timeLimit-int(serverMs/1000))
	}
	mult := d.XPMult
	if free {
		mult = d.XPMult * float32(0.6)
	}
	perf := float32(1)
	bonus := 0
	if perfect {
		perf, bonus = 1.25, 150
	}
	xp = int(float32(r.Cleared*3+spec.N*6) * mult * perf)
	scoreV = int(float32(r.Cleared*10+spec.N*25)*d.XPMult + float32(secsLeft*5) + float32(bonus))
	return
}

// suspicion returns reasons a valid replay looks automated or clock-tampered. Flagged runs are held for review
// instead of rejected, because fast humans exist.
func suspicion(r Replay, moves []Move, timeLimit int, serverMs int64) []string {
	var why []string
	if r.LastMs > serverMs+5000 {
		why = append(why, fmt.Sprintf("client clock %dms exceeds server clock %dms", r.LastMs, serverMs))
	}
	if timeLimit > 0 && r.LastMs > int64(timeLimit)*1000+1500 {
		why = append(why, "finished after the time limit")
	}
	if serverMs < int64(len(moves))*120 || serverMs < 1500 {
		why = append(why, fmt.Sprintf("%d taps in %dms", len(moves), serverMs))
	}
	fast := 0
	for _, g := range r.Gaps {
		if g < 90 {
			fast++
		}
	}
	if fast >= 3 {
		why = append(why, fmt.Sprintf("%d taps under 90ms apart", fast))
	}
	if len(r.Gaps) >= 12 {
		s := append([]int64(nil), r.Gaps...)
		sort.Slice(s, func(i, j int) bool { return s[i] < s[j] })
		if med := s[len(s)/2]; med < 150 {
			why = append(why, fmt.Sprintf("median tap gap %dms", med))
		}
		// Humans are irregular; a bot replaying a solution on a timer is not.
		var mean, sq float64
		for _, g := range r.Gaps {
			mean += float64(g)
		}
		mean /= float64(len(r.Gaps))
		for _, g := range r.Gaps {
			sq += (float64(g) - mean) * (float64(g) - mean)
		}
		if cv := math.Sqrt(sq/float64(len(r.Gaps))) / mean; mean > 0 && cv < 0.12 {
			why = append(why, fmt.Sprintf("metronomic tapping (variation %.2f)", cv))
		}
	}
	return why
}
