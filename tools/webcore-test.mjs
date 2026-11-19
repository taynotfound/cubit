// Checks docs/cubit-core.js against the golden fingerprints the app (PuzzleTest.kt) and server (puzzle_test.go) pin,
// so the web player builds the very same cubes. Run: node tools/webcore-test.mjs
import { createRequire } from "module";
const require = createRequire(import.meta.url);
const C = require("../docs/cubit-core.js");

function fingerprint(p) {
  let h = 14695981039346656037n;
  const mix = v => { h = BigInt.asUintN(64, (h ^ BigInt.asUintN(64, BigInt(v))) * 1099511628211n); };
  p.paths.forEach((path, i) => { path.forEach(c => mix(c)); mix(1000 + p.dirs[i]); mix(0xFFFF); });
  return BigInt.asIntN(64, h);
}
const level = l => ({ n: Math.min(18, 10 + Math.floor((l - 1) / 3)), maxLen: Math.min(16, 8 + Math.floor(l / 4)), seed: BigInt(l) * 7919n + 17n });
const endless = (seed, k) => ({ n: Math.min(14, 7 + k), maxLen: Math.min(12, 5 + Math.floor(k / 2)), seed: seed + BigInt(k) * 7777n });
let bad = 0;
const check = (what, got, want) => { if (got !== want) { bad++; console.log(`FAIL ${what}: ${got} != ${want}`); } else console.log(`ok   ${what}`); };

const specs = [level(1), level(7), level(30), { n: 2, maxLen: 2, seed: 123n }, { n: 14, maxLen: 20, seed: -987654321n }, C.dailySpec(20394), C.weekSpec(2965)];
const want = [-7336180328909340747n, -3800580958801716592n, -1263255144737735575n, -2333106797818041265n, 1859335250571087320n, -7805004108394037706n, 2451205338930652789n];
specs.forEach((s, i) => check(`fingerprint ${i}`, fingerprint(C.generate(s)), want[i]));

const sspecs = [level(4), level(30), { n: 14, maxLen: 20, seed: -987654321n }, endless(BigInt(20800) * 3000017n + 9n, 6)];
const swant = [-2168532955706612572n, 893908734425870072n, 8367443714046685816n, 776641724789738145n];
sspecs.forEach((s, i) => {
  const p = C.generate({ ...s, special: true, locks: true });
  let h = 14695981039346656037n;
  const mix = v => { h = BigInt.asUintN(64, (h ^ BigInt.asUintN(64, BigInt(v))) * 1099511628211n); };
  for (let a = 0; a < p.paths.length; a++) {
    if (p.bombs[a]) { mix(a); [...p.blast(a)].sort((x, y) => x - y).forEach(x => mix(10000 + x)); }
    if (p.keys[a] >= 0) { mix(20000 + a); mix(30000 + p.keys[a]); }
    if (p.twoWay[a]) { mix(40000 + a); p.tailExit(a).forEach(c => mix(50000 + c)); }
  }
  for (let c = 0; c < p.warp.length; c++) if (p.warp[c] > c) { mix(60000 + c); mix(70000 + p.warp[c]); }
  check(`specials ${i}`, BigInt.asIntN(64, h), swant[i]);
});
check("name seed", C.nameSeed("  Tay "), C.nameSeed("tay"));
console.log(bad ? `${bad} failed` : "all match");
process.exit(bad ? 1 : 0);
