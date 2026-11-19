# The game-feel sounds that answer play: a bell for each clear in a combo (the app plays it a scale step higher each
# time, so a run of clears climbs like a little tune), a rising "now!" chime when the pink arrow's way comes clear, and
# a soft boom for bombs. In C to sit inside the play music (Cmaj9 Em7 Am7 Fmaj7). Usage: python3 chimes.py <out dir>
import numpy as np, wave, sys
SR = 44100
def midi(m): return 440 * 2 ** ((m - 69) / 12)
def bell(f, dur, bright=1.0):
    t = np.arange(int(dur * SR)) / SR
    # a marimba-ish bell: the fundamental, a soft octave, and a quick inharmonic "tink" for the attack
    y = (np.sin(2 * np.pi * f * t) * np.exp(-t * 5.5)
         + 0.35 * np.sin(2 * np.pi * f * 2 * t) * np.exp(-t * 9)
         + 0.22 * bright * np.sin(2 * np.pi * f * 3.98 * t) * np.exp(-t * 28)
         + 0.12 * bright * np.sin(2 * np.pi * f * 9.1 * t) * np.exp(-t * 60))
    y[:110] *= np.linspace(0, 1, 110)
    return y
def save(y, name, peak):
    y = y / np.abs(y).max() * peak
    y[-200:] *= np.linspace(1, 0, 200)
    with wave.open(f"{sys.argv[1]}/{name}.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((y * 32767).astype(np.int16).tobytes())

# combo bell, on G4: the app pitches it from C4 (rate 0.67) up to G5 (rate 2.0)
save(bell(midi(67), 0.9), "sfx_combo", 0.5)

# "now!": three quick notes up a C major arpeggio (G4 C5 E5) then a held G5 with a shimmer
n = int(1.4 * SR); y = np.zeros(n)
for k, m in enumerate([67, 72, 76, 79]):
    b = bell(midi(m), 1.4 - k * 0.07, bright=0.8) * (0.7 + 0.1 * k)
    i = int(k * 0.07 * SR); y[i:i + len(b)] += b[:n - i]
t = np.arange(n) / SR
y += 0.08 * np.sin(2 * np.pi * midi(91) * t) * np.exp(-t * 4) * np.clip((t - 0.21) * 40, 0, 1)
save(y, "sfx_ready", 0.55)

# bomb: a pitched-down thump plus a burst of low noise
rng = np.random.default_rng(3)
n = int(0.7 * SR); t = np.arange(n) / SR
f = 120 * np.exp(-t * 7) + 38
thump = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 6)
noise = np.convolve(rng.standard_normal(n), np.ones(60) / 60, "same") * np.exp(-t * 9) * 3
save(thump + noise * 0.5, "sfx_boom", 0.6)
