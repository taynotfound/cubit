// Writes today's daily-cube post for the Mastodon bot (.github/workflows/daily-post.yml): the cube's number, its par
// and a link anyone can play in the browser. Run: node tools/daily-post.mjs [day]
// With --day it prints just the day number; with --alt, the alt text for the picture of the cube (play.html?card).
import { createRequire } from "module";
const C = createRequire(import.meta.url)("../docs/cubit-core.js");
const arg = process.argv.slice(2).find(a => /^\d+$/.test(a));
const day = arg ? parseInt(arg, 10) : C.utcDay();
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
// 1–24 December: the app's advent calendar opens a door each day (Advent.kt), so the post says which.
const when = new Date(day * 86400000);
if (when.getUTCMonth() === 11 && when.getUTCDate() <= 24) {
  const d = when.getUTCDate();
  lines.splice(2, 0, ``, d === 24 ? `🎄 The last advent door is open in the app: door 24, the big one.` : `🎄 Advent door ${d} of 24 is open in the app too.`);
  lines[lines.length - 1] += " #adventcalendar";
}
if (process.argv.includes("--day")) process.stdout.write(String(day));
else if (process.argv.includes("--alt")) process.stdout.write(`Cubit daily cube #${num}: a blue cube seen from above one corner, every face covered in ` +
  `winding white arrows, with one pink arrow on the front face. Text: "Daily cube #${num}. Par ${par}, ${p.paths.length} arrows, 12 by 12, ` +
  `the same cube for everyone. Free the pink arrow. Play free in your browser."`);
else process.stdout.write(lines.join("\n"));
