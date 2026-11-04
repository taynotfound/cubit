# Changelog

## 1.5.0 (2026-11-04)

- **Nightmare mode** (Mode on the Play tab): arrows run a full lap round the cube before they leave, so you have to check every face they pass, back faces included. One life, a tight timer, no hints, no powerups, ×3.5 XP. A new badge, Night shift, for your first win.
- Rankings say "Player LV" for the level from XP, and each tab keeps its own subtitle.
- After a run, Global rank shows where you stand on the board even when it wasn't your best run.

## 1.4.1.1 (2026-11-03)

- Fixed the cube sometimes zooming when you tapped and then dragged to turn it. Only a two-finger pinch zooms now.
- Lifting one finger after a pinch no longer jerks the cube round.
- Flicking the cube spins the same on every phone, and it no longer flings if your finger stopped before lifting.
- Tapping to stop a spinning cube no longer removes an arrow by accident.
- Find next resets zoom and pan, so the face it turns to stays on screen.

## 1.4.1 (2026-11-03)

- Support links (Ko-fi and GitHub Sponsors) in Settings, About, and on the website.

## 1.4.0 (2026-11-02)

- **New app ID: `de.taymaerz.cubit`** (it was `com.cubit.game`). Android treats this as a new app, so it installs next to the old one rather than over it. Install 1.4.0, then uninstall the old Cubit. Progress on the old install doesn't carry over, and with rankings on you'll join the board as a new player.

## 1.3.1 (2026-11-02)

- A separate F-Droid build without the GitHub update check. Builds are now reproducible, and releases carry both `Cubit.apk` and `Cubit-fdroid.apk`.
- Get automatic updates from GitHub with Obtainium (links on the website and in the README).

## 1.3.0 (2026-11-02)

- **Weekly challenge:** one 16×16 cube for everyone, Monday to Sunday, with its own board (Rankings → Week).
- **Puzzle packs:** Pocket, Needlework, Long lines and Monster, with ten cubes each, picked for how deep the pink arrow sits.
- **Par:** every cube has a fewest-taps-to-win number. Matching it counts as on par, and there's a badge for it.
- **Ghost race:** on the daily and weekly cubes, the leader's recorded run plays on your cube by the same clock, and its cleared arrows fade.
- **Colourways you earn:** six of the twelve open with badges. There are three new badges: On par, Weekender and Collector.
- **Streak reminder (opt-in):** one notification around 7 pm if your daily streak would end tonight.
- **Share links** to the daily or weekly cube open that cube in Cubit, or the download page if the friend doesn't have it.
- **Update notices:** a small card on the Play screen when a new version is out. It can be switched off in About.
- **Yesterday's cube:** a one-time card with how you placed.

## 1.2.0 (2026-11-02)

- Settings has an About section with the version, an update check against GitHub releases, and links to the website, source and changelog. The version also sits at the bottom of Settings.
- The website shows the current version, read live from the latest release.

## 1.1.1 (2026-11-02)

- Proper adaptive app icon: the navy ground fills whatever shape your launcher uses, with no more small square on a white circle. Android 13+ themed icons get a line-art version of the cube.
- The launch screens use the same cube.

## 1.1.0 (2026-11-02)

- Opening screen: a small cube spins in, CUBIT clatters onto the split-flap board, and the tagline fades up. A tap skips it, and it's skipped when animations are turned off.
- Launch screen: the app opens on its navy ground with the icon, using Android 12's own splash screen where available, so there's no white flash.
- Loading board: if a big cube takes more than a moment to build, a LOADING board covers the wait.

## 1.0.0 (2026-11-02)

The first release.

- An endless campaign. Cubes start at 10×10 per side with about 100 winding arrows and grow to 18×18, and the pink arrow is buried deeper as levels go on.
- Clean mode, one tap from the home screen: a see-through glass cube in your colourway, with no HUD. Two-finger tap takes back a move.
- Modes Zen, Easy, Normal, Hard and Expert, plus an optional Full clear goal (every arrow off, pink last).
- A daily cube with its own board and a streak, free roam (2×2 to 18×18) and level replays.
- Powerups Scan, Shield, Freeze, Spare life and Undo, each switchable. Hints are earned for levelling up, perfect runs and daily cubes.
- Leaving a level saves it, and the save survives the app closing.
- A first-launch tutorial and setup.
- Optional global rankings: players, best runs and today's daily cube. Every ranked run is replayed and verified by the server, and runs can be watched back.
- Badges, XP, a share card, 12 colourways, haptics, sound effects and a synthesized soundtrack.
- Accessibility: a striped target, a left-handed layout, heavier arrows, and support for the system's Remove animations setting.
- Signed with the release key. Earlier test builds must be uninstalled once.
