"""
Render one shot:  blender -b --factory-startup --python render.py -- <shot> <out_dir> [preview] [frames=1,40,90] [samples=N]
Shots are modules shots/<shot>.py with build() → number of frames; they use kit for everything.
preview: half size at 8 samples. samples=N: anti-aliasing samples per frame (the shots use 24).
"""
import importlib
import os
import sys
import time

import bpy

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "shots"))

args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
shot, out_dir = args[0], args[1]
preview = "preview" in args
frames = None
samples = None
for a in args:
    if a.startswith("frames="):
        frames = [int(v) for v in a[7:].split(",") if v]
    elif a.startswith("samples="):
        samples = int(a[8:])

mod = importlib.import_module(shot)
t0 = time.time()
n = mod.build(preview=preview)
sc = bpy.context.scene
sc.frame_end = n
if preview:
    sc.render.resolution_percentage = 50
    sc.eevee.taa_render_samples = 8
if samples:
    sc.eevee.taa_render_samples = samples
os.makedirs(out_dir, exist_ok=True)
print(f"BUILT {shot}: {n} frames in {time.time() - t0:.1f}s")
t0 = time.time()
if frames:
    for f in frames:
        sc.frame_set(f)
        sc.render.filepath = os.path.join(out_dir, f"{f:04d}.png")
        bpy.ops.render.render(write_still=True)
else:
    sc.render.filepath = os.path.join(out_dir, "")
    bpy.ops.render.render(animation=True)
print(f"RENDERED {shot} in {time.time() - t0:.1f}s")
