#!/usr/bin/env python3
"""
Generate the app's notification sounds into res/raw at build time.

Called by app/build.gradle.kts. Nothing audio is committed to this repository — the
sounds are arithmetic, so the source is ~15 KB of Python plus one 34 KB JSON of measured
articulation data, and the WAVs exist only inside build/.

    python3 build_sounds.py <out-dir>

Android res/raw names must be lowercase [a-z0-9_], so the keys here are the resource
names the app looks up in Notifier.kt. Change one and change it there.
"""
import shutil
import sys
from pathlib import Path

from make_sounds import SOUNDS, finish, write
from make_match import MATCHES

# resource name -> the generator that makes it
ROSTER = {
    "snd_youve_got_mail": MATCHES["match_chip"][0],   # the synthesized voice, chip take
    "snd_music_box": SOUNDS["music_box"][0],
}


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for name, fn in ROSTER.items():
        dur = write(out / f"{name}.wav", finish(fn()))
        print(f"  {name}.wav  {dur*1000:.0f} ms")


if __name__ == "__main__":
    main()
