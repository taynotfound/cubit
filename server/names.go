package main

import (
	"strings"
	"unicode"
)

// Names are public on the board, so slurs and the worst abuse don't get on it. This is a short list aimed at what
// actually turns up; it won't stop someone determined, and the admin rename/ban endpoints are the backstop for those.
// Players who pick one of these show as "Player" (see register), the same as a name that doesn't clean.

// lookalikes are written the way they read: digits and accented or Cyrillic letters standing in for Latin ones.
var lookalikes = map[rune]rune{
	'0': 'o', '1': 'i', '3': 'e', '4': 'a', '5': 's', '7': 't',
	'à': 'a', 'á': 'a', 'â': 'a', 'ã': 'a', 'ä': 'a', 'å': 'a', 'è': 'e', 'é': 'e', 'ê': 'e', 'ë': 'e',
	'ì': 'i', 'í': 'i', 'î': 'i', 'ï': 'i', 'ò': 'o', 'ó': 'o', 'ô': 'o', 'õ': 'o', 'ö': 'o', 'ø': 'o',
	'ù': 'u', 'ú': 'u', 'û': 'u', 'ü': 'u', 'ñ': 'n', 'ç': 'c',
	'а': 'a', 'е': 'e', 'о': 'o', 'і': 'i', 'с': 'c', 'р': 'p', 'х': 'x', 'у': 'y', 'к': 'k', 'н': 'h', 'г': 'r',
}

// skeleton lower-cases s, undoes lookalikes, drops everything but letters and squeezes repeats: vowels to one,
// consonants to at most keep. "N1iiGGGa" is "nigga" with keep 2 and "niga" with keep 1.
func skeleton(s string, keep int) string {
	var b []rune
	run := 0
	for _, r := range strings.ToLower(s) {
		if m, ok := lookalikes[r]; ok {
			r = m
		}
		if !unicode.IsLetter(r) {
			continue
		}
		if len(b) > 0 && b[len(b)-1] == r {
			run++
		} else {
			run = 1
		}
		if run > 1 && (strings.ContainsRune("aeiou", r) || run > keep) {
			continue
		}
		b = append(b, r)
	}
	return string(b)
}

var (
	// Anywhere in the name, doubled consonants kept: "neger" must not catch Schwarzenegger, "nigg" must not catch Niger.
	blockedDoubled = []string{"nigg", "fagg", "neger"}
	// Anywhere in the name, repeats squeezed out (so these are spelled without doubled letters).
	blockedAnywhere = []string{"fuck", "hitler", "siegheil", "whitepower", "pedophil", "paedophil", "hurensohn", "fotze",
		"schwuchtel", "kanake", "judensau", "wichser", "bitch", "whore", "trany", "chink"}
	// Only as a whole word or the whole name, because they sit inside innocent ones (Nazim, Scunthorpe, grape, spice).
	blockedWords = map[string]bool{"niga": true, "nigas": true, "nigaz": true, "nigah": true, "fag": true, "fags": true,
		"nazi": true, "nazis": true, "cunt": true, "cunts": true, "rape": true, "rapist": true, "spic": true, "slut": true,
		"kys": true}
)

// offensive reports whether a name carries one of the blocked terms.
func offensive(name string) bool {
	if two := skeleton(name, 2); containsAny(two, blockedDoubled) {
		return true
	}
	if one := skeleton(name, 1); containsAny(one, blockedAnywhere) || blockedWords[one] {
		return true
	}
	low := strings.ToLower(name)
	if strings.Contains(low, "kkk") || strings.Contains(low, "1488") {
		return true
	}
	for _, w := range strings.FieldsFunc(name, func(r rune) bool { return r == ' ' || r == '_' || r == '.' || r == '-' }) {
		if blockedWords[skeleton(w, 1)] {
			return true
		}
	}
	return false
}

func containsAny(s string, terms []string) bool {
	for _, t := range terms {
		if strings.Contains(s, t) {
			return true
		}
	}
	return false
}
