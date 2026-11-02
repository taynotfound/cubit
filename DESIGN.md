# Cubit design: Wayfinding

Cubit borrows the language of transit signage. Arrows are what signs are made of, so the cube is an enamel sign panel, the arrows are its markings, and the one pink arrow is the train you have to get out.

## Signature
**Split-flap boards** (`FlapView` in `Ui.kt`). Numbers that matter flip into place a character at a time, like an old station departure board, with a light haptic tick:
- level, grid size, lives and timer on the Play tab
- the HUD's level, timer and cleared counters
- result rows
- leaderboard ranks
- the welcome sign

Use a flap board for data, never for prose. It respects the system "Remove animations" setting.

## Colour
The dark ground comes from where the game gets played: on a phone, in hand, often in the evening or on a commute.

| Role | Signal (house) | Notes |
|---|---|---|
| Ground `bg` | `#0E1B3D` ink navy | Radial pool of face colour behind the cube |
| Panel | `#172A57` | Strips, sheets, nav bar |
| Face | `#1F4FBF` signal blue | Shaded toward black by a fixed light |
| Arrows `ink` | `#F4F6FB` | |
| Accent | `#FFC72C` signal yellow | Primary action, selection, freed arrows, hints. Text on it is `onAccent` (navy) |
| Target | `#FF3D7F` pink | Reserved for the trapped arrow and lives. Nothing else is pink |
| Tile / hairline / muted | Derived from text + bg | See `Theme` in `Prefs.kt` |

- **Colourways:** Signal, Platform (green), Metro (red), Night line (amber on graphite), and Daylight (light). Each one keeps the same roles.
- **Lines per side:** each face takes a transit-line colour (`LINE_COLORS`), so the sides are always distinguishable.

## Type
Self-hosted fonts in `assets/fonts`, under the SIL Open Font License:
- **Barlow Condensed Bold:** display and signage, all caps, tracking 0.01–0.03em.
- **Barlow Condensed SemiBold:** labels and buttons, caps, tracking 0.03–0.08em.
- **Barlow Regular and Medium:** body text and names.

Sizes are in sp, so text follows the system font scale.

## Shape and controls
- **Corners:** 6dp, like enamel signs. No pills. Sheets use 10dp.
- **Primary button:** a yellow plate, 60dp tall, with a trailing arrow glyph.
- **Secondary button:** the panel colour with a 1dp hairline.
- **Icons:** one stroke family on a 24-unit grid with 2-unit round strokes (`Glyph`). Never emoji.
- **Touch targets:** at least 48dp.
- **Navigation:** no tab bar. Play is home. Rankings and Settings open from quiet icons in the top right and have a back arrow.
- **Play screen:** the wordmark and player (tap for profile) at the top; the cube filling the screen; one flap line (`LEVEL 001`) and one quiet rules line; Play; then three chips. **Clean** is the only setting on the screen itself, and toggling it previews the glass cube live. **Mode** opens a sheet with the difficulties and Full clear. **More** opens a sheet with the daily cube, free roam and level replay, and shows a flame and the count while a streak is alive.
- **Sheets and confirms:** bottom sheets in the panel colour, closed by tapping outside or pressing back. Confirms make the safe choice the yellow button and the risky one a red secondary. No stock dialogs.
- **In a level:** hints and powerups sit in a tray behind the bolt button. The counter shows arrows **left**. Leaving with progress asks first.
- **Settings:** groups that fold open from their heading.
- **Lives:** shown as indicator lamps (`LampsView`).

## Modes, powerups, touch
- **Goal:** free the pink arrow. The generator rerolls until at least an eighth of the arrows stand in its way. **Full clear**, a switch in the Mode sheet, changes the goal to clearing every arrow, with the pink one last; tapping it early counts as a bump. Full-clear runs are labelled on the board. Levels start at 10×10 with about 100 winding arrows and grow to 18×18.
- **Find the last arrows:** when 6 or fewer arrows are left, the recenter button turns the cube to a face with a free arrow and highlights it.
- **Clean:** a difficulty with no lives and no timer, and only the cube plus a muted X on screen. The cube is drawn as glass in the colourway: no face fill, flat arrows at full weight that fade toward the background with depth, and back faces visible through the front. Clean runs are marked on the board.
- **Powerups:** Scan, Shield, Freeze, Spare life and Undo (Undo takes back the last bump). They appear as accent-coloured icon plates beside the lamps, one charge each per level, and dim once used. Each can be switched off.
- **Haptics:** the `Haptics` helper in `Ui.kt` gives a tick for UI, a click when an arrow leaves, a heavy click on a bump, a double click for powerups, and patterns for a win or a loss.
- **Daily cube:** one 12×12 cube per UTC day, the same for everyone (`dailySpec`, pinned by golden tests on the app and the server).
- **Run details:** tap a Best runs row to open a sheet with flap rows for score, time, cube size, arrows cleared and mistakes, plus the powerups used and a perfect-run badge.
- **Badges:** 14 achievements, checked after every win, shown under Rankings → Badges. They're stored on the phone only.
- **Daily streak:** a flame glyph and an accent-coloured button while the streak is alive. The streak lapses after a whole UTC day is missed.
- **Replays:** every ranked run stores its taps, and the run details sheet can play them back (`GET /v1/runs/{id}/replay`). Gaps longer than 1.5 s are cut down to 1.5 s.
- **Share card:** a 1080×1350 PNG of the cube with the score, sent through `ShareProvider`, so no storage permission is needed.
- **Settings:** grouped panels: Profile, Powerups, Look, Sound and touch, Progress.
- **First launch:** a five-step sheet (Welcome, Mode, Feel, Power, Ranks), with each step named on the split-flap sign.

## Motion
- **Screens:** fade and rise 10dp over 220ms with ease-out.
- **Sheets:** rise from the bottom.
- **Buttons:** dip to 0.97 when pressed.
- **The cube:** spins in at level start, gives particle bursts when an arrow is freed and confetti when the target is, shakes and flashes red on a mistake, and the target arrow pulses.

## Sound
Kenney CC0 packs, licence in `assets/licenses`, played through `Sfx.kt`. Every sound is physical and literal:

| Moment | Sound |
|---|---|
| Arrow slides off | Soft synthesized whoosh (`tools/swoosh.py`): pink noise sweeping 140 → 560 → 280 Hz at -9 dBFS, with almost nothing above 2 kHz |
| Arrow hits a blocker | Wooden thunk |
| Split-flap tiles | Real clacks cut from a split-flap recording, quiet. |
| Arrow freed | Steel-drum fanfare, rising |
| Run failed | Falling sting |

- **Variety:** pitch drifts ±6–12% between repeats.
- **Timer:** silent.
- **Music:** two seamless ambient loops synthesized by `tools/music.py`: soft pads, a bell arpeggio and sub bass, with circular reverb so there's no seam. The menu loop runs at 72 BPM and the in-game loop at 88 BPM with a quiet shaker. Both play under the effects at about 20% volume.
- **Volume:** UI clicks stay quieter than gameplay sounds.
