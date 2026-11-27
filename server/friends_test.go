package main

import "testing"

func (h *harness) player(name string) (token, code string) {
	h.app.limits = newLimiter() // tests sign up more players from one address than the real limit allows
	_, reg := h.call("POST", "/v1/register", "", map[string]string{"name": name})
	token = reg["token"].(string)
	_, me := h.call("GET", "/v1/me", token, nil)
	return token, me["code"].(string)
}

func (h *harness) requests(token string) (friends []any, reqs []map[string]any) {
	st, out := h.call("GET", "/v1/me/friends", token, nil)
	if st != 200 {
		h.t.Fatalf("GET /v1/me/friends: %d %v", st, out)
	}
	for _, r := range out["requests"].([]any) {
		reqs = append(reqs, r.(map[string]any))
	}
	return out["friends"].([]any), reqs
}

func TestFriendRequestFlow(t *testing.T) {
	h := newHarness(t)
	ann, annCode := h.player("Ann")
	bob, bobCode := h.player("Bob")

	// Ann adds Bob: Bob gets a request from Ann, Ann doesn't get one back.
	st, out := h.call("POST", "/v1/friends/"+bobCode, ann, nil)
	if st != 200 || out["mutual"] != false || out["friend"].(map[string]any)["name"] != "Bob" {
		t.Fatalf("add: %d %v", st, out)
	}
	if f, r := h.requests(bob); len(f) != 0 || len(r) != 1 || r[0]["code"] != annCode || r[0]["name"] != "Ann" {
		t.Fatalf("Bob should have Ann's request: %v %v", f, r)
	}
	if f, r := h.requests(ann); len(f) != 1 || f[0] != bobCode || len(r) != 0 {
		t.Fatalf("Ann's friends: %v %v", f, r)
	}
	// Adding again doesn't send a second request.
	h.call("POST", "/v1/friends/"+bobCode, ann, nil)
	if _, r := h.requests(bob); len(r) != 1 {
		t.Fatalf("duplicate request: %v", r)
	}

	// Bob adds Ann back (with a dash, lower case): that accepts the request, and Ann gets none.
	st, out = h.call("POST", "/v1/friends/"+annCode[:4]+"-"+annCode[4:], bob, nil)
	if st != 200 || out["mutual"] != true {
		t.Fatalf("add back: %d %v", st, out)
	}
	if _, r := h.requests(bob); len(r) != 0 {
		t.Fatalf("request should be gone: %v", r)
	}
	if _, r := h.requests(ann); len(r) != 0 {
		t.Fatalf("Ann got a request for an accepted friendship: %v", r)
	}
}

func TestFriendIgnoreAndWithdraw(t *testing.T) {
	h := newHarness(t)
	ann, annCode := h.player("Ann")
	bob, bobCode := h.player("Bob")
	cat, _ := h.player("Cat")

	h.call("POST", "/v1/friends/"+bobCode, ann, nil)
	if st, _ := h.call("DELETE", "/v1/friends/"+annCode, bob, nil); st != 200 { // Bob ignores Ann
		t.Fatal(st)
	}
	if _, r := h.requests(bob); len(r) != 0 {
		t.Fatalf("ignored request still there: %v", r)
	}

	// Cat adds Bob then removes him: the request is taken back.
	h.call("POST", "/v1/friends/"+bobCode, cat, nil)
	if _, r := h.requests(bob); len(r) != 1 {
		t.Fatalf("Cat's request: %v", r)
	}
	h.call("DELETE", "/v1/friends/"+bobCode, cat, nil)
	if _, r := h.requests(bob); len(r) != 0 {
		t.Fatalf("withdrawn request still there: %v", r)
	}
}

func TestFriendAddRejects(t *testing.T) {
	h := newHarness(t)
	ann, annCode := h.player("Ann")
	for path, want := range map[string]int{
		"/v1/friends/" + annCode: 400, // yourself
		"/v1/friends/ABCD1234":   404, // nobody (codes are random, so this one is free in a fresh store)
		"/v1/friends/nothex!!":   400,
	} {
		if st, out := h.call("POST", path, ann, nil); st != want {
			t.Errorf("%s: %d %v, want %d", path, st, out, want)
		}
	}
	if st, _ := h.call("POST", "/v1/friends/ABCD1234", "", nil); st != 401 {
		t.Errorf("unauthenticated add: %d", st)
	}

	// A banned player's request disappears from the list.
	bob, bobCode := h.player("Bob")
	h.call("POST", "/v1/friends/"+annCode, bob, nil)
	h.app.store.mu.Lock()
	h.app.store.db.Devices[h.app.store.byCode(bobCode)].Banned = true
	h.app.store.mu.Unlock()
	if _, r := h.requests(ann); len(r) != 0 {
		t.Fatalf("banned player's request shown: %v", r)
	}
}
