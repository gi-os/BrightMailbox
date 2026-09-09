#!/usr/bin/env python3
"""
BrightMailbox notification sounds — synthesized, no samples.

Every sound in this file is generated from arithmetic. Nothing is recorded and
nothing is sampled, so there is no clip to license and the whole sound set is
about 3 KB of source instead of a folder of audio.

Why not the actual "You've Got Mail": that is Elwood Edwards' 1989 voice
recording and it is somebody's property. A synthesized mail slot is not.

Run:  python3 make_sounds.py [outdir]
Out:  48 kHz, mono, 16-bit PCM WAV — what Android wants for a channel sound.
"""

import math
import struct
import sys
import wave
from pathlib import Path

import numpy as np

SR = 48_000
PEAK = 0.5  # -6 dBFS. A notification should not be the loudest thing the phone does.


# ─────────────────────────────────────────────────────────────── primitives ──

def silence(dur):
    return np.zeros(int(SR * dur))


def env_exp(n, decay, attack=0.002):
    """Exponential decay with a short attack so nothing starts on a click."""
    t = np.arange(n) / SR
    e = np.exp(-t / decay)
    a = int(SR * attack)
    if a > 1:
        e[:a] *= np.linspace(0, 1, a) ** 2
    return e


def modal(spec, dur, detune=0.0):
    """Sum of decaying sinusoids.  spec = [(freq, amp, decay_seconds), ...]

    Inharmonic partial ratios are what separate metal from a sine beep. A pure
    harmonic series reads as electronic; struck physical objects never are.
    """
    n = int(SR * dur)
    t = np.arange(n) / SR
    out = np.zeros(n)
    for i, (f, a, d) in enumerate(spec):
        f = f * (1.0 + detune * (i % 3 - 1) * 0.001)
        out += a * np.sin(2 * np.pi * f * t + i * 0.7) * env_exp(n, d)
    return out


def bandpass(x, f0, q):
    """RBJ cookbook bandpass, constant skirt gain. Hand-rolled — no scipy."""
    w0 = 2 * math.pi * f0 / SR
    alpha = math.sin(w0) / (2 * q)
    b0, b1, b2 = q * alpha, 0.0, -q * alpha
    a0, a1, a2 = 1 + alpha, -2 * math.cos(w0), 1 - alpha
    b0, b1, b2, a1, a2 = b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0

    y = np.zeros_like(x)
    x1 = x2 = y1 = y2 = 0.0
    for i, xn in enumerate(x):
        yn = b0 * xn + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        y[i] = yn
        x2, x1 = x1, xn
        y2, y1 = y1, yn
    return y


def noise_hit(dur, f0, q, decay, seed=0):
    """Filtered noise burst — the transient half of anything physical."""
    rng = np.random.default_rng(seed)
    n = int(SR * dur)
    return bandpass(rng.standard_normal(n), f0, q) * env_exp(n, decay, attack=0.0005)


def place(canvas, sound, at):
    """Mix `sound` into `canvas` starting at `at` seconds."""
    i = int(SR * at)
    end = min(len(canvas), i + len(sound))
    canvas[i:end] += sound[: end - i]
    return canvas


def trim(x, floor_db=-60, pad=0.030):
    """Cut dead tail. A notification file should not outlast its own sound."""
    env = np.abs(x)
    live = np.where(env > 10 ** (floor_db / 20) * env.max())[0]
    if not len(live):
        return x
    return x[: min(len(x), live[-1] + int(SR * pad))]


def finish(x, fade=0.012):
    """Trim, normalize, then fade the tail so the file never ends on a step."""
    x = trim(x)
    peak = np.max(np.abs(x))
    if peak > 0:
        x = x / peak * PEAK
    f = int(SR * fade)
    if f < len(x):
        x[-f:] *= np.linspace(1, 0, f) ** 2
    return x


def phone_speaker(x):
    """Rough LP3 earpiece-grade speaker sim, for auditioning only.

    A ~10 mm driver has no output below roughly 500 Hz and a broad presence
    lift. Judging a notification on laptop speakers is how you ship a sound
    that is inaudible in a pocket.
    """
    n = len(x)
    S = np.fft.rfft(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    resp = np.ones_like(f)
    resp *= 1 / (1 + (400 / np.maximum(f, 1)) ** 4)        # 24 dB/oct below 400
    resp *= 1 + 0.9 * np.exp(-((f - 3000) / 1600) ** 2)     # presence bump
    resp *= 1 / (1 + (np.maximum(f, 1) / 11000) ** 3)       # top rolloff
    y = np.fft.irfft(S * resp, n)
    p = np.abs(y).max()
    return y / p * PEAK if p > 0 else y


def write(path, x):
    data = np.clip(x, -1, 1)
    pcm = (data * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    return len(data) / SR


# ────────────────────────────────────────────────────────────────── sounds ──

def slot():
    """A letter through a brass mail slot: the flap, then paper landing.

    Longer decays than a real flap has. A true 50 ms clank reads as a UI error
    tone on a phone speaker; stretching the brass to ~150 ms is what makes it
    read as an object instead of a click.
    """
    c = silence(0.90)
    flap = modal([(521, 1.00, 0.150), (874, 0.62, 0.105), (1310, 0.35, 0.070),
                  (1979, 0.18, 0.045), (2740, 0.09, 0.028)], 0.55)
    place(c, flap, 0.00)
    place(c, noise_hit(0.05, 2600, 1.1, 0.008, seed=1) * 0.22, 0.00)
    # the paper itself, arriving a beat later — quiet, it is only texture
    place(c, noise_hit(0.28, 1400, 0.6, 0.050, seed=2) * 0.16, 0.150)
    place(c, modal([(233, 0.5, 0.045), (392, 0.3, 0.030)], 0.20) * 0.35, 0.155)
    return c


def flap():
    """Just the brass flap. Shortest thing here — 180 ms and gone."""
    c = silence(0.42)
    place(c, modal([(438, 1.0, 0.048), (735, 0.70, 0.036), (1102, 0.44, 0.026),
                    (1663, 0.24, 0.018), (2381, 0.12, 0.012)], 0.28), 0.0)
    place(c, noise_hit(0.05, 3100, 1.0, 0.009, seed=4) * 0.6, 0.0)
    return c


def bell():
    """Postman's bicycle bell. Inharmonic, with the beating that makes it sing."""
    c = silence(1.30)
    partials = [(1046, 1.00, 0.62), (1049, 0.85, 0.60),   # detuned pair = beating
                (2492, 0.55, 0.34), (2498, 0.45, 0.33),
                (3810, 0.26, 0.20), (5230, 0.13, 0.12), (6890, 0.06, 0.07)]
    place(c, modal(partials, 1.25), 0.0)
    place(c, noise_hit(0.03, 5200, 1.2, 0.005, seed=5) * 0.35, 0.0)
    return c


def two_tone():
    """Descending major third, E5 to C5. The doorbell everyone already knows."""
    c = silence(1.20)
    for i, (f, at) in enumerate([(659.25, 0.00), (523.25, 0.19)]):
        place(c, modal([(f, 1.0, 0.34), (f * 2, 0.13, 0.16), (f * 3, 0.04, 0.09)],
                       0.95 - at), at)
    return c


def ask():
    """Rising fourth, G4 to C5. An arrival phrased as a question."""
    c = silence(1.00)
    for f, at in [(392.00, 0.00), (523.25, 0.155)]:
        place(c, modal([(f, 1.0, 0.30), (f * 2, 0.16, 0.15), (f * 3, 0.05, 0.08)],
                       0.80 - at), at)
    return c


def music_box():
    """Three tines, C5 G5 E5. A music box is a comb, so the partials are stretched."""
    c = silence(1.60)
    for f, at, amp in [(523.25, 0.00, 1.0), (783.99, 0.135, 0.9), (659.25, 0.270, 0.85)]:
        # stretched partials (×2.04, ×3.12) — a struck bar, not a string
        place(c, modal([(f, 1.0, 0.50), (f * 2.04, 0.30, 0.26),
                        (f * 3.12, 0.11, 0.14), (f * 4.9, 0.04, 0.07)],
                       1.5 - at) * amp, at)
    return c


def ticks():
    """Two dry wooden ticks. The most restrained option — barely a sound."""
    c = silence(0.40)
    for at, f in [(0.000, 1850), (0.105, 1560)]:
        place(c, modal([(f, 1.0, 0.012), (f * 1.61, 0.5, 0.009)], 0.10), at)
        place(c, noise_hit(0.03, f * 1.4, 2.0, 0.004, seed=int(at * 1000) + 7) * 0.7, at)
    return c


def stamp():
    """A postmark: thud of the die, tick of the handle.

    Fundamental sits at 330 Hz, not the ~190 Hz a real desk stamp gives you.
    The LP3 speaker has nothing below about 400 Hz, so a physically honest
    postmark would arrive as silence with a tick on the end of it.
    """
    c = silence(0.55)
    place(c, modal([(330, 1.0, 0.070), (528, 0.55, 0.045),
                    (855, 0.26, 0.026), (1290, 0.10, 0.016)], 0.30), 0.0)
    place(c, noise_hit(0.05, 900, 0.8, 0.016, seed=8) * 0.45, 0.0)
    place(c, noise_hit(0.03, 4200, 1.6, 0.006, seed=9) * 0.28, 0.020)
    return c


def paper():
    """A rustle that resolves into one soft tone. Mail, then the note it carries."""
    c = silence(1.20)
    rng = np.random.default_rng(11)
    n = int(SR * 0.30)
    swell = bandpass(rng.standard_normal(n), 3400, 0.6)
    swell *= np.concatenate([np.linspace(0, 1, int(n * 0.55)) ** 1.6,
                             np.linspace(1, 0, n - int(n * 0.55)) ** 1.2])
    place(c, swell * 0.30, 0.0)
    place(c, modal([(587.33, 1.0, 0.42), (1174.7, 0.14, 0.20),
                    (1762, 0.04, 0.10)], 0.85) * 0.85, 0.215)
    return c


def tine():
    """One struck metal tine, left to ring. Quietest thing that still says arrived."""
    c = silence(1.60)
    place(c, modal([(880, 1.00, 0.70), (880 * 2.76, 0.22, 0.30),
                    (880 * 5.40, 0.07, 0.14), (880 * 8.93, 0.02, 0.07)], 1.55), 0.0)
    place(c, noise_hit(0.02, 4800, 1.4, 0.004, seed=12) * 0.22, 0.0)
    return c


SOUNDS = {
    "slot":      (slot,      "Mail Slot",   "Brass flap, then paper landing. The literal one."),
    "flap":      (flap,      "Flap",        "Just the slot's flap. 180 ms."),
    "bell":      (bell,      "Bicycle Bell","Postman's bell, with the beat two detuned partials give."),
    "two_tone":  (two_tone,  "Two Tone",    "E5 down to C5. Doorbell grammar."),
    "ask":       (ask,       "Ask",         "G4 up to C4's octave. Arrival as a question."),
    "music_box": (music_box, "Music Box",   "C-G-E on stretched partials."),
    "ticks":     (ticks,     "Ticks",       "Two dry wooden ticks. Barely there."),
    "stamp":     (stamp,     "Postmark",    "Dull die thud plus the handle's tick."),
    "paper":     (paper,     "Paper",       "Rustle resolving into one D5."),
    "tine":      (tine,      "Tine",        "A single struck tine, left ringing."),
}


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    out.mkdir(parents=True, exist_ok=True)
    (out / "phone-sim").mkdir(exist_ok=True)
    for key, (fn, name, note) in SOUNDS.items():
        x = finish(fn())
        dur = write(out / f"{key}.wav", x)
        write(out / "phone-sim" / f"{key}.wav", phone_speaker(x))
        print(f"{key:10s} {dur*1000:6.0f} ms   {name} — {note}")


if __name__ == "__main__":
    main()
