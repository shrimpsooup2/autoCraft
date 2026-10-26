"""Shot 1 — cold open: dawn fly-over, the bot walks out of the forest, the logo assembles in the sky."""
import math
from mathutils import Vector
import kit

FRAMES = 270


def build(preview=False):
    kit.reset(FRAMES, samples=24)
    kit.sky_grad(zenith="#2f63b3", horizon="#ffab66", middle="#f3c9a4", ground="#5b6a45", ambient=0.32, sun_elev=11,
                 sun_rot=-25, sun_energy=5.5, sun_color="#ffc890", camera_strength=1.15)
    kit.bloom(threshold=0.85, size=7)
    blocks, water, height = kit.landscape(size=110 if not preview else 80, seed=11, base=7, amp=10, water=6,
                                          trees=0.03, forest_x=6)
    kit.block_mesh("terrain", blocks)
    kit.block_mesh("water", water, material=kit.water_material(), cull_against=blocks)

    # the bot walks out of the forest to the river bank
    bot = kit.bot()
    path = [(17, 7), (12, 6), (7, 5), (3, 4)]
    pts = [(x, y, kit.ground_z(height, x, y)) for x, y in path]
    end = kit.follow(bot, pts, 20, speed=3.2)
    kit.rest(bot, end + 2)
    bot.key(end + 10, head=(-12, 0, 0))   # looks up at the sky as the title builds
    kit.animate_input(bot.light_mat, "Emission Strength", [(1, 3), (120, 3), (128, 18), (136, 4), (144, 18), (152, 5)])

    # logo in the sky
    L = Vector((0, 26, 34))
    vox = 0.25
    cols = kit.logo_columns(voxel=vox, color=kit.hexc("#c81e14"), emission=0.06)
    for cx, ob in cols:
        final = L + Vector(((cx - 15.5) * vox, 0, 0))
        start = 178 + (cx * 2 if cx < 17 else 34 + (cx - 17) * 2)  # gear first, then the circuit
        ob.location = final + Vector((0, 0, -9))
        ob.scale = (0, 0, 0)
        ob.keyframe_insert("location", frame=start)
        ob.keyframe_insert("scale", frame=start)
        ob.location = final + Vector((0, 0, 0.35))
        ob.scale = (1.08, 1.08, 1.08)
        ob.keyframe_insert("location", frame=start + 9)
        ob.keyframe_insert("scale", frame=start + 9)
        ob.location = final
        ob.scale = (1, 1, 1)
        ob.keyframe_insert("location", frame=start + 14)
        ob.keyframe_insert("scale", frame=start + 14)
    red = cols[0][1].data.materials[0]
    kit.animate_input(red, "Emission Strength", [(1, 0.12), (232, 0.12), (238, 3.0), (252, 0.25)])

    title = kit.text("autoCraft", size=2.1, color=kit.WHITE, emission=0.35, extrude=0.08)
    title.location = L + Vector((0, 0, -5.6))
    title.rotation_euler = (math.radians(90), 0, 0)
    kit.pop_in(title, 236, dur=10)
    tag = kit.text("Tell it what you want.", size=1.0, color=kit.hexc("#fff1e0"), emission=0.35, font="segoeuisl.ttf")
    tag.location = L + Vector((0, 0, -7.6))
    tag.rotation_euler = (math.radians(90), 0, 0)
    kit.pop_in(tag, 250, dur=10)

    cam, tgt = kit.camera(lens=32, dof=None)
    b0 = Vector(pts[0]) + Vector((0, 0, 1.5))
    b1 = Vector(pts[-1]) + Vector((0, 0, 1.5))
    kit.cam_keys(cam, tgt, [
        (1, (-34, -30, 15), (6, 2, 7)),
        (90, (-14, -14, 10.5), b0.lerp(b1, 0.4)),
        (160, (-3, -8, 9.5), b1),
        (205, (-1, -4, 18), L + Vector((0, 0, -7))),
        (FRAMES, (0, 4.5, 31.5), L + Vector((0, 0, -2.4))),
    ])
    return FRAMES
