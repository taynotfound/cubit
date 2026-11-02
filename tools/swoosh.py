# Swoosh = coloured noise through a band-pass whose centre sweeps up then settles, under a soft rise/long fall.
import numpy as np, wave, sys
SR = 44100
def svf_bandpass(x, fc, q):
    # Chamberlin state-variable filter with per-sample cutoff
    low = band = 0.0; out = np.empty_like(x)
    for i in range(len(x)):
        f = 2*np.sin(np.pi*fc[i]/SR); high = x[i] - low - band/q
        band += f*high; low += f*band; out[i] = band
    return out
def swoosh(seed, dur, f0, f1, f2, peak_at):
    rng = np.random.default_rng(seed); n = int(dur*SR); t = np.arange(n)/n
    white = rng.standard_normal(n)
    pink = np.cumsum(white); pink -= np.convolve(pink, np.ones(400)/400, "same")   # tilt down: pinkish/brown
    noise = pink/np.abs(pink).max()   # no white noise: that was the hiss that stung
    # centre frequency: rise to f1 at peak_at, then ease back to f2
    fc = np.where(t < peak_at, f0 + (f1-f0)*np.sin(np.pi/2*t/peak_at),
                  f2 + (f1-f2)*np.cos(np.pi/2*(t-peak_at)/(1-peak_at)))
    y = svf_bandpass(noise, fc, 0.6) + 0.7*svf_bandpass(noise, fc*0.5, 0.5)        # body + low air
    env = np.where(t < peak_at, np.sin(np.pi/2*t/peak_at)**3, np.exp(-3.5*(t-peak_at)/(1-peak_at)))
    y = np.convolve(y, np.ones(12)/12, 'same')   # extra soft low-pass
    y *= env
    y[-int(0.01*SR):] *= np.linspace(1, 0, int(0.01*SR))
    return y / np.abs(y).max() * 0.35    # -9 dBFS
for i, (seed, dur, f0, f1, f2, pk) in enumerate([(11, 0.36, 140, 520, 260, 0.45), (23, 0.32, 160, 480, 240, 0.42), (37, 0.40, 130, 560, 280, 0.48)]):
    y = swoosh(seed, dur, f0, f1, f2, pk)
    with wave.open(f"{sys.argv[1]}/swoosh_{i+1}.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((y*32767).astype(np.int16).tobytes())
