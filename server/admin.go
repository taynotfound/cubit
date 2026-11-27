package main

import (
	_ "embed"
	"net/http"
)

// The admin panel (1.9): one page at /admin that talks to the /v1/admin API with the admin token. The token is typed
// into the page and kept in that browser only; every request still needs it, so the page itself is no secret.

//go:embed admin.html
var adminHTML []byte

func (a *App) adminPage(w http.ResponseWriter, r *http.Request) {
	h := w.Header()
	h.Set("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; "+
		"img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("X-Robots-Tag", "noindex")
	w.WriteHeader(http.StatusOK)
	w.Write(adminHTML)
}
