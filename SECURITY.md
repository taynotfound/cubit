# Security

## Reporting a problem

Found a way to post a score the server shouldn't accept, to crash or slow down the leaderboard, or to read data you shouldn't? Please report it privately through [GitHub's private vulnerability reporting](https://github.com/taynotfound/cubit/security/advisories/new) instead of opening a public issue. I'll reply as soon as I can.

## What the leaderboard stores

- Per install: a display name, a hash of the device's secret token, XP and run counts.
- Per ranked run: score, the level, mode, time, arrows cleared, mistakes, powerups used, and the taps (so the run can be verified and watched back).

There are no accounts, emails or analytics, and nothing at all is sent unless global rankings are turned on in the app. Cheating isn't a security issue, but if you find a cheat the server doesn't catch, a report through the link above is welcome too.
