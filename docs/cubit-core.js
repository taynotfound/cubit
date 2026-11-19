// Cubit's level generator and rules for the browser: a port of app/src/main/java/com/cubit/game/Puzzle.kt.
// It must build bit-identical cubes to the app and to server/puzzle.go, so shared links show the same cube everywhere.
// tools/webcore-test.mjs checks it against the golden fingerprints in PuzzleTest.kt and server/puzzle_test.go.
// Nightmare (lap) cubes are left out: the web player sends those to the app.
(function (root) {
  "use strict";
  const M = (1n << 64n) - 1n;
  const DX = [1, -1, 0, 0, 0, 0], DY = [0, 0, 1, -1, 0, 0], DZ = [0, 0, 0, 0, 1, -1];
  const opposite = d => d ^ 1;
  const STUCK = 0, HEAD = 1, TAIL = 2;
  const i64 = v => BigInt.asIntN(64, BigInt(v));

  /** SplitMix64, as Rng in Puzzle.kt. Seeds are BigInt. */
  function Rng(seed) {
    let s = BigInt.asUintN(64, seed);
    const next = () => {
      s = (s + 0x9E3779B97F4A7C15n) & M; let z = s;
      z = ((z ^ (z >> 30n)) * 0xBF58476D1CE4E5B9n) & M;
      z = ((z ^ (z >> 27n)) * 0x94D049BB133111EBn) & M;
      return z ^ (z >> 31n);
    };
    const nextInt = b => Number((next() >> 33n) % BigInt(b));
    const shuffle = xs => { for (let i = xs.length - 1; i >= 1; i--) { const j = nextInt(i + 1); const t = xs[i]; xs[i] = xs[j]; xs[j] = t; } return xs; };
    return { nextInt, shuffle };
  }

  function Cube(n) {
    const size = 6 * n * n, px = new Int32Array(size), py = new Int32Array(size), pz = new Int32Array(size), face = new Int32Array(size);
    const index = new Map(), key = (x, y, z) => ((x + 512) << 20) | ((y + 512) << 10) | (z + 512);
    for (let f = 0, i = 0; f < 6; f++) {
      const a = f >> 1, s = f % 2 === 0 ? n : -n;
      for (let u = -n + 1; u < n; u += 2) for (let v = -n + 1; v < n; v += 2, i++) {
        const c = [0, 0, 0]; c[a] = s; c[(a + 1) % 3] = u; c[(a + 2) % 3] = v;
        px[i] = c[0]; py[i] = c[1]; pz[i] = c[2]; face[i] = f; index.set(key(c[0], c[1], c[2]), i);
      }
    }
    const cellAt = (x, y, z) => { const v = index.get(key(x, y, z)); return v === undefined ? -1 : v; };
    const tangents = c => [0, 1, 2, 3, 4, 5].filter(d => d >> 1 !== face[c] >> 1);
    const step = (c, d) => {
      const x = px[c] + 2 * DX[d], y = py[c] + 2 * DY[d], z = pz[c] + 2 * DZ[d];
      if (x * DX[d] + y * DY[d] + z * DZ[d] > n) {
        const f = face[c];
        return cellAt(px[c] + DX[d] - DX[f], py[c] + DY[d] - DY[f], pz[c] + DZ[d] - DZ[f]) * 8 + opposite(f);
      }
      return cellAt(x, y, z) * 8 + d;
    };
    const ray = (c, d) => { const out = []; for (;;) { const s = step(c, d); if (s % 8 !== d) return out; c = s >> 3; out.push(c); } };
    const antipode = c => { const v = [px[c], py[c], pz[c]]; v[face[c] >> 1] = -v[face[c] >> 1]; return cellAt(v[0], v[1], v[2]); };
    return { n, size, px, py, pz, face, cellAt, tangents, step, ray, antipode };
  }

  /** A puzzle: paths run tail → head, dirs[a] is the head's way, arrow 0 is the pink one. Mirrors Puzzle in Puzzle.kt. */
  function Puzzle(cube, paths, dirs, bombs, keys, twoWay, warp) {
    const N = paths.length;
    bombs = bombs || new Array(N).fill(false);
    keys = keys || new Array(N).fill(-1);
    twoWay = twoWay || new Array(N).fill(false);
    warp = warp || new Int32Array(cube.size).fill(-1);
    const warped = (cells, d) => {
      for (let i = 0; i < cells.length; i++) { const q = warp[cells[i]]; if (q >= 0) return cells.slice(0, i + 1).concat([q], cube.ray(q, d)); }
      return cells;
    };
    const exit = a => warped(cube.ray(paths[a][paths[a].length - 1], dirs[a]), dirs[a]);
    const tailDir = a => { const p = paths[a]; return opposite(cube.tangents(p[0]).find(t => cube.step(p[0], t) >> 3 === p[1])); };
    const tailExit = a => warped(cube.ray(paths[a][0], tailDir(a)), tailDir(a));
    let heads = null, tails = null, owner = null;
    const way = (a, occ, gone) => {
      if (!heads) heads = paths.map((_, i) => exit(i));
      if (!tails) tails = paths.map((_, i) => twoWay[i] ? tailExit(i) : []);
      if (keys[a] >= 0 && !gone[keys[a]]) return STUCK;
      if (heads[a].every(c => occ[c] < 0 || occ[c] === a)) return HEAD;
      if (twoWay[a] && tails[a].every(c => occ[c] < 0 || occ[c] === a)) return TAIL;
      return STUCK;
    };
    /** The cells in the way of [a]'s head (or of its tail, for a two-way arrow whose head is blocked). */
    const blockers = (a, occ) => { if (!heads) heads = paths.map((_, i) => exit(i)); return heads[a].filter(c => occ[c] >= 0 && occ[c] !== a).map(c => occ[c]); };
    const blast = a => {
      if (!bombs[a]) return [];
      if (!owner) { owner = new Int32Array(cube.size).fill(-1); paths.forEach((p, i) => p.forEach(c => owner[c] = i)); }
      const out = new Set();
      for (const c of paths[a]) for (const d of cube.tangents(c)) { const b = owner[cube.step(c, d) >> 3]; if (b > 0 && b !== a) out.add(b); }
      return [...out];
    };
    const cleared = (a, gone) => {
      const out = [a], seen = new Set([a]);
      for (let i = 0; i < out.length; i++) for (const x of blast(out[i])) if (!gone[x] && !seen.has(x)) { seen.add(x); out.push(x); }
      return out;
    };
    return { cube, paths, dirs, bombs, keys, twoWay, warp, exit, tailDir, tailExit, way, blockers, blast, cleared };
  }

  function solvable(p) {
    const occ = new Int32Array(p.cube.size).fill(-1); p.paths.forEach((path, i) => path.forEach(c => occ[c] = i));
    const gone = new Array(p.paths.length).fill(false); let left = p.paths.length;
    while (left > 0) {
      let moved = false;
      for (let a = 0; a < p.paths.length; a++) if (!gone[a] && p.way(a, occ, gone) !== STUCK) { gone[a] = true; left--; moved = true; p.paths[a].forEach(c => occ[c] = -1); }
      if (!moved) return false;
    }
    return true;
  }

  const BOMB_EVERY = 30, LOCK_EVERY = 40, TWO_WAY_EVERY = 25, MAX_WORMHOLES = 3;

  function specialsFor(n, seed) {
    const rb = Rng(seed ^ 0x426F6D62n);
    const bombs = Array.from({ length: n }, (_, i) => i > 0 && rb.nextInt(BOMB_EVERY) === 0);
    const rl = Rng(seed ^ 0x4C6F636Bn), keys = new Array(n).fill(-1);
    for (let i = 1; i < n - 1; i++) if (rl.nextInt(LOCK_EVERY) === 0 && !bombs[i]) keys[i] = i + 1 + rl.nextInt(Math.min(30, n - 1 - i));
    const rt = Rng(seed ^ 0x54776F57n), two = new Array(n).fill(false);
    for (let i = 1; i < n; i++) if (rt.nextInt(TWO_WAY_EVERY) === 0 && !bombs[i] && keys[i] < 0) two[i] = true;
    return { bombs, keys, two };
  }

  function wormholesFor(p, seed) {
    const owner = new Int32Array(p.cube.size).fill(-1); p.paths.forEach((path, i) => path.forEach(c => owner[c] = i));
    const free = []; for (let c = 0; c < p.cube.size; c++) if (owner[c] < 0) free.push(c);
    const warp = new Int32Array(p.cube.size).fill(-1);
    if (!free.length) return warp;
    const r = Rng(seed ^ 0x576F726Dn), pairs = [];
    for (let t = 0; t < 40; t++) {
      if (pairs.length === MAX_WORMHOLES) break;
      const c = free[r.nextInt(free.length)], q = p.cube.antipode(c);
      if (q >= 0 && q !== c && owner[q] < 0 && warp[c] < 0 && warp[q] < 0) { warp[c] = q; warp[q] = c; pairs.push(c); }
    }
    while (pairs.length && !solvable(Puzzle(p.cube, p.paths, p.dirs, p.bombs, p.keys, p.twoWay, warp))) {
      const c = pairs.pop(); warp[warp[c]] = -1; warp[c] = -1;
    }
    return warp;
  }

  /** Free cells as a Fenwick tree, as FreeCells in Puzzle.kt. */
  function FreeCells(size) {
    const fen = new Int32Array(size + 1); let count = size;
    for (let i = 1; i <= size; i++) { fen[i] += 1; const j = i + (i & -i); if (j <= size) fen[j] += fen[i]; }
    let top = 1; while (top * 2 <= size) top *= 2;
    return {
      get count() { return count; },
      take(c) { for (let i = c + 1; i <= size; i += i & -i) fen[i]--; count--; },
      kth(k) { let pos = 0, rem = k + 1; for (let s = top; s > 0; s >>= 1) { const nx = pos + s; if (nx <= size && fen[nx] < rem) { pos = nx; rem -= fen[nx]; } } return pos; },
    };
  }

  function build(n, maxLen, seed) {
    const cube = Cube(n), rnd = Rng(seed), occ = new Int32Array(cube.size).fill(-1), paths = [], dirs = [];
    let filled = 0, fails = 0;
    const goal = Math.floor(cube.size * 92 / 100), mid = -n + 1 + 2 * (n >> 1), center = cube.cellAt(mid, mid, n), free = FreeCells(cube.size);
    while (filled < goal && fails < 400) {
      if (free.count < 2) break;
      const start = paths.length ? free.kth(rnd.nextInt(free.count)) : center;
      const len = paths.length ? 2 + rnd.nextInt(maxLen - 1) : Math.max(4, n + 1);
      const path = [start], inPath = new Set([start]);
      let cur = start; const t0 = cube.tangents(start); let dir = t0[rnd.nextInt(t0.length)], firstDir = dir, arrive = dir;
      for (let i = 1; i < len; i++) {
        const opts = rnd.shuffle(cube.tangents(cur).filter(d => d !== opposite(dir)));
        if (rnd.nextInt(4) === 0) { const k = opts.indexOf(dir); if (k >= 0) { opts.splice(k, 1); opts.unshift(dir); } }
        let moved = false;
        for (const d of opts) {
          const s = cube.step(cur, d), nc = Math.floor(s / 8);
          if (nc < 0 || occ[nc] >= 0 || inPath.has(nc)) continue;
          path.push(nc); inPath.add(nc); cur = nc; dir = arrive = s % 8;
          if (i === 1) firstDir = d;
          moved = true; break;
        }
        if (!moved) break;
      }
      if (path.length < 2) { fails++; continue; }
      const options = rnd.shuffle([[path, arrive], [[...path].reverse(), opposite(firstDir)]]);
      const pick = options.find(([p, d]) => cube.ray(p[p.length - 1], d).every(c => occ[c] < 0 && !inPath.has(c)));
      if (!pick) { fails++; continue; }
      const id = paths.length; paths.push(pick[0]); dirs.push(pick[1]);
      for (const c of pick[0]) { occ[c] = id; free.take(c); }
      filled += pick[0].length; fails = 0;
    }
    return Puzzle(cube, paths, dirs);
  }

  function mustClear(p) {
    const occ = new Int32Array(p.cube.size).fill(-1); p.paths.forEach((path, i) => path.forEach(c => occ[c] = i));
    const seen = new Array(p.paths.length).fill(false); seen[0] = true;
    const q = [0]; let k = 0;
    for (let h = 0; h < q.length; h++) for (const c of p.exit(q[h])) { const b = occ[c]; if (b >= 0 && !seen[b]) { seen[b] = true; k++; q.push(b); } }
    return k;
  }

  /** spec: { n, maxLen, seed (BigInt or number), special, locks }. Mirrors generate in Puzzle.kt, without lap cubes.
   *  locks only reproduces 1.7.0–1.7.3 cubes for the tests: no cube played today has them. */
  function generate(spec) {
    const seed = i64(spec.seed), { n, maxLen } = spec;
    const need = p => Math.floor(p.paths.length * (10 + Math.max(0, maxLen - 8)) / 80);
    let best = build(n, maxLen, seed), bestDepth = mustClear(best);
    for (let k = 1n; bestDepth < need(best) && k <= 10n; k++) {
      const p = build(n, maxLen, i64(seed + k * 1000003n)), d = mustClear(p);
      if (d > bestDepth) { best = p; bestDepth = d; }
    }
    if (!spec.special) return best;
    const { bombs, keys, two } = specialsFor(best.paths.length, seed);
    const warp = wormholesFor(Puzzle(best.cube, best.paths, best.dirs, bombs, keys, two), seed);
    return Puzzle(best.cube, best.paths, best.dirs, bombs, spec.locks ? keys : new Array(keys.length).fill(-1), two, warp);
  }

  // The same specs as Puzzle.kt.
  const FIRST_DAILY = 20759;
  const utcDay = () => Math.floor(Date.now() / 86400000);
  const dailySpec = day => ({ n: 12, maxLen: 10, seed: BigInt(day) * 1000003n + 77n, special: false });
  const weekSpec = week => ({ n: 16, maxLen: 12, seed: BigInt(week) * 7000003n + 5n, special: false });
  const utcWeek = (day = utcDay()) => Math.floor((day + 3) / 7);
  function nameSeed(name) {
    let h = -3750763034362895579n;
    for (const b of new TextEncoder().encode(name.trim().toLowerCase())) h = BigInt.asIntN(64, (h ^ BigInt(b)) * 1099511628211n);
    return h;
  }
  const nameSpec = name => ({ n: 12, maxLen: 10, seed: nameSeed(name), special: true });

  /** Fewest taps that win: the arrows that must go before the pink one, plus the pink one. Not the app's exact par
   *  (bombs can save taps there), but the same for daily cubes, which have none. */
  const parOf = p => mustClear(p) + 1;

  const api = { DX, DY, DZ, STUCK, HEAD, TAIL, Rng, Cube, Puzzle, generate, mustClear, solvable, parOf,
    FIRST_DAILY, utcDay, dailySpec, weekSpec, utcWeek, nameSeed, nameSpec };
  if (typeof module !== "undefined" && module.exports) module.exports = api; else root.CubitCore = api;
})(typeof self !== "undefined" ? self : this);
