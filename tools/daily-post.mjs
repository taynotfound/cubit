// Writes today's daily-cube post for the Mastodon bot (.github/workflows/daily-post.yml): the cube's number, its par
// and a link anyone can play in the browser. Run: node tools/daily-post.mjs [day]
import { createRequire } from "module";
const C = createRequire(import.meta.url)("../docs/cubit-core.js");
const day = process.argv[2] ? parseInt(process.argv[2], 10) : C.utcDay();
const p = C.generate(C.dailySpec(day));
const num = day - C.FIRST_DAILY + 1, par = C.parOf(p);
const date = new Date(day * 86400000).toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long", timeZone: "UTC" });
const lines = [
  `🟪 Cubit daily #${num} is up (${date}).`,
  `${p.paths.length} arrows on a 12×12 cube. Free the pink one in ${par} taps to hit par.`,
  ``,
  `Play it in your browser: https://taynotfound.github.io/cubit/play.html?daily=${day}`,
  `Or in the app (free, no ads, Android): https://f-droid.org/en/packages/de.taymaerz.cubit/`,
  ``,
  `Reply with your result! #Cubit #puzzle #dailypuzzle`,
];
process.stdout.write(lines.join("\n"));
