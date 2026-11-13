package main

import (
	"os"
	"testing"
)

// A board saved before 1.7.2 credits its World Cube winners once, on the first start, and not again after.
func TestWorldWinnersBackfilledOnce(t *testing.T) {
	file := t.TempDir() + "/db.json"
	old := `{"devices":{"dev1":{"name":"Tay","tokenHash":"x","xp":10,"created":1,"runs":1,"code":"18B72243"}},"scores":[],"held":[],
		"world":{"id":2,"seed":5,"removed":[],"clears":{},"started":1,"history":[{"id":1,"winner":"Tay","code":"18B72243","players":1,"arrows":23,"seconds":9,"finished":2}]}}`
	os.WriteFile(file, []byte(old), 0o600)
	for i := 0; i < 2; i++ {
		s, err := openStore(file)
		if err != nil {
			t.Fatal(err)
		}
		if d := s.db.Devices["dev1"]; d.WorldWins != 1 || d.WorldCubes != 1 {
			t.Fatalf("start %d: wins %d, cubes %d; want 1 and 1", i+1, d.WorldWins, d.WorldCubes)
		}
		if err := s.save(); err != nil {
			t.Fatal(err)
		}
	}
}
