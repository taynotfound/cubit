// Cubit leaderboard: stdlib-only API that verifies every ranked run by regenerating the puzzle and replaying
// the player's taps, so scores are computed here and can't simply be posted.
package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode"
)

const publicURL = "https://cubit-lb-api.taymaerz.de"

type Run struct {
	ID, Device, Label, Diff string
	Spec                    LevelSpec
	Free, All               bool // All: full-clear goal
	Day                     int  // daily cube runs only
	Started                 time.Time
}

type App struct {
	store       *Store
	limits      *Limiter
	adminToken  string
	allowDirect bool   // accept requests that bypass the HTTPS proxy (tests / local dev only)
	serverIP    string // our own public IP: the proxy on this host connects from it
	now         func() time.Time

	mu   sync.Mutex
	runs map[string]*Run
}

// ---------- helpers ----------

func randID(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

func hashSecret(s string) string { h := sha256.Sum256([]byte(s)); return hex.EncodeToString(h[:]) }

func send(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

func fail(w http.ResponseWriter, code int, msg string) {
	send(w, code, map[string]string{"error": msg})
}

// decode reads exactly one JSON object with no unknown fields, capped in size.
func decode(w http.ResponseWriter, r *http.Request, limit int64, v any) bool {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		fail(w, http.StatusBadRequest, "bad request body")
		return false
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		fail(w, http.StatusBadRequest, "one JSON object only")
		return false
	}
	return true
}

func cleanName(s string) (string, bool) {
	var b strings.Builder
	space := false
	for _, r := range strings.TrimSpace(s) {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r) || r == '_' || r == '.' || r == '-':
			b.WriteRune(r)
			space = false
		case r == ' ' && !space:
			b.WriteRune(r)
			space = true
		}
	}
	out := []rune(strings.TrimSpace(b.String()))
	if len(out) > 16 {
		out = []rune(strings.TrimSpace(string(out[:16])))
	}
	return string(out), len(out) >= 2
}

func isPrivate(ip net.IP) bool { return ip != nil && (ip.IsLoopback() || ip.IsPrivate()) }

// clientIP trusts forwarding headers only from the proxy on this host; viaProxy reports whether it came through it.
func (a *App) clientIP(r *http.Request) (ip string, viaProxy bool) {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if !isPrivate(net.ParseIP(host)) && host != a.serverIP {
		return host, false
	}
	if v := strings.TrimSpace(r.Header.Get("X-Real-IP")); v != "" {
		return v, true
	}
	if v := r.Header.Get("X-Forwarded-For"); v != "" {
		return strings.TrimSpace(strings.Split(v, ",")[0]), true
	}
	return host, false
}

// auth resolves "Authorization: Bearer <device>.<secret>".
func (a *App) auth(r *http.Request) (string, *Device, int) {
	tok, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	id, secret, ok2 := strings.Cut(tok, ".")
	if !ok || !ok2 || len(id) != 32 || secret == "" {
		return "", nil, http.StatusUnauthorized
	}
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	d := a.store.db.Devices[id]
	if d == nil || subtle.ConstantTimeCompare([]byte(d.TokenHash), []byte(hashSecret(secret))) != 1 {
		return "", nil, http.StatusUnauthorized
	}
	if d.Banned {
		return "", nil, http.StatusForbidden
	}
	return id, d, 0
}

// ---------- middleware ----------

func (a *App) wrap(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hd := w.Header()
		hd.Set("X-Content-Type-Options", "nosniff")
		hd.Set("Referrer-Policy", "no-referrer")
		hd.Set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
		hd.Set("Strict-Transport-Security", "max-age=31536000")
		hd.Set("Cache-Control", "no-store")
		defer func() {
			if p := recover(); p != nil {
				log.Printf("panic on %s %s: %v", r.Method, r.URL.Path, p)
				fail(w, http.StatusInternalServerError, "internal error")
			}
		}()
		ip, via := a.clientIP(r)
		if !via && !a.allowDirect && r.URL.Path != "/healthz" {
			fail(w, http.StatusForbidden, "use "+publicURL)
			return
		}
		if !a.limits.allow("all:"+ip, 120, 240) {
			fail(w, http.StatusTooManyRequests, "slow down")
			return
		}
		h.ServeHTTP(w, r)
	})
}

// ---------- handlers ----------

func (a *App) register(w http.ResponseWriter, r *http.Request) {
	ip, _ := a.clientIP(r)
	if !a.limits.allow("reg:"+ip, 3, 0.05) || !a.limits.allow("reg:*", 30, 30) {
		fail(w, http.StatusTooManyRequests, "too many new players from here, try later")
		return
	}
	var b struct{ Name string }
	if !decode(w, r, 1024, &b) {
		return
	}
	name, ok := cleanName(b.Name)
	if !ok {
		fail(w, http.StatusBadRequest, "name must be 2-16 letters or digits")
		return
	}
	id, secret := randID(16), base64.RawURLEncoding.EncodeToString([]byte(randID(32)))
	a.store.mu.Lock()
	a.store.db.Devices[id] = &Device{Name: name, TokenHash: hashSecret(secret), Created: a.now().Unix()}
	a.store.mu.Unlock()
	a.store.markDirty()
	log.Printf("register %s %q from %s", id[:8], name, ip)
	send(w, http.StatusOK, map[string]string{"token": id + "." + secret, "name": name})
}

func (a *App) startRun(w http.ResponseWriter, r *http.Request) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	if !a.limits.allow("start:"+dev, 20, 20) {
		fail(w, http.StatusTooManyRequests, "slow down")
		return
	}
	var b struct {
		Mode, Difficulty, Name string
		Goal                   string // "" or "pink": free the pink arrow; "all": clear the cube, pink last
		Level, N, MaxLen, Day  int
		Seed                   int64
	}
	if !decode(w, r, 1024, &b) {
		return
	}
	if _, ok := difficulties[b.Difficulty]; !ok {
		fail(w, http.StatusBadRequest, "unknown difficulty")
		return
	}
	run := &Run{ID: randID(16), Device: dev, Diff: b.Difficulty, Started: a.now()}
	switch b.Mode {
	case "campaign":
		if b.Level < 1 || b.Level > 100_000 {
			fail(w, http.StatusBadRequest, "bad level")
			return
		}
		run.Spec, run.Label = specForLevel(b.Level), fmt.Sprintf("Level %d", b.Level)
	case "daily":
		// The phone's UTC day may differ from ours around midnight; allow one day either way.
		if today := int(a.now().Unix() / 86400); b.Day < today-1 || b.Day > today+1 {
			fail(w, http.StatusBadRequest, "that daily cube is closed")
			return
		}
		run.Spec, run.Label, run.Day = dailySpec(b.Day), "Daily "+time.Unix(int64(b.Day)*86400, 0).UTC().Format("2 Jan"), b.Day
	case "free":
		if b.N < 2 || b.N > 18 || b.MaxLen < 2 || b.MaxLen > 24 { // 18 = replayed campaign levels
			fail(w, http.StatusBadRequest, "bad cube size")
			return
		}
		run.Spec, run.Free, run.Label = LevelSpec{b.N, b.MaxLen, b.Seed}, true, fmt.Sprintf("Free %d×%d", b.N, b.N)
	default:
		fail(w, http.StatusBadRequest, "unknown mode")
		return
	}
	switch b.Goal {
	case "", "pink":
	case "all":
		run.All, run.Label = true, run.Label+" · full clear"
	default:
		fail(w, http.StatusBadRequest, "unknown goal")
		return
	}
	if name, ok := cleanName(b.Name); ok && b.Name != "" {
		a.store.mu.Lock()
		d.Name = name
		a.store.mu.Unlock()
		a.store.markDirty()
	}
	a.mu.Lock()
	// Keep at most 5 open runs per player: starting a sixth drops the oldest.
	var mine []*Run
	for _, x := range a.runs {
		if x.Device == dev {
			mine = append(mine, x)
		}
	}
	for len(mine) >= 5 {
		old := 0
		for i := range mine {
			if mine[i].Started.Before(mine[old].Started) {
				old = i
			}
		}
		delete(a.runs, mine[old].ID)
		mine = append(mine[:old], mine[old+1:]...)
	}
	a.runs[run.ID] = run
	a.mu.Unlock()
	send(w, http.StatusOK, map[string]string{"run": run.ID})
}

func (a *App) finishRun(w http.ResponseWriter, r *http.Request) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	if !a.limits.allow("finish:"+dev, 10, 10) {
		fail(w, http.StatusTooManyRequests, "slow down")
		return
	}
	var b struct{ Moves [][2]int64 }
	if !decode(w, r, 32<<10, &b) {
		return
	}
	a.mu.Lock()
	run := a.runs[r.PathValue("id")]
	if run != nil && run.Device == dev {
		delete(a.runs, run.ID) // single use, whatever the outcome
	} else {
		run = nil
	}
	a.mu.Unlock()
	if run == nil {
		fail(w, http.StatusNotFound, "run not found or already submitted")
		return
	}
	serverMs := a.now().Sub(run.Started).Milliseconds()
	moves := make([]Move, len(b.Moves))
	for i, m := range b.Moves {
		moves[i] = Move{int(m[0]), m[1]}
	}
	diff := difficulties[run.Diff]
	p := generate(run.Spec)
	rep, err := replay(p, diff, moves, run.All)
	if err != nil {
		log.Printf("reject %s run %s: %v", dev[:8], run.ID[:8], err)
		fail(w, http.StatusUnprocessableEntity, "run did not verify: "+err.Error())
		return
	}
	limit := diff.timeLimitSec(len(p.paths))
	if limit > 0 {
		limit += rep.Freezes * freezeSec
	}
	sc, xp := score(rep, run.Spec, diff, run.Free, limit, serverMs)
	entry := Score{RunID: run.ID, Device: dev, Score: sc, XP: xp, Label: run.Label, Difficulty: run.Diff, Arrows: rep.Cleared, Ms: rep.LastMs, Time: a.now().UnixMilli(),
		Mistakes: rep.Mistakes, Size: run.Spec.N, Powerups: rep.Powerups, Spec: run.Spec, Moves: b.Moves, Day: run.Day}

	if why := suspicion(rep, moves, limit, serverMs); len(why) > 0 {
		a.store.mu.Lock()
		if len(a.store.db.Held) < 1000 {
			a.store.db.Held = append(a.store.db.Held, Held{entry, why})
		}
		a.store.mu.Unlock()
		a.store.markDirty()
		log.Printf("hold %s run %s: %s", dev[:8], run.ID[:8], strings.Join(why, "; "))
		send(w, http.StatusOK, map[string]any{"status": "review", "score": sc, "xp": xp})
		return
	}
	a.store.mu.Lock()
	d.credit(entry)
	rank := a.store.addScore(entry)
	total := d.XP
	a.store.mu.Unlock()
	a.store.markDirty()
	send(w, http.StatusOK, map[string]any{"status": "accepted", "score": sc, "xp": xp, "rank": rank, "totalXp": total, "level": playerLevel(total)})
}

// ---------- admin ----------

func (a *App) admin(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ip, _ := a.clientIP(r)
		tok, _ := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if a.adminToken == "" || subtle.ConstantTimeCompare([]byte(tok), []byte(a.adminToken)) != 1 {
			if !a.limits.allow("adminfail:"+ip, 5, 5) {
				fail(w, http.StatusTooManyRequests, "slow down")
				return
			}
			fail(w, http.StatusUnauthorized, "admin only")
			return
		}
		h(w, r)
	}
}

func (a *App) adminHeld(w http.ResponseWriter, r *http.Request) {
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	out := []map[string]any{}
	for _, h := range a.store.db.Held {
		name := ""
		if d := a.store.db.Devices[h.Score.Device]; d != nil {
			name = d.Name
		}
		out = append(out, map[string]any{"run": h.Score.RunID, "device": h.Score.Device, "name": name, "score": h.Score.Score, "label": h.Score.Label, "ms": h.Score.Ms, "reasons": h.Reasons})
	}
	send(w, http.StatusOK, out)
}

func (a *App) adminDecide(w http.ResponseWriter, r *http.Request) {
	var b struct{ Action string }
	if !decode(w, r, 256, &b) {
		return
	}
	if b.Action != "approve" && b.Action != "reject" {
		fail(w, http.StatusBadRequest, "action is approve or reject")
		return
	}
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	for i, h := range a.store.db.Held {
		if h.Score.RunID != r.PathValue("run") {
			continue
		}
		a.store.db.Held = append(a.store.db.Held[:i], a.store.db.Held[i+1:]...)
		if b.Action == "approve" {
			if d := a.store.db.Devices[h.Score.Device]; d != nil {
				d.credit(h.Score)
				a.store.addScore(h.Score)
			}
		}
		a.store.markDirty()
		send(w, http.StatusOK, map[string]string{"ok": b.Action})
		return
	}
	fail(w, http.StatusNotFound, "no such held run")
}

func (a *App) adminDevices(w http.ResponseWriter, r *http.Request) {
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	out := []map[string]any{}
	for id, d := range a.store.db.Devices {
		out = append(out, map[string]any{"device": id, "name": d.Name, "xp": d.XP, "runs": d.Runs, "banned": d.Banned})
	}
	send(w, http.StatusOK, out)
}

func (a *App) adminDevice(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Banned *bool
		Name   *string
	}
	if !decode(w, r, 512, &b) {
		return
	}
	a.store.mu.Lock()
	defer a.store.mu.Unlock()
	d := a.store.db.Devices[r.PathValue("id")]
	if d == nil {
		fail(w, http.StatusNotFound, "no such device")
		return
	}
	if b.Banned != nil {
		d.Banned = *b.Banned
	}
	if b.Name != nil {
		n, ok := cleanName(*b.Name)
		if !ok {
			fail(w, http.StatusBadRequest, "bad name")
			return
		}
		d.Name = n
	}
	a.store.markDirty()
	send(w, http.StatusOK, map[string]any{"name": d.Name, "banned": d.Banned})
}

// ---------- wiring ----------

func (a *App) routes() http.Handler {
	m := http.NewServeMux()
	m.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { send(w, 200, map[string]bool{"ok": true}) })
	m.HandleFunc("GET /v1/whoami", func(w http.ResponseWriter, r *http.Request) {
		ip, via := a.clientIP(r)
		send(w, 200, map[string]any{"ip": ip, "viaProxy": via})
	})
	m.HandleFunc("GET /v1/leaderboard", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "public, max-age=5")
		send(w, 200, a.store.board())
	})
	m.HandleFunc("GET /v1/runs/{id}/replay", func(w http.ResponseWriter, r *http.Request) {
		if rp := a.store.replayOf(r.PathValue("id")); rp != nil {
			w.Header().Set("Cache-Control", "public, max-age=3600")
			send(w, 200, rp)
			return
		}
		fail(w, http.StatusNotFound, "no replay for that run")
	})
	m.HandleFunc("GET /v1/daily/{day}", func(w http.ResponseWriter, r *http.Request) {
		day, err := strconv.Atoi(r.PathValue("day"))
		if err != nil || day < 0 || day > 1_000_000 {
			fail(w, http.StatusBadRequest, "bad day")
			return
		}
		w.Header().Set("Cache-Control", "public, max-age=10")
		send(w, 200, map[string]any{"day": day, "scores": a.store.daily(day)})
	})
	m.HandleFunc("POST /v1/register", a.register)
	m.HandleFunc("POST /v1/runs", a.startRun)
	m.HandleFunc("POST /v1/runs/{id}/finish", a.finishRun)
	m.HandleFunc("GET /v1/admin/held", a.admin(a.adminHeld))
	m.HandleFunc("POST /v1/admin/held/{run}", a.admin(a.adminDecide))
	m.HandleFunc("GET /v1/admin/devices", a.admin(a.adminDevices))
	m.HandleFunc("POST /v1/admin/devices/{id}", a.admin(a.adminDevice))
	return a.wrap(m)
}

// adminTokenFrom reads ADMIN_TOKEN, else admin.token beside the binary, creating it (0600) on first start.
func adminTokenFrom(file string) string {
	if t := os.Getenv("ADMIN_TOKEN"); len(t) >= 24 {
		return t
	}
	if b, err := os.ReadFile(file); err == nil && len(strings.TrimSpace(string(b))) >= 24 {
		return strings.TrimSpace(string(b))
	}
	t := randID(32)
	if err := os.WriteFile(file, []byte(t+"\n"), 0o600); err != nil {
		log.Println("could not write admin token, admin API disabled:", err)
		return ""
	}
	log.Printf("created %s: use it as the admin bearer token", file)
	return t
}

func (a *App) janitor() {
	for range time.Tick(10 * time.Minute) {
		a.mu.Lock()
		for id, r := range a.runs {
			if a.now().Sub(r.Started) > 3*time.Hour {
				delete(a.runs, id)
			}
		}
		a.mu.Unlock()
	}
}

func main() {
	store, err := openStore("cubit-db.json")
	if err != nil {
		log.Fatal("cubit-db.json is unreadable, refusing to start so it isn't overwritten: ", err)
	}
	go store.saver()
	go store.backups()
	a := &App{store: store, limits: newLimiter(), adminToken: adminTokenFrom("admin.token"),
		allowDirect: os.Getenv("CUBIT_ALLOW_DIRECT") == "1", serverIP: os.Getenv("SERVER_IP"), now: time.Now, runs: map[string]*Run{}}
	go a.janitor()

	port := os.Getenv("SERVER_PORT")
	if port == "" {
		port = "2512"
	}
	srv := &http.Server{Addr: ":" + port, Handler: a.routes(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second,
		WriteTimeout: 10 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 8 << 10}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		log.Printf("Cubit leaderboard on :%s (public %s)", port, publicURL)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatal(err)
		}
	}()
	<-ctx.Done()
	shut, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	srv.Shutdown(shut)
	if err := store.save(); err != nil {
		log.Println("final save failed:", err)
	}
	log.Println("stopped, data saved")
}
