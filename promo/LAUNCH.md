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

## Mastodon / Fediverse

**Where to sign up** (checked 19 Nov 2026 from each server's own rules):

- **Cubit's account: mastodon.gamedev.place.** A server for game developers (about 4,300 active people a month, the
  official itch.io account lives there). Its rules have nothing against sharing your own game or automated posts.
  Sign-ups need approval, so say in the request that it's the account for Cubit, a free open source puzzle game, and
  that it will also post the daily cube automatically. The admin's contact is on the server's About page.
- **Backup: mastodon.social.** The biggest server (about 265,000 active a month), open sign-up, run by Mastodon's own
  non-profit. Its rules ask that generative AI use is disclosed and that accounts don't post only AI-generated content.
  Cubit's posts aren't AI-generated, but the game is built with Claude, so link AI-DECLARATION.md in the profile.
- **Avoid:** fosstodon.org (closed to sign-ups, and its rules forbid automated posting from an account nobody watches,
  and posts that are only links and hashtags). botsin.space, the old home for bots, looks shut: its site no longer serves a Mastodon server.

One account for everything works well: you post as a person, and the daily cube goes out from the same account. Tick
"This is an automated account" only if the account is just the bot; a mixed account is fine without it as long as you
read and answer replies. Then set the `MASTODON_INSTANCE` (`https://mastodon.gamedev.place`) and `MASTODON_TOKEN`
secrets for `.github/workflows/daily-post.yml`. Make the token under Preferences → Development → New application,
with the `write:statuses` and `write:media` scopes (media is for the picture of the cube).

**A first post:**

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
