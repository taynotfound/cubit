# Getting Cubit in front of people

Drafts, ready to paste and edit. Post them one or two a week rather than all at once, and stay in the threads to answer
questions: an author who replies does better than the post itself. Always lead with something people can play or watch:
the browser link (https://taynotfound.github.io/cubit/play.html) or a reel from `docs/img/`.

Rules worth knowing before posting:
- Reddit: most subs want you to take part, not just self-promote. Check each sub's rules first; some only allow
  self-promotion on certain days or in a weekly thread. Upload the video natively rather than linking it.
- Hacker News: "Show HN" posts must be something people can try right away. The browser player covers that.
- Don't post the same text everywhere. Each draft below is written for its place.

---

## Mastodon / Fediverse (your own account)

> I made a puzzle game: a cube covered in winding arrows. Tap one and it slides off, if nothing's in its way. Dig out
> the pink one.
>
> Today's cube is the same for everyone, and you can play it right in your browser:
> https://taynotfound.github.io/cubit/play.html
>
> The Android app is free, MIT licensed, no ads, no accounts, no tracking, and on F-Droid.
> #indiedev #puzzle #FOSS #Android #FDroid #gamedev

Attach `docs/img/reel-hook.mp4` with alt text: "A cube covered in white arrows. Taps slide arrows off one by one
until the pink arrow is free and flies off."

There's also a daily bot, see `.github/workflows/daily-post.yml`. Make a separate account for it on a server whose rules allow
bots, tick "This is an automated account" in its profile settings, create an access token with the
`write:statuses` scope, and add the `MASTODON_INSTANCE` and `MASTODON_TOKEN` secrets.

---

## r/puzzles or r/puzzlevideogames

**Title:** I made a daily puzzle: free the pink arrow from a cube of 140 arrows (playable in the browser)

> Every face of the cube is covered in arrows. Tap one and it slides forward off the cube, unless another arrow is in
> the way. Arrows wrap over the edges, so you have to turn the cube and think a few moves ahead. Your goal is to clear
> a path for the pink one.
>
> There's a new cube every day, the same for everyone, with a par (the fewest taps that can win). Today's:
> https://taynotfound.github.io/cubit/play.html
>
> Post your taps below! I'd love to hear whether par feels fair.

---

## r/AndroidGaming

**Title:** [DEV] Cubit: a 3D arrow puzzle cube. Free, no ads, no IAP, open source

> Hi! I've been building Cubit, a puzzle game where a cube is covered in winding arrows and you slide them off one by
> one to free the pink one. Bigger cubes add bombs with chain reactions, two-way arrows and wormholes through the cube.
>
> - Daily cube and weekly challenge, same for everyone, with ghost races against the leader
> - The World Cube: one giant cube every player clears together, live
> - Endless runs, Nightmare and Twist modes, a calm glass-cube "Clean" mode
> - Global rankings that verify every run by replaying it on the server
> - No ads, no purchases, no account, no tracking. Under 1 MB.
>
> It isn't on the Play Store (one-person project): it's on F-Droid, Obtainium and GitHub. You can try today's cube in
> your browser first: https://taynotfound.github.io/cubit/play.html
>
> Feedback very welcome, especially on difficulty.

Attach `docs/img/reel-bombs.mp4`.

---

## r/fossdroid and r/opensource

**Title:** Cubit: an open source (MIT) puzzle game for Android. No dependencies, no AndroidX, reproducible F-Droid build

> Cubit is a puzzle game: a cube covered in arrows, and you free the pink one. It's plain Kotlin with programmatic
> Views and a hand-written 3D renderer on Canvas. No AndroidX, no Google libraries, and the F-Droid build is
> reproducible.
>
> The optional leaderboard is a small Go server with no dependencies. Instead of trusting scores, it regenerates each
> cube from its seed and replays your taps, so posting a fake score doesn't work. The level generator exists three
> times (Kotlin, Go and now JavaScript for the browser player) and fingerprint tests keep all three bit-identical.
>
> F-Droid: https://f-droid.org/en/packages/de.taymaerz.cubit/
> Source: https://github.com/taynotfound/cubit
> Try it in the browser: https://taynotfound.github.io/cubit/play.html

---

## Hacker News

**Title:** Show HN: Cubit – free the pink arrow from a cube of arrows (daily puzzle, plays in browser)

**URL:** https://taynotfound.github.io/cubit/play.html

**First comment (post it yourself right away):**

> I built Cubit as an Android puzzle game and just added a browser player so people without Android can try the daily
> cube.
>
> A few things that might interest HN:
> - Levels are generated in reverse: each new arrow's way out must be clear of every arrow already placed, so the cube
>   is always solvable, and the generator rerolls until enough arrows bury the pink one.
> - The generator runs bit-identically in Kotlin (app), Go (leaderboard) and JavaScript (browser), using SplitMix64 and
>   integer maths only, pinned by golden fingerprint tests in all three.
> - The leaderboard doesn't trust clients: it regenerates the cube from its seed and replays every tap to score the run.
> - The app has no dependencies beyond the Android SDK and is under 1 MB.
>
> Happy to answer questions about any of it.

---

## Lemmy (e.g. !android@lemdro.id, !opensource@lemmy.ml, !gaming@lemmy.world)

Reuse the r/fossdroid text; Lemmy communities tend to like the no-tracking, no-dependencies angle.

---

## People to tell

- **IzzyOnDroid** and F-Droid's "This Week in F-Droid": mention the new browser player and the latest release.
- **YouTube channels that review F-Droid or FOSS apps**: send a short email with the reel and the browser link.
- **Puzzle newsletters and daily-puzzle lists** (sites that collect Wordle-likes): Cubit's daily cube with a text
  result to share fits right in. Search for "daily puzzle games list" and ask to be added.
- **itch.io**: a free web build page (embed play.html in an iframe) puts Cubit in front of itch's browser-game crowd.
