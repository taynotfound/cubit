# Cubit leaderboard

Zero-dependency Go API behind `https://cubit-lb-api.taymaerz.de`.

Ranked runs are **verified, not trusted**:
1. The app opens a run with `POST /v1/runs`.
2. It records every tap.
3. It sends them to `POST /v1/runs/{id}/finish`.
4. The server regenerates the exact cube (`puzzle.go` is a bit-identical port of the app's generator, pinned by fingerprint tests on both sides), replays the taps under the game rules, and computes score and XP itself.
   Powerups are recorded as negative arrow indices (-1 shield, -2 freeze +15 s, -3 spare life), each allowed once per run and only where the difficulty has lives or a timer. `Clean` is Zen rules with a tag: the board shows it and counts each player's `cleanRuns`. `Nightmare` runs are generated and replayed on a lap cube (`LevelSpec.Lap`): an arrow only leaves if its whole lap round the cube is clear. It has one life, a timer and no powerups.

**Rejected outright:** impossible runs, such as a tap on a blocked or missing arrow, running out of lives, a backwards clock, or a pink arrow that never gets out.

**Held for review:** runs that look automated: superhuman or metronomic tapping, or a clock that doesn't match the server's.

## Admin
The token is in `admin.token`, created with 0600 permissions on first start. Send it as a bearer token:

| Endpoint | Purpose |
|---|---|
| `GET /v1/admin/held` | List held runs with their reasons |
| `POST /v1/admin/held/{run}` | `{"action":"approve"}` or `{"action":"reject"}` |
| `GET /v1/admin/stats` | Player counts: `registered` (turned rankings on), `ranked` (has a verified run), `banned`, `newLast7Days`, `heldRuns` |
| `GET /v1/admin/devices` | List all players |
| `POST /v1/admin/devices/{id}` | `{"banned":true}` and/or `{"name":"New name"}` |

`GET /v1/leaderboard` lists the top 50 and gives the number of ranked players as `total`.

Public, read-only and open to other sites (CORS), for the website:

| Endpoint | |
|---|---|
| `GET /v1/stats` | `players` (has a verified run) and `registered` |
| `GET /v1/profile/{code}` | An opt-in public profile by friend code; 404 unless the player switched it on |
| `GET /v1/endless`, `GET /v1/endless/{day}` | Best endless runs, and one day's daily endless runs |
| `GET /v1/world` | The World Cube: id, recipe, arrows left, players; `?id=&since=<v>` gives only what was cleared since |

Endless runs: `POST /v1/endless` (`{"daily":true}` for the day's shared seed) returns an id and seed; each cube is then a
ranked run with `"mode":"endless"`, and the server counts a cube only once it verifies. `POST /v1/me/profile` turns the
caller's public page on or off.

The World Cube is one shared 24×24 cube: `POST /v1/world/tap` (`{"id":…,"arrow":…}`) checks the tap against the
shared board and clears it (and anything a bomb takes) for everyone. Freeing the pink arrow wins the cube and starts the next.

Friends (signed in): `POST /v1/friends/{code}` adds a friend and leaves them a request to add you back, or accepts the
one they left you (404 for a code nobody has); `DELETE /v1/friends/{code}` removes a friend, ignores their request, or
takes back yours; `GET /v1/me/friends` gives your friends as the server has them and the requests waiting for you.

Social (signed in, `social.go`): `POST /v1/me/bio` (`{"bio":…}`, 140 characters, no links, handles or numbers);
`POST /v1/report/{code}` reports a bio (three reporters hide it until an admin decides); `POST /v1/friends/{code}/note`
(`{"msg":n}` for one of the app's fixed messages, or `{"challenge":payload}`, only between friends who added each other)
and `DELETE /v1/me/inbox/{id}`; notes and friends' feed come with `GET /v1/me/friends`. `GET /v1/me/league` is the
caller's league week. `POST /v1/me/invited-by/{code}` (new players only) and `POST /v1/me/rewards` handle invites;
`GET /v1/stats` gives the `goal` for Road to 1,000.

Moderation: `GET /v1/admin/reports` lists reported bios; `POST /v1/admin/reports/{code}` with `{"action":"keep"}`,
`"remove"` or `"lock"` (removed, and no new bio). Set `NTFY_URL` (and `NTFY_TOKEN` if the topic needs one) to get a
push for each report; otherwise they're logged.

Twist mode (`"difficulty":"Twist"`) records each face twist as move `-10 - face`; the replay turns that face's arrowheads.

## Names
Names with slurs or the worst abuse are refused (`names.go`): a new player registers as "Player", a rename is ignored, and names already in the database are reset to "Player" when the server starts. The list is short on purpose. For anything it misses, rename or ban the player with the endpoint above, and add the word to the list.

## Deploy
```
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o cubit-leaderboard .
```
Upload the binary to the Pelican server and restart it. Data lives in `cubit-db.json`, with a `.bak` copy of the previous save. Requests that bypass the HTTPS proxy are refused. Set `CUBIT_ALLOW_DIRECT=1` only for local testing.
