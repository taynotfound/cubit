# Cubit leaderboard

Zero-dependency Go API behind `https://cubit-lb-api.taymaerz.de`.

Ranked runs are **verified, not trusted**:
1. The app opens a run with `POST /v1/runs`.
2. It records every tap.
3. It sends them to `POST /v1/runs/{id}/finish`.
4. The server regenerates the exact cube (`puzzle.go` is a bit-identical port of the app's generator, pinned by fingerprint tests on both sides), replays the taps under the game rules, and computes score and XP itself.
   Powerups are recorded as negative arrow indices (-1 shield, -2 freeze +15 s, -3 spare life), each allowed once per run and only where the difficulty has lives or a timer. `Clean` is Zen rules with a tag: the board shows it and counts each player's `cleanRuns`.

**Rejected outright:** impossible runs, such as a tap on a blocked or missing arrow, running out of lives, a backwards clock, or a pink arrow that never gets out.

**Held for review:** runs that look automated: superhuman or metronomic tapping, or a clock that doesn't match the server's.

## Admin
The token is in `admin.token`, created with 0600 permissions on first start. Send it as a bearer token:

| Endpoint | Purpose |
|---|---|
| `GET /v1/admin/held` | List held runs with their reasons |
| `POST /v1/admin/held/{run}` | `{"action":"approve"}` or `{"action":"reject"}` |
| `GET /v1/admin/devices` | List all players |
| `POST /v1/admin/devices/{id}` | `{"banned":true}` and/or `{"name":"New name"}` |

## Deploy
```
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o cubit-leaderboard .
```
Upload the binary to the Pelican server and restart it. Data lives in `cubit-db.json`, with a `.bak` copy of the previous save. Requests that bypass the HTTPS proxy are refused. Set `CUBIT_ALLOW_DIRECT=1` only for local testing.
