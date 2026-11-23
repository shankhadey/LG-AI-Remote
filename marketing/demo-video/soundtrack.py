import numpy as np, wave, json
TM = json.load(open("timing.json"))   # written by room.py: video length and tap times
SR = 48000; BPM = 50; beat = 60 / BPM
rng = np.random.default_rng(3)
CYC = int(TM['len'] // 9.6) + 2; FWD = CYC * 8 * beat + 5.0
N = int(SR * FWD); dry = np.zeros(N)
def hz(m): return 440 * 2 ** ((m - 69) / 12)
def put(sig, t, g):
    i = int(t * SR); j = min(N, i + len(sig)); dry[i:j] += sig[: j - i] * g
def epiano(m, dur):              # soft FM "glass" tone
    t = np.arange(int(SR * dur)) / SR; f = hz(m)
    idx = 1.6 * np.exp(-t * 3.0)
    s = np.sin(2 * np.pi * f * t + idx * np.sin(2 * np.pi * f * t))
    return s * np.exp(-t * 1.4) * np.minimum(1, t / 0.004)
def pad(ms, dur):
    t = np.arange(int(SR * dur)) / SR; s = sum(np.sin(2 * np.pi * hz(m) * (1 + d) * t) for m in ms for d in (-0.0008, 0.0008))
    return s / (2 * len(ms)) * np.minimum(1, t / 0.35) * np.minimum(1, np.maximum(0, (dur - t)) / 0.4)
def bass(m, dur):
    t = np.arange(int(SR * dur)) / SR
    return (np.sin(2 * np.pi * hz(m) * t) + 0.25 * np.sin(4 * np.pi * hz(m) * t)) * np.exp(-t * 1.1) * np.minimum(1, t / 0.01)
BASS = [50, 45, 47, 42, 43, 38, 43, 45]                                       # D A Bm F#m G D G A
CH = [[62, 66, 69], [61, 64, 69], [59, 62, 66], [54, 57, 61], [55, 59, 62], [54, 57, 62], [55, 59, 62], [57, 61, 64]]
MEL = [[78, 76, 74, 73, 71, 69, 71, 73], [74, 73, 71, 69, 67, 66, 67, 64], [66, 69, 74, 73, 71, 74, 73, 71]]
for c in range(CYC):
    for k in range(8):
        t0 = (c * 8 + k) * beat
        put(bass(BASS[k] - 12, beat * 1.8), t0, 0.16)
        put(pad([m - 12 for m in CH[k]], beat * 1.1), t0, 0.12)
        for m in CH[k]: put(epiano(m, beat * 2.8), t0, 0.14)   # the chord, struck softly (audible on phone speakers)
# stereo reverb: convolve with decaying noise (different per ear)
def ir(sec, seed):
    r = np.random.default_rng(seed); n = int(SR * sec); t = np.arange(n) / SR
    h = r.uniform(-1, 1, n) * np.exp(-t * 1.5); h[0] = 2.0; return h
def conv(x, h):
    L = len(x) + len(h) - 1; n = 1 << (L - 1).bit_length()
    return np.fft.irfft(np.fft.rfft(x, n) * np.fft.rfft(h, n), n)[:len(x)]
def lowpass(x, a):
    y = np.empty_like(x); acc = 0.0
    for k in range(len(x)): acc += a * (x[k] - acc); y[k] = acc
    return y
wetL = conv(dry, ir(4.5, 21)); wetR = conv(dry, ir(4.5, 22))
L = 0.25 * dry + 0.75 * wetL / np.abs(wetL).max() * np.abs(dry).max()
R = 0.25 * dry + 0.75 * wetR / np.abs(wetR).max() * np.abs(dry).max()
L = lowpass(L, 0.10); R = lowpass(R, 0.10)              # warm, no sparkle
L, R = L[::-1].copy(), R[::-1].copy()                    # backwards
LEN = TM['len']; n = int(SR * LEN); off = int(SR * 2.4); L, R = L[off:off + n], R[off:off + n]
env = np.ones(n); fi = int(SR * 1.5); fo = int(SR * 2.5)
env[:fi] = np.linspace(0, 1, fi) ** 2; env[-fo:] = np.linspace(1, 0, fo) ** 1.5
L *= env; R *= env
pk = max(np.abs(L).max(), np.abs(R).max()); L /= pk / 0.5; R /= pk / 0.5
# faint taps (not reversed)
def tick():
    m = int(SR * 0.018); t = np.arange(m) / SR
    s = 0.6 * lowpass(rng.uniform(-1, 1, m), 0.5) + 0.4 * np.sin(2 * np.pi * 2600 * t)
    return s * np.exp(-t * 260)
for t in TM['taps']:
    i = int(t * SR); tk = tick() * 0.05; L[i:i + len(tk)] += tk; R[i:i + len(tk)] += tk
st = np.stack([L, R], axis=1)
with wave.open("music.wav", "w") as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((np.clip(st, -1, 1) * 32767).astype(np.int16).tobytes())
print("ok")
