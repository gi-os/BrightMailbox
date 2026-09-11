# scripts

## Sounds

`build_sounds.py` runs during the Gradle build and writes the app's notification sounds
into `build/generated/res/sounds/raw/`. **No audio is committed.** Every sound is
generated from arithmetic:

- `make_sounds.py` — modal synthesis. Sums of exponentially decaying sinusoids, with
  inharmonic partial ratios where the object being struck would be metal. Ten chimes.
- `make_voice.py` — a Klatt-style cascade formant synthesizer. A Rosenberg glottal pulse
  train drives five time-varying two-pole resonators; stops are silence plus a filtered
  noise burst; lip radiation is a first difference.
- `make_match.py` — drives that synthesizer from `ref_tracks.json` instead of from
  hand-guessed targets.

### ref_tracks.json

204 frames of measured articulation: F1–F4 and their bandwidths, pitch, level, voicing,
and one static spectral-tilt curve. It is a description of where a tongue was, not a
recording — 34 KB of floats. It was produced once by `analyze_ref.py` and the build
never touches audio.

Why the app ships a synthesized "You've got mail" rather than the famous one: that
recording is Elwood Edwards' 1989 performance for AOL and belongs to Yahoo. Playing your
own copy on your own phone is ordinary personal use; shipping it inside an APK is
redistribution, and an MIT `LICENSE` on this repo would then be making a false statement
to anyone who forks it. Use **Settings → Sound → Custom** to point the app at your own
file if you have one.

### The measurements, for anyone curious

Analysis-by-synthesis, verified rather than assumed:

| | error vs the reference |
|---|---|
| F1 | 28 Hz |
| F2 | 28 Hz |
| pitch | 8.8 Hz |
| alignment | 1.1 ms |
| spectral envelope distance | 4.25 dB |

For scale on that last one: a band-limited copy of the reference scores 4.53 dB against
itself, the reference played backwards scores 6.36, and noise scores 11.17.

Three findings worth keeping:

- The pitch contour is the whole character. It runs **YOU'VE 233 → got 137 → MAIL 222 →
  78 Hz**, nearly an octave, with the peak on the first word. A flat contour is what
  makes synthesized speech sound synthesized.
- Spectral tilt was 63% of the total error even with every formant correct — a glottal
  source model and a 1989 broadcast chain do not have the same slope.
- Formant tracking needs Viterbi, not greedy nearest-neighbor. During the /m/ of "mail"
  the mouth is shut and F2 is weak; greedy tracking latched onto the wrong pole and
  carried it through the vowel, reporting F2 near 2500 Hz where the real glide runs
  1500 → 2000.

## authorize.py

Sign in on a computer and hand the phone a refresh token by QR, for when LightOS's
browser eats the OAuth redirect. See the header of that file.
