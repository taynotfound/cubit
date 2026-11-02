# Two seamless ambient loops for Cubit. Every note is written modulo the loop length and the reverb is a circular
# FFT convolution, so the loop point has no seam.
import numpy as np, wave, sys
SR = 44100
rng = np.random.default_rng(7)

def midi(m): return 440 * 2 ** ((m - 69) / 12)

def render(bpm, bars, chords, arp, pulse, seed):
    beat = 60 / bpm; L = int(bars * 4 * beat * SR); out = np.zeros(L)
    def put(sig, start):
        i = int(start * SR) % L; n = len(sig)
        first = min(n, L - i); out[i:i + first] += sig[:first]
        if n > first: out[:n - first] += sig[first:]
    def tone(f, dur, harm, att, rel, amp, detune=0.0):
        t = np.arange(int((dur + rel) * SR)) / SR
        y = sum(a * np.sin(2 * np.pi * f * k * (1 + detune * (j % 2 * 2 - 1)) * t + j) for j, (k, a) in enumerate(harm))
        env = np.minimum(t / att, 1) * np.where(t < dur, 1, np.exp(-(t - dur) / rel * 4))
        return amp * y * env
    bar = 4 * beat
    for b in range(bars):
        ch = chords[b % len(chords)]
        # pad: soft stacked partials, two slightly detuned voices
        for m in ch:
            for d in (0.003, -0.002):
                put(tone(midi(m), bar, [(1, 1), (2, .28), (3, .1), (4, .05)], 0.9, 1.4, 0.045, d), b * bar)
        put(tone(midi(ch[0] - 12), bar * 0.95, [(1, 1), (2, .15)], 0.05, 0.6, 0.11), b * bar)    # bass
        # bell arpeggio: sine + inharmonic shimmer, quick decay
        for k, step in enumerate(arp):
            if step is None: continue
            m = ch[step % len(ch)] + 12 * (1 + step // len(ch))
            t = np.arange(int(1.6 * SR)) / SR
            y = (np.sin(2 * np.pi * midi(m) * t) + .25 * np.sin(2 * np.pi * midi(m) * 2.76 * t) * np.exp(-t * 9)) * np.exp(-t * 3.2)
            y[:200] *= np.linspace(0, 1, 200)
            put(0.06 * y * (0.8 + 0.4 * rng.random()), b * bar + k * beat / 2 + rng.normal(0, 0.006))
        if pulse:  # soft shaker on the offbeats
            for k in range(8):
                n = int(0.05 * SR); nz = rng.standard_normal(n); nz = np.diff(nz, prepend=0)  # high-passed
                put(0.012 * (1.4 if k % 2 else 0.7) * nz * np.exp(-np.arange(n) / n * 6), b * bar + k * beat / 2)
    # gentle low-pass: a one-pole applied twice, done circularly in the frequency domain so the loop stays seamless
    a = np.exp(-2 * np.pi * 3500 / SR); w = 2 * np.pi * np.fft.fftfreq(L)
    H = ((1 - a) / (1 - a * np.exp(-1j * w))) ** 2
    out = np.real(np.fft.ifft(np.fft.fft(out) * H))
    # circular reverb: decaying noise impulse response
    ir_n = int(2.2 * SR); t = np.arange(ir_n) / SR
    ir = rng.standard_normal(ir_n) * np.exp(-t * 3.0); ir[0] = 0
    irL = np.zeros(L); irL[:ir_n] = ir / np.sqrt((ir ** 2).sum()) * 0.5
    wet = np.real(np.fft.ifft(np.fft.fft(out) * np.fft.fft(irL)))
    mix = out + 0.55 * wet
    return mix / np.abs(mix).max() * 0.6

def save(y, name):
    with wave.open(f"{sys.argv[1]}/{name}.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((y * 32767).astype(np.int16).tobytes())

# menu: slow and open. Fmaj7 Am7 Dm9 Bbmaj7
save(render(72, 8, [[53, 57, 60, 64], [57, 60, 64, 67], [50, 53, 57, 64], [46, 50, 53, 57]],
            [0, None, 2, None, 3, None, 1, None], False, 1), "music_menu")
# play: a touch more motion. Cmaj9 Em7 Am7 Fmaj7
save(render(88, 8, [[48, 52, 55, 59], [52, 55, 59, 62], [57, 60, 64, 67], [53, 57, 60, 64]],
            [0, 2, 1, 3, 2, 4, 3, 1], True, 2), "music_play")
