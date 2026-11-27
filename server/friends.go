package main

import (
	"net/http"
	"slices"
	"strings"
)

// Friends are kept on the phone (older apps only ever did that), and since 1.8 here too, so that adding someone can
// tell them: their app shows a request to add you back. Requests carry only the name, level and code of the player
// who sent them, which the receiver needs to add them back anyway.

const maxFriends = 50  // matches the cap on GET /v1/friends
const maxRequests = 50 // pending requests kept per player; the oldest go first

type friendBrief struct {
	Code  string `json:"code"`
	Name  string `json:"name"`
	Level int    `json:"level"`
}

// addFriend adds [code] to the caller's friends. If that player hadn't added the caller already, it leaves them a
// request; if they had (a request is waiting), this accepts it. Done with the store's mu held.
func (s *Store) addFriend(me string, code string) (friendBrief, bool, int, string) {
	d := s.db.Devices[me]
	id := s.byCode(code)
	t := s.db.Devices[id]
	switch {
	case t == nil || t.Banned:
		return friendBrief{}, false, http.StatusNotFound, "no player has that code"
	case id == me:
		return friendBrief{}, false, http.StatusBadRequest, "that's your own code"
	}
	if !slices.Contains(d.Friends, code) {
		if len(d.Friends) >= maxFriends {
			return friendBrief{}, false, http.StatusBadRequest, "at most 50 friends"
		}
		d.Friends = append(d.Friends, code)
	}
	d.Requests = slices.DeleteFunc(d.Requests, func(c string) bool { return c == code }) // accepted, if there was one
	mutual := slices.Contains(t.Friends, d.Code)
	if !mutual && !slices.Contains(t.Requests, d.Code) {
		t.Requests = append(t.Requests, d.Code)
		if len(t.Requests) > maxRequests {
			t.Requests = t.Requests[len(t.Requests)-maxRequests:]
		}
	}
	s.markDirty()
	return friendBrief{code, t.Name, playerLevel(t.XP)}, mutual, 0, ""
}

// dropFriend removes [code] from the caller's friends and requests (unfriend, or ignore a request), and takes back a
// request the caller left them that they haven't answered. Done with the store's mu held.
func (s *Store) dropFriend(me string, code string) {
	d := s.db.Devices[me]
	d.Friends = slices.DeleteFunc(d.Friends, func(c string) bool { return c == code })
	d.Requests = slices.DeleteFunc(d.Requests, func(c string) bool { return c == code })
	if t := s.db.Devices[s.byCode(code)]; t != nil {
		t.Requests = slices.DeleteFunc(t.Requests, func(c string) bool { return c == d.Code })
	}
	s.markDirty()
}

// requestsFor lists who added the caller and is waiting to be added back, newest first, leaving out banned players.
// Done with the store's mu held.
func (s *Store) requestsFor(me string) []friendBrief {
	out := []friendBrief{}
	reqs := s.db.Devices[me].Requests
	for i := len(reqs) - 1; i >= 0; i-- {
		if t := s.db.Devices[s.byCode(reqs[i])]; t != nil && !t.Banned {
			out = append(out, friendBrief{reqs[i], t.Name, playerLevel(t.XP)})
		}
	}
	return out
}

func friendCode(r *http.Request) (string, bool) {
	c := strings.ToUpper(strings.ReplaceAll(r.PathValue("code"), "-", ""))
	if len(c) != 8 || strings.Trim(c, "0123456789ABCDEF") != "" {
		return "", false
	}
	return c, true
}

// POST /v1/friends/{code}: add a friend (and send them a request, or accept theirs).
func (a *App) friendAdd(w http.ResponseWriter, r *http.Request) {
	dev, _, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	c, ok := friendCode(r)
	if !ok {
		fail(w, http.StatusBadRequest, "a friend code is 8 letters and digits")
		return
	}
	if !a.limits.allow("friend:"+dev, 30, 10) {
		fail(w, http.StatusTooManyRequests, "slow down")
		return
	}
	a.store.mu.Lock()
	f, mutual, status, msg := a.store.addFriend(dev, c)
	a.store.mu.Unlock()
	if status != 0 {
		fail(w, status, msg)
		return
	}
	send(w, http.StatusOK, map[string]any{"friend": f, "mutual": mutual})
}

// DELETE /v1/friends/{code}: remove a friend, or ignore their request.
func (a *App) friendDrop(w http.ResponseWriter, r *http.Request) {
	dev, _, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	c, ok := friendCode(r)
	if !ok {
		fail(w, http.StatusBadRequest, "a friend code is 8 letters and digits")
		return
	}
	if !a.limits.allow("friend:"+dev, 30, 10) {
		fail(w, http.StatusTooManyRequests, "slow down")
		return
	}
	a.store.mu.Lock()
	a.store.dropFriend(dev, c)
	a.store.mu.Unlock()
	send(w, http.StatusOK, map[string]bool{"ok": true})
}

// GET /v1/me/friends: the caller's friends as the server has them (so a restored backup gets them back), the
// requests and notes waiting for them, and what their friends did this week.
func (a *App) myFriends(w http.ResponseWriter, r *http.Request) {
	dev, d, code := a.auth(r)
	if code != 0 {
		fail(w, code, "not signed in")
		return
	}
	a.store.mu.Lock()
	friends := slices.Clone(d.Friends)
	reqs := a.store.requestsFor(dev)
	inbox := a.store.inboxFor(d)
	feed := a.store.feedFor(d, a.now())
	a.store.mu.Unlock()
	if friends == nil {
		friends = []string{}
	}
	send(w, http.StatusOK, map[string]any{"friends": friends, "requests": reqs, "inbox": inbox, "feed": feed})
}
