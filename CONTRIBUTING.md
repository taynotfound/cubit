# Contributing

Issues and pull requests are welcome.

- **Bugs:** say which level or mode, what you did, and what happened. A screenshot helps.
- **Ideas:** open an issue first for anything big, so we can agree on it before you build it.

## Working on the code

```bash
./gradlew testDebugUnitTest assembleRelease          # app (JDK 17, Android SDK 34)
cd server && go test ./...                           # leaderboard (Go 1.23+)
```

- The app is plain Kotlin with programmatic Views and no AndroidX. Please keep it that way.
- **The level generator exists twice:** `Puzzle.kt` and `server/puzzle.go` must build bit-identical cubes. If you change one, change the other, then update the golden fingerprints in both `PuzzleTest.kt` and `puzzle_test.go`. A change here also changes every level, so it needs a good reason.
- The look follows [DESIGN.md](DESIGN.md).

By contributing, you agree that your work is released under the [MIT License](LICENSE).
