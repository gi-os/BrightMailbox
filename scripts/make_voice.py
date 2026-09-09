#!/usr/bin/env python3
"""
"You got mail" — synthesized speech, no recording.

A Klatt-style cascade formant synthesizer in about 200 lines of numpy. A
glottal pulse train drives five time-varying resonators; stops are silence
plus a noise burst; the lips are a first difference. Nothing is sampled, so
this owes nobody anything — the AOL clip is Elwood Edwards' 1989 recording
and is somebody's property. This is arithmetic that happens to talk.

It will not sound like a person. It sounds like a speech chip, which for a
phone with three colors and a scroll wheel is arguably the correct register.

Run:  python3 make_voice.py [outdir]
"""

import sys
from pathlib import Path

import numpy as np

from make_sounds import SR, PEAK, finish, write, phone_speaker, silence, place, slot

# ────────────────────────────────────────────────────────────── the vocoder ──

def glottal(f0, open_quotient=0.62):
    """Rosenberg glottal pulse train over a per-sample pitch track.

    The pulse SHAPE is what stops this sounding like a buzzer: a sawtooth has
    every harmonic at equal strength, a real glottis rolls off about
    -12 dB/octave and that rolloff is most of what reads as "voice".
    """
    out = np.zeros(len(f0))
    n = 0
    while n < len(out):
        T = max(int(SR / max(f0[n], 50.0)), 24)
        n1 = max(int(T * open_quotient * 0.68), 2)   # opening phase
        n2 = max(int(T * open_quotient * 0.32), 1)   # closing phase (sharper)
        p = np.concatenate([
            0.5 * (1 - np.cos(np.pi * np.arange(n1) / n1)),
            np.cos(np.pi * np.arange(n2) / (2 * n2)),
        ])
        end = min(len(out), n + len(p))
        out[n:end] += p[: end - n]
        n += T
    return out


def resonator(x, f, bw):
    """Two-pole resonator with per-sample coefficients.

    Coefficients are computed vectorized up front; only the two-tap recursion
    has to be a real loop, which keeps a 1-second utterance under a second.
    """
    c = -np.exp(-2 * np.pi * bw / SR)
    b = 2 * np.exp(-np.pi * bw / SR) * np.cos(2 * np.pi * f / SR)
    a = 1 - b - c
    y = np.zeros_like(x)
    y1 = y2 = 0.0
    for n in range(len(x)):
        y1, y2 = a[n] * x[n] + b[n] * y1 + c[n] * y2, y1
        y[n] = y1
    return y


def track(keys, n):
    """Linear-interpolate (time, value) keyframes onto a sample grid."""
    t = np.array([k[0] for k in keys])
    v = np.array([k[1] for k in keys])
    return np.interp(np.arange(n) / SR, t, v)


# ───────────────────────────────────────────────────────── the utterance ──
#
# "You got mail"  —  /juː  gɒt  meɪl/
#
# Formant targets are the standard American English values, pushed slightly
# apart: on a 10 mm speaker under-articulated synthesis turns to mush, and the
# listener already knows what the phrase is going to be.

DUR = 0.98

F1 = [(0.00, 300), (0.06, 305), (0.13, 320), (0.19, 330), (0.20, 300), (0.25, 300),
      (0.29, 700), (0.33, 745), (0.39, 720), (0.41, 400), (0.46, 400),
      (0.50, 260), (0.565, 275), (0.61, 590), (0.70, 445), (0.78, 385),
      (0.92, 360), (0.98, 355)]

F2 = [(0.00, 2150), (0.06, 1500), (0.13, 880), (0.19, 1000), (0.20, 1100), (0.25, 1200),
      (0.29, 1150), (0.33, 1120), (0.39, 1210), (0.41, 1600), (0.46, 1700),
      (0.50, 1150), (0.565, 1220), (0.61, 1880), (0.70, 2160), (0.78, 950),
      (0.92, 870), (0.98, 860)]

F3 = [(0.00, 2900), (0.13, 2250), (0.25, 2300), (0.33, 2500), (0.46, 2600),
      (0.50, 2320), (0.61, 2560), (0.70, 2680), (0.78, 2720), (0.98, 2700)]

# Bandwidth. The nasal /m/ is a wide, heavily damped B1 — that damping is what
# makes it read as a nose rather than a very quiet vowel.
B1 = [(0.00, 70), (0.13, 60), (0.29, 80), (0.39, 85),
      (0.50, 260), (0.565, 250), (0.61, 75), (0.78, 95), (0.98, 120)]

# Voicing amplitude. Zeros are stop closures — the silence IS the consonant.
AV = [(0.00, 0.45), (0.05, 0.95), (0.13, 1.00), (0.185, 0.85),
      (0.195, 0.07), (0.250, 0.07),                      # /g/ closure, voice bar
      (0.275, 0.85), (0.33, 1.00), (0.385, 0.80),
      (0.405, 0.00), (0.455, 0.00),                      # /t/ closure, fully silent
      (0.495, 0.50), (0.565, 0.62),                      # /m/ murmur
      (0.61, 1.00), (0.70, 0.98), (0.78, 0.85),
      (0.90, 0.55), (0.98, 0.00)]

# Pitch. A falling statement contour with the peak on the stressed word.
# A flat F0 is the single biggest giveaway of machine speech.
F0 = [(0.00, 128), (0.13, 122), (0.19, 119), (0.29, 133), (0.39, 127),
      (0.50, 139), (0.62, 154), (0.72, 143), (0.86, 112), (0.98, 92)]


def utterance(jitter=0.006, shimmer=0.05, seed=3):
    n = int(SR * DUR)
    rng = np.random.default_rng(seed)

    f0 = track(F0, n)
    # Jitter: a perfectly periodic glottis is the other giveaway. A slow random
    # walk of well under a percent is enough to stop it sounding like a tone.
    walk = np.cumsum(rng.standard_normal(n)) / np.sqrt(n)
    f0 = f0 * (1 + jitter * walk / (np.abs(walk).max() or 1) * 6)

    src = glottal(f0)
    src *= 1 + shimmer * np.interp(np.arange(n), np.linspace(0, n, 40),
                                   rng.standard_normal(40))
    src *= track(AV, n)

    # Aspiration rides with voicing. It must be SHAPED, not white: white noise
    # through the +6 dB/oct lip radiation below turns the whole utterance into
    # a whisper with a buzz under it. Measured as a spectral centroid of
    # 8.9 kHz on the first build — speech sits nearer 1 kHz.
    from make_sounds import bandpass
    asp = bandpass(rng.standard_normal(n), 2000, 0.7) * track(AV, n) * 0.010

    x = src + asp
    y = resonator(x, track(F1, n), track(B1, n))
    y = resonator(y, track(F2, n), np.full(n, 105.0))
    y = resonator(y, track(F3, n), np.full(n, 170.0))
    y = resonator(y, np.full(n, 3400.0), np.full(n, 260.0))   # F4, fixed
    y = resonator(y, np.full(n, 4500.0), np.full(n, 320.0))   # F5, fixed

    y = burst(y, 0.252, 1750, 0.9, 0.030, 0.22, rng)   # /g/ release, velar
    y = burst(y, 0.458, 3900, 1.5, 0.042, 0.30, rng)   # /t/ release, alveolar

    y = np.diff(y, prepend=0.0)     # lip radiation, +6 dB/oct
    return vocal_rolloff(y)


def vocal_rolloff(x, corner=4200):
    """The rolloff a real vocal tract has and a bare difference does not.

    Lip radiation lifts +6 dB/oct with no upper limit, so without this the
    highest thing in the signal is whatever noise was in the source. Real
    speech is falling by 4 kHz.
    """
    n = len(x)
    S = np.fft.rfft(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    S = S / (1 + (np.maximum(f, 1.0) / corner) ** 2.2)
    return np.fft.irfft(S, n)


def burst(y, at, f0_c, q, dur, amp, rng):
    """A stop release: filtered noise, fast attack, exponential collapse."""
    from make_sounds import bandpass
    n = int(SR * dur)
    b = bandpass(rng.standard_normal(n), f0_c, q)
    b *= np.exp(-np.arange(n) / (SR * dur * 0.28))
    i = int(SR * at)
    end = min(len(y), i + n)
    y[i:end] += b[: end - i] * amp * (np.abs(y).max() or 1)
    return y


# ───────────────────────────────────────────────────────────────  variants ──

def voice():
    """The plain utterance."""
    return utterance()


def voice_chip():
    """Through a 1980s speech chip: 8 kHz, coarsely quantized, band-limited.

    Sample-and-hold aliasing and quantization noise are the whole character of
    a TMS5220 or an SP0256. Decimating without a filter is not a bug here.
    """
    y = utterance(jitter=0.010, shimmer=0.09, seed=7)
    hold = 6                                    # 48k / 6 = 8 kHz
    y = np.repeat(y[::hold], hold)[: len(y)]
    y = np.round(y / (np.abs(y).max() or 1) * 40) / 40      # ~6.3 bit
    from make_sounds import bandpass
    return bandpass(y, 1500, 0.55)


def voice_slot():
    """Mail Slot, then the voice. The object arrives, then it is named."""
    v = finish(utterance())
    c = silence(0.62 + DUR + 0.05)
    place(c, slot() * 0.85, 0.0)
    place(c, v * 0.95, 0.62)
    return c


def voice_far():
    """Quieter, further back, more room. For people who don't want a phone
    that talks at them — it reads as overheard rather than announced."""
    y = utterance(jitter=0.005, shimmer=0.03, seed=11)
    # a couple of cheap early reflections, no reverb tail
    out = y.copy()
    for delay, gain in [(0.021, 0.30), (0.037, 0.20), (0.058, 0.12)]:
        d = int(SR * delay)
        out[d:] += y[:-d] * gain
    return out


def voice_lofi():
    """Properly rough: 4 kHz sample-and-hold, 4-bit, no anti-aliasing.

    This is the SP0256-AL2 register — a talking clock, an elevator, a toy.
    The aliasing images from decimating without a filter are the point, so
    the hold happens before any band-limiting.
    """
    y = utterance(jitter=0.014, shimmer=0.12, seed=23)
    hold = 12                                   # 48k / 12 = 4 kHz
    y = np.repeat(y[::hold], hold)[: len(y)]
    y = np.round(y / (np.abs(y).max() or 1) * 7) / 7        # 4 bit
    from make_sounds import bandpass
    return bandpass(y, 1300, 0.5)


def voice_intercom():
    """Telephone band and driven slightly too hard, like a door buzzer."""
    from make_sounds import bandpass
    y = utterance(jitter=0.009, shimmer=0.07, seed=31)
    y = bandpass(y, 1100, 0.42)                             # ~300–3400 Hz
    y = np.tanh(y / (np.abs(y).max() or 1) * 2.6)           # soft clip
    return bandpass(y, 1400, 0.5)


VOICES = {
    "voice":          (voice,          "You Got Mail",            "Plain formant synthesis. Falling statement contour."),
    "voice_chip":     (voice_chip,     "You Got Mail — Chip",     "8 kHz, 6-bit speech chip. 1982."),
    "voice_lofi":     (voice_lofi,     "You Got Mail — Lo-Fi",    "4 kHz, 4-bit, aliasing left in. A talking toy."),
    "voice_intercom": (voice_intercom, "You Got Mail — Intercom", "Phone band, driven hard. A door buzzer with words."),
    "voice_far":      (voice_far,      "You Got Mail — Far",      "Quieter, early reflections. Overheard, not announced."),
    "voice_slot":     (voice_slot,     "Slot + Voice",            "The flap, then the words."),
}


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    out.mkdir(parents=True, exist_ok=True)
    (out / "phone-sim").mkdir(exist_ok=True)
    for key, (fn, name, note) in VOICES.items():
        x = finish(fn())
        d = write(out / f"{key}.wav", x)
        write(out / "phone-sim" / f"{key}.wav", phone_speaker(x))
        print(f"{key:11s} {d*1000:6.0f} ms   {name} — {note}")


if __name__ == "__main__":
    main()
