"""Cut the rendered shots into the final video: crossfades between shots, a chapter tag at the start of each middle
shot, a fade in from black and out at the end. Silent (the music is laid in separately).

    python edit.py <renders_dir> <out.mp4> [ffmpeg]

<renders_dir>/<shot>/0001.png … as written by blender/render.py (render_all.py runs this for you at the end).
ffmpeg: the given one, else the one on PATH, else the imageio-ffmpeg package's."""
import shutil
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

FPS = 30
W, H = 1920, 1080
XFADE = 12                    # frames of crossfade between shots
FADE_IN, FADE_OUT = 18, 30
# (shot, frames, chapter tag or None) — the cut, in order
SHOTS = [("shot1_open", 270, None), ("shot2_pick", 450, "Pick"), ("shot3_plan", 600, "Plan"),
         ("shot4_find", 465, "Find"), ("shot5_survive", 540, "Survive"), ("shot6_city", 360, "Loot"),
         ("shot7_build", 450, "Build together"), ("shot8_end", 300, None)]
RED = (229, 53, 43)
FONTS = ["C:/Windows/Fonts/segoeuib.ttf", "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
         "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "DejaVuSans-Bold.ttf", "arialbd.ttf"]


def find_ffmpeg(given=None):
    if given:
        return given
    on_path = shutil.which("ffmpeg")
    if on_path:
        return on_path
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except ImportError:
        sys.exit("No ffmpeg: install it, or `pip install imageio-ffmpeg`, or pass its path.")


def font(size):
    for f in FONTS:
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            continue
    return ImageFont.load_default(size)


def tag_image(number, label):
    """A small pill: a red disc with the chapter number, then the label."""
    f_num, f_lab = font(30), font(34)
    tw = int(f_lab.getlength(label))
    im = Image.new("RGBA", (86 + tw + 28, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, im.width - 1, 63), radius=32, fill=(12, 14, 18, 170))
    d.ellipse((8, 8, 56, 56), fill=RED)
    d.text((32, 31), str(number), font=f_num, fill=(255, 255, 255), anchor="mm")
    d.text((72, 31), label, font=f_lab, fill=(255, 255, 255), anchor="lm")
    return im


def tag_alpha(f):
    """Tag opacity at frame f of its shot: in over 10 frames from frame 14, held, out over 14 frames by frame 120."""
    if f < 14:
        return 0.0
    if f < 24:
        return (f - 14) / 10
    if f < 106:
        return 1.0
    return max(0.0, 1 - (f - 106) / 14)


def total_frames():
    return sum(n for _, n, _ in SHOTS) - XFADE * (len(SHOTS) - 1)


def frames(root):
    chapter = 0
    tags = {}
    for shot, n, label in SHOTS:
        if label:
            chapter += 1
            tags[shot] = tag_image(chapter, label)

    def load(shot, f):
        im = Image.open(root / shot / f"{f:04d}.png").convert("RGB")
        if im.size != (W, H):
            im = im.resize((W, H), Image.LANCZOS)
        t = tags.get(shot)
        a = tag_alpha(f) if t else 0
        if a > 0:
            ov = t.copy()
            ov.putalpha(ov.getchannel("A").point(lambda v: int(v * a)))
            im.paste(ov, (48, 44), ov)
        return im

    for i, (shot, n, _) in enumerate(SHOTS):
        first = 1 if i == 0 else XFADE + 1       # the first XFADE frames were blended into the previous shot's tail
        last = n if i == len(SHOTS) - 1 else n - XFADE
        for f in range(first, last + 1):
            yield load(shot, f)
        if i + 1 < len(SHOTS):
            nxt = SHOTS[i + 1][0]
            for k in range(XFADE):
                a = (k + 1) / (XFADE + 1)
                yield Image.blend(load(shot, n - XFADE + 1 + k), load(nxt, k + 1), a)


def encode(root, out, ffmpeg=None):
    root, out = Path(root), Path(out)
    total = total_frames()
    p = subprocess.Popen([find_ffmpeg(ffmpeg), "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
                          "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-preset", "slow",
                          "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(out)],
                         stdin=subprocess.PIPE)
    black = Image.new("RGB", (W, H))
    for i, im in enumerate(frames(root)):
        if i < FADE_IN:
            im = Image.blend(black, im, i / FADE_IN)
        elif i >= total - FADE_OUT:
            im = Image.blend(black, im, (total - 1 - i) / FADE_OUT)
        p.stdin.write(im.tobytes())
        if i % 300 == 0:
            print(f"  encoding {i}/{total}", flush=True)
    p.stdin.close()
    if p.wait():
        sys.exit("ffmpeg failed")
    print(f"wrote {out} ({total} frames, {total / FPS:.1f} s)")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    encode(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
