<p align="center"><img src="docs/img/icon.svg" width="96" alt=""></p>

<h1 align="center">Cubit</h1>

<p align="center"><a href="AI-DECLARATION.md"><img src="https://img.shields.io/badge/䷼%20AI--DECLARATION-auto-ede9fe?labelColor=ede9fe" alt="AI-DECLARATION: auto"></a></p>

<p align="center"><b>A hard little cube of arrows. Free the pink one.</b><br>
No ads, no accounts, no nonsense. Native Android.</p>

<p align="center">
  <a href="https://github.com/taynotfound/cubit/releases/latest/download/Cubit.apk"><b>Download the APK</b></a> ·
  <a href="https://taynotfound.github.io/cubit/">Website</a> ·
  <a href="https://github.com/taynotfound/cubit/releases">All releases</a>
</p>

<p align="center"><a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522de.taymaerz.cubit%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Ftaynotfound%252Fcubit%2522%252C%2522author%2522%253A%2522taynotfound%2522%252C%2522name%2522%253A%2522Cubit%2522%252C%2522additionalSettings%2522%253A%2522%257B%255C%2522apkFilterRegEx%255C%2522%253A%2520%255C%2522%255ECubit%255C%255C%255C%255C.apk%2524%255C%2522%257D%2522%257D"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" height="54" alt="Get it on Obtainium"></a></p>

<p align="center"><img src="docs/img/trailer.gif" width="300" alt="Cubit opening, Clean mode and a level being played"></p>

<p align="center">
  <img src="docs/img/home.png" width="23%" alt="Home screen">
  <img src="docs/img/game.png" width="23%" alt="A level in progress">
  <img src="docs/img/home-clean.png" width="23%" alt="Clean mode preview">
  <img src="docs/img/game-clean.png" width="23%" alt="Clean mode: just the glass cube">
</p>

## How it plays

Every face of the cube is covered in arrows. Tap one and it slides forward along its path and off the cube, if nothing is in the way. If something is, it bumps and bounces back, and on most modes that costs a life.

One arrow is pink. **Clear a way out for it and tap it to win.** Drag to turn the cube, pinch to zoom, two fingers to pan.

Levels start at 10×10 per side with about a hundred winding arrows and grow to 18×18. The pink arrow is always buried: the generator rerolls a level until enough arrows stand in its way, and that depth rises as you go.

## Features

- **Clean mode.** One tap on the home screen. Just a see-through glass cube in your colours, back faces showing through, no lives, no timer, no buttons. Two-finger tap to take back a move.
- **Modes:** Zen, Easy, Normal, Hard and Expert, each with its own lives, timer and XP multiplier.
- **Full clear.** An optional goal: clear every arrow, and the pink one has to leave last.
- **Endless campaign, a daily cube and a weekly challenge**, the same cube for everyone, each with its own board. Share a link and a friend gets the same cube.
- **Puzzle packs:** forty picked cubes in four packs, from tiny 5×5 puzzles to 18×18 monsters. Plus free roam (any size from 2×2 to 18×18) and replays of any level.
- **Par for every cube:** the fewest taps that can win. Hit it for a star.
- **Ghost race:** on the daily and weekly cubes, the leader's run plays out faintly on your cube as you go.
- **Powerups,** each switchable: Scan, Shield, Freeze, Spare life and Undo. Hints are earned for levelling up, perfect runs and daily cubes.
- **Leaving is safe.** Leave a level and it's saved; continue it from the home screen.
- **Global rankings** (optional). Every ranked run is verified on the server by replaying your taps, so scores can't simply be posted. There are boards for players, best runs and today's daily cube, and you can watch any run back.
- **Badges, XP and a daily streak.** Six colourways are earned with badges, and an opt-in reminder warns you the evening before a streak would end.
- **12 colourways** with arrow colour and weight options. Sound effects, a small synthesized soundtrack and real haptics.
- **Accessibility:** a striped target that doesn't rely on colour, a left-handed layout and arrows up to weight 14. It follows the system's Remove animations setting.
- **A share card** for your best runs.

## Install

Download `Cubit.apk` from the [latest release](https://github.com/taynotfound/cubit/releases/latest) and open it on your phone. Android 7.0 or newer.

**Want automatic updates?** Add Cubit to [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522de.taymaerz.cubit%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Ftaynotfound%252Fcubit%2522%252C%2522author%2522%253A%2522taynotfound%2522%252C%2522name%2522%253A%2522Cubit%2522%252C%2522additionalSettings%2522%253A%2522%257B%255C%2522apkFilterRegEx%255C%2522%253A%2520%255C%2522%255ECubit%255C%255C%255C%255C.apk%2524%255C%2522%257D%2522%257D), which installs new versions straight from these GitHub releases.

> Upgrading from a build before 1.0.0? Those were signed with a development key. Uninstall the old app first; 1.0.0 and later update in place.

**GrapheneOS and other privacy ROMs:** if rankings say "can't reach the internet", allow Cubit's Network permission. The app works fully offline otherwise.

**F-Droid:** the `fdroid` build flavour is reproducible, and the store listing lives in `fastlane/`.

## Build it yourself

You need JDK 17 and the Android SDK (platform 34).

```bash
./gradlew testGithubDebugUnitTest assembleGithubRelease assembleFdroidRelease
```

APKs land in `app/build/outputs/apk/<flavour>/release/`. The `github` flavour checks GitHub for updates, and the `fdroid` flavour doesn't. Release builds are signed from `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without it they come out unsigned.

The app is plain Kotlin with no AndroidX: Activities, programmatic Views and a hand-rolled perspective renderer on Canvas.

## Leaderboard server

`server/` is a small Go service with no dependencies. It regenerates each cube from its seed (`puzzle.go` is a bit-identical port of `Puzzle.kt`, pinned by fingerprint tests on both sides), replays every ranked run, computes the score itself, and holds suspicious runs for review. It saves to disk atomically and keeps daily backups. See [server/README.md](server/README.md).

```bash
cd server && go test ./... && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o cubit-leaderboard .
```

## Project layout

| Path | What |
|---|---|
| `app/src/main/java/com/cubit/game/Puzzle.kt` | Cube geometry, deterministic level generator |
| `GameView.kt` | Renderer, camera, input, rules |
| `MainActivity.kt` | Screens, sheets, rounds, saving |
| `Ui.kt`, `Sfx.kt`, `Net.kt`, `Prefs.kt` | Design kit, sound and haptics, rankings client, settings |
| `server/` | Leaderboard and run verification |
| `tools/` | Scripts that synthesize the swoosh and the music |
| `DESIGN.md` | The Wayfinding design system |
| `docs/` | The website (GitHub Pages) |

## Licence

The code, art and synthesized audio are under the [MIT License](LICENSE). The fonts (SIL OFL) and Kenney's sound effects (CC0) keep their own licences; see [NOTICE.md](NOTICE.md).

There's also a [changelog](CHANGELOG.md), [how to contribute](CONTRIBUTING.md), and [how to report a security problem](SECURITY.md).

## Credits

- Fonts: [Barlow](https://github.com/jpt/barlow) by Jeremy Tribby, under the SIL Open Font License.
- Sound effects: [Kenney](https://kenney.nl) packs, CC0. The split-flap clacks were cut from a real display recording, and the swoosh and the music are synthesized by the scripts in `tools/`.
