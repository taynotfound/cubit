package main

import (
	"sync"
	"time"
)

// Limiter is a set of token buckets keyed by "route:ip" or "route:device".
type Limiter struct {
	mu sync.Mutex
	b  map[string]*bucket
}

type bucket struct {
	tokens float64
	last   time.Time
}

func newLimiter() *Limiter { return &Limiter{b: map[string]*bucket{}} }

// allow spends one token from key's bucket, which holds burst tokens and refills at perMin per minute.
func (l *Limiter) allow(key string, burst, perMin float64) bool {
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.b) > 100_000 { // bound memory under a flood; idle buckets are full anyway
		for k, v := range l.b {
			if now.Sub(v.last) > 10*time.Minute {
				delete(l.b, k)
			}
		}
	}
	bk := l.b[key]
	if bk == nil {
		bk = &bucket{tokens: burst, last: now}
		l.b[key] = bk
	}
	bk.tokens = min(burst, bk.tokens+now.Sub(bk.last).Minutes()*perMin)
	bk.last = now
	if bk.tokens < 1 {
		return false
	}
	bk.tokens--
	return true
}
