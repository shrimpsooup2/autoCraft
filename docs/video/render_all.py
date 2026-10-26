"""Render the explainer video from scratch on any machine with Blender 5.x, then cut the final MP4.

    python docs/video/render_all.py                            everything, then docs/video/autocraft-explainer.mp4
    python docs/video/render_all.py shot5_survive shot6_city   just these shots
    python docs/video/render_all.py --preview --frames 1,200   quick look: half size, 8 samples, two frames per shot

Options
  --blender PATH   Blender 5.x (default: $BLENDER, then PATH, then the usual install folders)
  --ffmpeg PATH    for the final encode (default: PATH, then the imageio-ffmpeg package)
  --out DIR        where frames go (default docs/video/renders; ignored by git)
  --samples N      anti-aliasing samples per frame (default 24; 12 is about twice as fast, a little grainier)
  --chunk N        frames per Blender run (default 60; each run rebuilds the scene, a crash only loses its chunk)

Resumable: frames already on disk are skipped, so stop it (Ctrl+C) and start it again whenever. The MP4 is cut
once every frame of every shot exists. Needs Python 3 with Pillow and numpy:  pip install pillow numpy imageio-ffmpeg
"""
import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import edit  # noqa: E402  (the cut: shot list, frame counts, encoder)

RENDER = HERE / "blender" / "render.py"
UI_FRAMES = 250                      # ui_frames.py writes this many panel frames for shot 2


def find_blender(given):
    for c in (given, os.environ.get("BLENDER"), shutil.which("blender")):
        if c:
            return c
    if sys.platform == "win32":
        pats = [os.path.join(os.environ.get(v, ""), "Blender Foundation", "Blender*", "blender.exe")
                for v in ("ProgramFiles", "ProgramFiles(x86)")]
        pats += [os.path.join(os.environ.get("ProgramFiles(x86)", ""), "Steam", "steamapps", "common", "Blender", "blender.exe"),
                 os.path.expanduser(r"~\Downloads\blender-*\blender.exe")]
    elif sys.platform == "darwin":
        pats = ["/Applications/Blender*.app/Contents/MacOS/Blender"]
    else:
        pats = ["/opt/blender*/blender", os.path.expanduser("~/blender*/blender"), "/snap/bin/blender"]
    found = sorted(p for pat in pats for p in glob.glob(pat))
    return found[-1] if found else None


def blender_version(blender):
    out = subprocess.run([blender, "--version"], capture_output=True, text=True).stdout
    m = re.search(r"Blender (\d+)\.(\d+)", out)
    return (int(m.group(1)), int(m.group(2))) if m else None


def fmt(seconds):
    seconds = int(seconds)
    return f"{seconds // 3600}h{seconds % 3600 // 60:02d}m" if seconds >= 3600 else f"{seconds // 60}m{seconds % 60:02d}s"


def main():
    names = [s for s, _, _ in edit.SHOTS]
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("shots", nargs="*", help="shots to render (default all): " + ", ".join(names))
    ap.add_argument("--blender")
    ap.add_argument("--ffmpeg")
    ap.add_argument("--out", default=str(HERE / "renders"))
    ap.add_argument("--samples", type=int)
    ap.add_argument("--chunk", type=int, default=60)
    ap.add_argument("--preview", action="store_true", help="half size, 8 samples, into <out>/preview")
    ap.add_argument("--frames", help="comma-separated frame numbers to render in each shot")
    a = ap.parse_args()

    shots = a.shots or names
    for s in shots:
        if s not in names:
            sys.exit(f"unknown shot {s!r}; shots are: {', '.join(names)}")
    blender = find_blender(a.blender)
    if not blender:
        sys.exit("Blender not found: install Blender 5.x, or pass --blender PATH (or set BLENDER).")
    ver = blender_version(blender)
    if not ver or ver[0] < 5:
        sys.exit(f"{blender} is Blender {ver and '.'.join(map(str, ver))}; these scenes need Blender 5.x.")
    print(f"Blender {ver[0]}.{ver[1]}: {blender}")

    out = Path(a.out)
    frames_root = out / "preview" if a.preview else out
    ui = out / "ui"
    if "shot2_pick" in shots and len(list(ui.glob("panel_*.png"))) < UI_FRAMES:
        print("panel frames for shot 2 ...", flush=True)
        subprocess.run([sys.executable, str(HERE / "ui_frames.py"), str(ui)], check=True)
    env = dict(os.environ, AUTOCRAFT_UI=str(ui))

    count = dict((s, n) for s, n, _ in edit.SHOTS)
    wanted = {s: ([int(v) for v in a.frames.split(",") if v.strip()] if a.frames else list(range(1, count[s] + 1)))
              for s in shots}

    def missing(s):
        return [f for f in wanted[s] if not (frames_root / s / f"{f:04d}.png").exists()]

    todo = sum(len(missing(s)) for s in shots)
    print(f"{todo} frames to render" + (" (preview)" if a.preview else ""), flush=True)
    done, t0 = 0, time.time()
    for s in shots:
        d = frames_root / s
        d.mkdir(parents=True, exist_ok=True)
        for sweep in range(3):                       # a crashed chunk is retried on the next sweep
            left = missing(s)
            if not left:
                break
            for i in range(0, len(left), a.chunk):
                chunk = left[i:i + a.chunk]
                cmd = [blender, "-b", "--factory-startup", "--python", str(RENDER), "--", s, str(d)]
                cmd += (["preview"] if a.preview else []) + ["frames=" + ",".join(map(str, chunk))]
                cmd += [f"samples={a.samples}"] if a.samples else []
                r = subprocess.run(cmd, capture_output=True, text=True, env=env)
                got = sum((d / f"{f:04d}.png").exists() for f in chunk)
                done += got
                rate = (time.time() - t0) / max(1, done)
                print(f"{s}: frames {chunk[0]}-{chunk[-1]}: {got}/{len(chunk)}   "
                      f"[{done}/{todo}, {rate:.1f} s/frame, ~{fmt(rate * (todo - done))} left]", flush=True)
                if got < len(chunk):
                    print((r.stdout + r.stderr)[-2500:], flush=True)
        if missing(s):
            sys.exit(f"{s}: {len(missing(s))} frames still missing after 3 tries (Blender's output is above).")

    if a.preview or a.frames:
        print(f"done in {fmt(time.time() - t0)}; frames are in {frames_root}")
        return
    if not all((out / s / f"{f:04d}.png").exists() for s, n, _ in edit.SHOTS for f in range(1, n + 1)):
        print(f"rendered in {fmt(time.time() - t0)}; the MP4 is cut once every shot is rendered")
        return
    edit.encode(out, HERE / "autocraft-explainer.mp4", a.ffmpeg)


if __name__ == "__main__":
    main()
