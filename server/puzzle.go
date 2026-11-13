package main

// Port of app/src/main/java/com/cubit/game/Puzzle.kt. The server regenerates a run's puzzle to replay it,
// so this must stay bit-identical to the app: puzzle_test.go pins the same fingerprints as the Kotlin tests.

var (
	dx = [6]int{1, -1, 0, 0, 0, 0}
	dy = [6]int{0, 0, 1, -1, 0, 0}
	dz = [6]int{0, 0, 0, 0, 1, -1}
)

func opposite(d int) int { return d ^ 1 }

type Cube struct {
	n, size        int
	px, py, pz, fc []int
	index          map[int]int
}

func cubeKey(x, y, z int) int { return ((x + 512) << 20) | ((y + 512) << 10) | (z + 512) }

func newCube(n int) *Cube {
	c := &Cube{n: n, size: 6 * n * n, index: make(map[int]int, 12*n*n)}
	c.px, c.py, c.pz, c.fc = make([]int, c.size), make([]int, c.size), make([]int, c.size), make([]int, c.size)
	i := 0
	for f := 0; f < 6; f++ {
		a := f / 2
		s := n
		if f%2 == 1 {
			s = -n
		}
		for u := -n + 1; u < n; u += 2 {
			for v := -n + 1; v < n; v += 2 {
				var p [3]int
				p[a], p[(a+1)%3], p[(a+2)%3] = s, u, v
				c.px[i], c.py[i], c.pz[i], c.fc[i] = p[0], p[1], p[2], f
				c.index[cubeKey(p[0], p[1], p[2])] = i
				i++
			}
		}
	}
	return c
}

func (c *Cube) cellAt(x, y, z int) int {
	if v, ok := c.index[cubeKey(x, y, z)]; ok {
		return v
	}
	return -1
}

func (c *Cube) tangents(cell int) []int {
	out := make([]int, 0, 4)
	for d := 0; d < 6; d++ {
		if d/2 != c.fc[cell]/2 {
			out = append(out, d)
		}
	}
	return out
}

// step returns newCell*8 + newDirection.
func (c *Cube) step(cell, d int) int {
	x, y, z := c.px[cell]+2*dx[d], c.py[cell]+2*dy[d], c.pz[cell]+2*dz[d]
	if x*dx[d]+y*dy[d]+z*dz[d] > c.n {
		f := c.fc[cell]
		return c.cellAt(c.px[cell]+dx[d]-dx[f], c.py[cell]+dy[d]-dy[f], c.pz[cell]+dz[d]-dz[f])*8 + opposite(f)
	}
	return c.cellAt(x, y, z)*8 + d
}

func (c *Cube) ray(cell, d int) []int {
	var out []int
	cur := cell
	for {
		s := c.step(cur, d)
		if s%8 != d {
			return out
		}
		cur = s / 8
		out = append(out, cur)
	}
}

// lap mirrors Cube.lap in Puzzle.kt: every cell once around the cube from [cell] in [d], over the edges and back.
func (c *Cube) lap(cell, d int) []int {
	var out []int
	cur := cell
	for {
		s := c.step(cur, d)
		cur, d = s/8, s%8
		if cur == cell {
			return out
		}
		out = append(out, cur)
	}
}

// exit mirrors Cube.exit in Puzzle.kt: the cells an arrow's head crosses on its way out. Normally just the run to
// the face edge; on a lap cube (Nightmare) a whole lap round the cube first, back over its own start, then that run.
func (c *Cube) exit(cell, d int, lap bool) []int {
	if !lap {
		return c.ray(cell, d)
	}
	return append(append(c.lap(cell, d), cell), c.ray(cell, d)...)
}

type Puzzle struct {
	cube  *Cube
	paths [][]int
	dirs  []int
	lap   bool
	// Special arrows (nil unless the spec asks for them): bombs, locks (keys[a] must be gone first, -1 for none)
	// and two-way arrows. See specialsFor.
	bombs  []bool
	keys   []int
	twoWay []bool
}

type LevelSpec struct {
	N, MaxLen int
	Seed      int64
	Lap       bool `json:",omitempty"` // Nightmare: arrows lap the whole cube before they leave
	Special   bool `json:",omitempty"` // bombs, locked and two-way arrows (app 1.7+, see specialsFor)
}

// dailySpec mirrors dailySpec() in Puzzle.kt: one cube for everyone per UTC day (days since 1970).
// weekSpec mirrors weekSpec() in Puzzle.kt: the weekly challenge cube. Weeks count from 1970 and start on Mondays.
func weekSpec(week int) LevelSpec {
	return LevelSpec{N: 16, MaxLen: 12, Seed: int64(week)*7_000_003 + 5}
}

func utcWeek(day int) int { return (day + 3) / 7 }

func dailySpec(day int) LevelSpec {
	return LevelSpec{N: 12, MaxLen: 10, Seed: int64(day)*1_000_003 + 77}
}

func specForLevel(level int) LevelSpec {
	return LevelSpec{N: min(10+(level-1)/3, 18), MaxLen: min(8+level/4, 16), Seed: int64(level)*7919 + 17}
}

// Rng is SplitMix64, identical to Rng in Puzzle.kt.
type Rng struct{ s uint64 }

func (r *Rng) next() uint64 {
	r.s += 0x9E3779B97F4A7C15
	z := r.s
	z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9
	z = (z ^ (z >> 27)) * 0x94D049BB133111EB
	return z ^ (z >> 31)
}

func (r *Rng) nextInt(bound int) int { return int((r.next() >> 33) % uint64(bound)) }

func shuffle[T any](r *Rng, xs []T) {
	for i := len(xs) - 1; i >= 1; i-- {
		j := r.nextInt(i + 1)
		xs[i], xs[j] = xs[j], xs[i]
	}
}

// generate mirrors generate() in Puzzle.kt: up to 10 rerolls until enough arrows stand between the pink one and
// the edge (an eighth at first, over a fifth as arrows lengthen with the level; a third on Nightmare's sparse cubes).
func generate(spec LevelSpec) *Puzzle {
	best := build(spec)
	bestDepth := mustClear(best)
	need := func(p *Puzzle) int {
		if spec.Lap {
			return len(p.paths) / 3
		}
		return len(p.paths) * (10 + max(0, spec.MaxLen-8)) / 80
	}
	for k := int64(1); bestDepth < need(best) && k <= 10; k++ {
		p := build(LevelSpec{N: spec.N, MaxLen: spec.MaxLen, Seed: spec.Seed + k*1_000_003, Lap: spec.Lap})
		if d := mustClear(p); d > bestDepth {
			best, bestDepth = p, d
		}
	}
	if spec.Special && !spec.Lap {
		best.bombs, best.keys, best.twoWay = specialsFor(len(best.paths), spec.Seed)
	}
	return best
}

// endlessSpec mirrors endlessSpec in Puzzle.kt: cube k (from 0) of an endless run.
func endlessSpec(seed int64, k int) LevelSpec {
	return LevelSpec{N: min(7+k, 14), MaxLen: min(5+k/2, 12), Seed: seed + int64(k)*7_777}
}

// dailyEndlessSeed mirrors dailyEndlessSeed in Puzzle.kt.
func dailyEndlessSeed(day int) int64 { return int64(day)*3_000_017 + 9 }

// Mirror BOMB_EVERY, LOCK_EVERY and TWO_WAY_EVERY in Puzzle.kt.
const (
	bombEvery   = 30
	lockEvery   = 40
	twoWayEvery = 25
)

// specialsFor mirrors specialsFor in Puzzle.kt. Each kind has its own RNG stream and is laid on after the cube is
// built, so special arrows never change the arrows themselves. The pink arrow (0) is never special. A lock's key is
// placed later than the arrow it locks, so in the generator's reverse order the key goes first: still solvable.
func specialsFor(n int, seed int64) (bombs []bool, keys []int, two []bool) {
	rb := &Rng{uint64(seed ^ 0x426F6D62)}
	bombs = make([]bool, n)
	for i := 1; i < n; i++ {
		bombs[i] = rb.nextInt(bombEvery) == 0
	}
	rl := &Rng{uint64(seed ^ 0x4C6F636B)}
	keys = make([]int, n)
	for i := range keys {
		keys[i] = -1
	}
	for i := 1; i < n-1; i++ {
		if rl.nextInt(lockEvery) == 0 && !bombs[i] {
			keys[i] = i + 1 + rl.nextInt(min(30, n-1-i))
		}
	}
	rt := &Rng{uint64(seed ^ 0x54776F57)}
	two = make([]bool, n)
	for i := 1; i < n; i++ {
		if rt.nextInt(twoWayEvery) == 0 && !bombs[i] && keys[i] < 0 {
			two[i] = true
		}
	}
	return
}

// tailExit mirrors Puzzle.tailExit: a two-way arrow's way out tail first, back against its body's first step.
func (p *Puzzle) tailExit(a int) []int {
	path := p.paths[a]
	for _, d := range p.cube.tangents(path[0]) {
		if p.cube.step(path[0], d)/8 == path[1] {
			return p.cube.exit(path[0], opposite(d), p.lap)
		}
	}
	return nil
}

// free mirrors Puzzle.way (as a yes/no): whether arrow a can leave the board occ, with removed arrows gone. Its
// own cells never block it. The full-clear rule is the caller's.
func (p *Puzzle) free(a int, occ []int, gone []bool) bool {
	if p.keys != nil && p.keys[a] >= 0 && !gone[p.keys[a]] {
		return false
	}
	clear := func(cells []int) bool {
		for _, c := range cells {
			if occ[c] >= 0 && occ[c] != a {
				return false
			}
		}
		return true
	}
	path := p.paths[a]
	return clear(p.cube.exit(path[len(path)-1], p.dirs[a], p.lap)) || p.twoWay != nil && p.twoWay[a] && clear(p.tailExit(a))
}

// cleared mirrors Puzzle.cleared: a, then its blast, then the blasts of bombs caught in it, skipping gone arrows.
// start is the untouched board.
func (p *Puzzle) cleared(a int, start []int, gone []bool) []int {
	out := []int{a}
	seen := map[int]bool{a: true}
	for i := 0; i < len(out); i++ {
		for _, x := range p.blast(out[i], start) {
			if !gone[x] && !seen[x] {
				seen[x] = true
				out = append(out, x)
			}
		}
	}
	return out
}

// blast mirrors Puzzle.blast in Puzzle.kt: the arrows bomb [a] takes with it when it leaves, every other arrow
// with a cell next to one of its cells, never the pink one. occ is the starting board (no arrows removed).
func (p *Puzzle) blast(a int, occ []int) []int {
	if p.bombs == nil || !p.bombs[a] {
		return nil
	}
	seen := map[int]bool{}
	var out []int
	for _, c := range p.paths[a] {
		for _, d := range p.cube.tangents(c) {
			if b := occ[p.cube.step(c, d)/8]; b > 0 && b != a && !seen[b] {
				seen[b] = true
				out = append(out, b)
			}
		}
	}
	return out
}

// mustClear counts the arrows that have to go before the pink one is free: everything on its way out, everything
// on their ways out, and so on.
func mustClear(p *Puzzle) int {
	occ := make([]int, p.cube.size)
	for i := range occ {
		occ[i] = -1
	}
	for i, path := range p.paths {
		for _, c := range path {
			occ[c] = i
		}
	}
	seen := make([]bool, len(p.paths))
	seen[0] = true
	queue := []int{0}
	n := 0
	for len(queue) > 0 {
		a := queue[0]
		queue = queue[1:]
		path := p.paths[a]
		for _, c := range p.cube.exit(path[len(path)-1], p.dirs[a], p.lap) {
			if b := occ[c]; b >= 0 && !seen[b] {
				seen[b] = true
				n++
				queue = append(queue, b)
			}
		}
	}
	return n
}

func build(spec LevelSpec) *Puzzle {
	cube := newCube(spec.N)
	rnd := &Rng{uint64(spec.Seed)}
	occ := make([]int, cube.size)
	for i := range occ {
		occ[i] = -1
	}
	var paths [][]int
	var dirs []int
	filled, fails := 0, 0
	goal := cube.size * 92 / 100
	mid := -spec.N + 1 + 2*(spec.N/2)
	center := cube.cellAt(mid, mid, spec.N)

	free := newFreeCells(cube.size)
	maxFails := 400
	if spec.Lap {
		maxFails = 2000 // a clear lap is rare once a few arrows are down: keep trying longer
	}
	for filled < goal && fails < maxFails {
		if free.count < 2 {
			break
		}
		start, length := center, max(4, spec.N+1)
		if len(paths) > 0 {
			start = free.kth(rnd.nextInt(free.count))
			length = 2 + rnd.nextInt(spec.MaxLen-1)
		}
		path := []int{start}
		inPath := map[int]bool{start: true}
		cur := start
		t := cube.tangents(start)
		dir := t[rnd.nextInt(len(t))]
		firstDir, arrive := dir, dir
		for i := 1; i < length; i++ {
			var opts []int
			for _, d := range cube.tangents(cur) {
				if d != opposite(dir) {
					opts = append(opts, d)
				}
			}
			shuffle(rnd, opts)
			if rnd.nextInt(4) == 0 { // rarely prefer straight on: arrows wind
				for k, d := range opts {
					if d == dir {
						opts = append([]int{dir}, append(opts[:k:k], opts[k+1:]...)...)
						break
					}
				}
			}
			moved := false
			for _, d := range opts {
				s := cube.step(cur, d)
				nc := s / 8
				if nc < 0 || occ[nc] >= 0 || inPath[nc] {
					continue
				}
				path = append(path, nc)
				inPath[nc] = true
				cur, dir, arrive = nc, s%8, s%8
				if i == 1 {
					firstDir = d
				}
				moved = true
				break
			}
			if !moved {
				break
			}
		}
		if len(path) < 2 {
			fails++
			continue
		}

		rev := make([]int, len(path))
		for i, c := range path {
			rev[len(path)-1-i] = c
		}
		type option struct {
			p []int
			d int
		}
		options := []option{{path, arrive}, {rev, opposite(firstDir)}}
		shuffle(rnd, options)
		picked := -1
		for oi, o := range options {
			ok := true
			for _, c := range cube.exit(o.p[len(o.p)-1], o.d, spec.Lap) {
				// A lap may cross the arrow's own body: it slides along behind its head, so that's no block.
				if occ[c] >= 0 || inPath[c] && !spec.Lap {
					ok = false
					break
				}
			}
			if ok {
				picked = oi
				break
			}
		}
		if picked < 0 {
			fails++
			continue
		}
		id := len(paths)
		paths = append(paths, options[picked].p)
		dirs = append(dirs, options[picked].d)
		for _, c := range options[picked].p {
			occ[c] = id
			free.take(c)
		}
		filled += len(options[picked].p)
		fails = 0
	}
	return &Puzzle{cube: cube, paths: paths, dirs: dirs, lap: spec.Lap}
}

// freeCells mirrors FreeCells in Puzzle.kt: a Fenwick tree over empty cells, so picking the k-th empty cell in index
// order is O(log n) rather than a scan per arrow.
type freeCells struct {
	fen   []int
	count int
}

func newFreeCells(size int) *freeCells {
	f := &freeCells{fen: make([]int, size+1), count: size}
	for i := 1; i <= size; i++ {
		f.fen[i]++
		if j := i + (i & -i); j <= size {
			f.fen[j] += f.fen[i]
		}
	}
	return f
}

func (f *freeCells) take(c int) {
	for i := c + 1; i < len(f.fen); i += i & -i {
		f.fen[i]--
	}
	f.count--
}

func (f *freeCells) kth(k int) int {
	size := len(f.fen) - 1
	step := 1
	for step*2 <= size {
		step *= 2
	}
	pos, rem := 0, k+1
	for ; step > 0; step /= 2 {
		if nx := pos + step; nx <= size && f.fen[nx] < rem {
			pos = nx
			rem -= f.fen[nx]
		}
	}
	return pos
}
