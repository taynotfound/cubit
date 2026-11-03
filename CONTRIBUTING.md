# Contributing to Cubit

Bug reports, ideas and pull requests are all welcome. Cubit is a small project, so a short, focused change is easiest to review.

## Before you start

- **Bugs:** open an issue using the bug template. Include your Cubit version (Settings → About), your phone and Android version, the mode, what you did and what happened. A screenshot or screen recording helps a lot.
- **Ideas and bigger changes:** open an issue first so we can agree on the approach. That's especially true for anything that changes gameplay, levels or the look, before you spend time building it.
- **Small fixes** (typos, crashes, obvious bugs) can go straight to a pull request.

## Setting up

You need:

- **JDK 21.** Releases must be built with 21, because F-Droid builds with 21 and checks that its APK matches ours byte for byte. Other versions build fine for development.
- **Android SDK** with platform 34. Android Studio's SDK works, or set `ANDROID_HOME`.
- **Go 1.23+**, only if you work on the leaderboard server.

```bash
git clone https://github.com/taynotfound/cubit.git
cd cubit
./gradlew testGithubDebugUnitTest assembleGithubDebug    # tests + debug APK
adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk

cd server && go test ./...                               # leaderboard server
```

The debug build installs next to the release app as a separate, debug-signed app. Uninstall it before going back to a release.

### Build flavours

| Flavour | What it is |
|---|---|
| `github` | The normal build (`Cubit.apk`). It checks GitHub for new versions. |
| `fdroid` | The same game without the update check (`Cubit-fdroid.apk`). It must stay reproducible. |

## How the code is laid out

| Where | What |
|---|---|
| `app/src/main/java/com/cubit/game/Puzzle.kt` | Cube geometry and the deterministic level generator |
| `GameView.kt` | The renderer (hand-rolled 3D on Canvas), camera, touch input and rules |
| `MainActivity.kt` | Screens, sheets, rounds, saving and settings |
| `Ui.kt`, `Sfx.kt`, `Net.kt`, `Prefs.kt` | Design kit (fonts, icons, flap boards), sound and haptics, the leaderboard client, settings and badges |
| `server/` | The Go leaderboard, which verifies every ranked run by replaying it |
| `tools/` | Python scripts that synthesize the swoosh and the music |
| `docs/` | The website (GitHub Pages) |
| `fastlane/` | The store listing F-Droid reads |

## House rules

- **Plain Kotlin, programmatic Views, no AndroidX, no Google libraries.** New dependencies need a very good reason, and they must be free software (F-Droid rejects anything else).
- **The level generator exists twice:** `Puzzle.kt` and `server/puzzle.go` must build bit-identical cubes, or ranked runs stop verifying. If you change one, change the other in the same pull request, and update the golden fingerprints in both `PuzzleTest.kt` and `server/puzzle_test.go`. Changing the generator changes every level for every player, so open an issue first.
- **Keep the F-Droid build reproducible.** Don't embed timestamps, git info or other build-machine details in the APK.
- **Look and feel** follow [DESIGN.md](DESIGN.md).
- **Privacy:** no analytics, no tracking, and no network calls the player didn't turn on.
- **Accessibility:** keep content descriptions on controls, touch targets at least 48dp, and honour the system's Remove animations setting.

## Pull requests

1. Fork the repo and branch off `main`.
2. Keep each pull request to one change, and describe what it does and how you tested it, on a real phone if you can.
3. CI runs the tests and builds a debug APK for every pull request. You'll find it as an artifact on the pull request's Checks tab, so reviewers can install and try it.
4. Don't bump the version or edit `CHANGELOG.md`. The maintainer does that when releasing.

**AI tools:** fine to use, but say so in the pull request, and make sure you understand and have tested what you submit. See [AI-DECLARATION.md](AI-DECLARATION.md).

## Releases (maintainer)

A push to `main` whose commit message starts with a version number (for example `1.5.0: new levels`) triggers the Release workflow. It:

1. checks that `versionName` in `app/build.gradle.kts` matches the version in the message;
2. builds both flavours with JDK 21 and signs them with the release key from the repository secrets;
3. publishes a GitHub release `v<version>` with `Cubit.apk` and `Cubit-fdroid.apk`, with notes taken from that version's section in `CHANGELOG.md`.

Before that commit:

- bump `versionCode` and `versionName`;
- add a `## <version> (<date>)` section to `CHANGELOG.md`;
- add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

F-Droid then picks up the new tag by itself within a few days.

## Licence

By contributing, you agree that your work is released under the [MIT License](LICENSE).
