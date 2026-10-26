"""Shot 2 — how to use it: K opens the panel (floating screen), 32 diamond is added, the plan unfolds, Run all; the
panel flies off, the bot's light turns green and it sets off."""
import math
import os
import bpy
from mathutils import Vector, Euler
import kit

FRAMES = 450
PANEL_START, PANEL_FRAMES = 40, 250
UI_DIR = os.environ.get("AUTOCRAFT_UI", os.path.join(os.path.expanduser("~"), ".autocraft-dev", "video", "ui"))


def panel_material():
    img = bpy.data.images.load(os.path.join(UI_DIR, "panel_0001.png"))
    img.source = "SEQUENCE"
    m = bpy.data.materials.new("Panel")
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = img
    tex.interpolation = "Linear"
    iu = tex.image_user
    iu.frame_duration = PANEL_FRAMES
    iu.frame_start = PANEL_START
    iu.frame_offset = 0
    iu.use_auto_refresh = True
    iu.use_cyclic = False
    emit = nt.nodes.new("ShaderNodeEmission")
    emit.inputs["Strength"].default_value = 1.0
    nt.links.new(tex.outputs["Color"], emit.inputs["Color"])
    tr = nt.nodes.new("ShaderNodeBsdfTransparent")
    mix = nt.nodes.new("ShaderNodeMixShader")
    step = nt.nodes.new("ShaderNodeMath")  # solid panel, clean corners: nothing behind shows through
    step.operation = "GREATER_THAN"
    step.inputs[1].default_value = 0.4
    nt.links.new(tex.outputs["Alpha"], step.inputs[0])
    nt.links.new(step.outputs[0], mix.inputs["Fac"])
    nt.links.new(tr.outputs[0], mix.inputs[1])
    nt.links.new(emit.outputs[0], mix.inputs[2])
    nt.links.new(mix.outputs[0], out.inputs["Surface"])
    kit.transparent(m)
    return m


def keycap(cam):
    """A K key in the lower left of the view that presses itself."""
    bpy.ops.mesh.primitive_cube_add(size=1)
    k = bpy.context.active_object
    k.name = "KeyK"
    bev = k.modifiers.new("bevel", "BEVEL")
    bev.width = 0.12
    bev.segments = 3
    k.data.materials.append(kit.mat_flat("keycap", kit.hexc("#2b2f38"), rough=0.4))
    k.parent = cam
    label = kit.text("K", size=0.75, color=kit.WHITE, emission=1.5)
    label.parent = k
    label.location = (0, 0, 0.52)
    base = Vector((-0.74, -0.36, -1.7))
    k.rotation_euler = Euler((math.radians(55), 0, math.radians(-10)))
    S = (0.2, 0.2, 0.11)
    for f, z, s in ((8, -0.25, 0.0), (16, 0.0, 1.0), (24, 0.0, 1.0), (27, -0.04, 1.0), (31, 0.0, 1.0), (52, 0.0, 1.0), (60, -0.3, 0.0)):
        k.location = base + Vector((0, z, 0))
        k.keyframe_insert("location", frame=f)
        k.scale = (S[0] * s, S[1] * s, S[2] * s)
        k.keyframe_insert("scale", frame=f)
    glow = label.data.materials[0]
    kit.animate_input(glow, "Emission Strength", [(1, 1.5), (26, 1.5), (28, 8.0), (36, 1.5)])
    return k


def build(preview=False):
    kit.reset(FRAMES, samples=24)
    kit.sky_grad(zenith="#3b7ad2", horizon="#cfe6ff", middle="#8db8ea", ground="#55703f", ambient=0.42, sun_elev=40,
                 sun_rot=-35, sun_energy=4.2, sun_color="#fff4e2")
    kit.bloom(threshold=0.9, size=6)
    blocks, water, height = kit.landscape(size=90 if not preview else 70, seed=23, base=6, amp=7, water=4,
                                          river=False, trees=0.025, forest_x=4)
    path = [(0, 0), (2, 3), (6, 7), (11, 12), (17, 16)]
    kit.clear_corridor(blocks, height, path, radius=3.5)                       # the bot's walk
    kit.clear_corridor(blocks, height, [(0, -4), (6, -5), (9, 2), (11, 12)], radius=5.5)  # the camera's move and view
    kit.block_mesh("terrain", blocks)
    if water:
        kit.block_mesh("water", water, material=kit.water_material(), cull_against=blocks)

    gz = kit.ground_z(height, 0, 0)
    bot = kit.bot()
    bot.place(1, (0, 0, gz), yaw_deg=180)  # facing away from the camera (toward +Y)
    # light: red while idle, green once running (a second gem swaps in with a pop)
    green_mat = kit.mat_flat("light_green", kit.GREEN, emission=7.0)
    bpy.ops.mesh.primitive_cube_add(size=kit.PX * 2.2)
    gem = bpy.context.active_object
    gem.data.materials.append(green_mat)
    gem.parent = bot.pivots["head"]
    gem.location = bot.light.location
    gem.rotation_euler = bot.light.rotation_euler
    kit.visible(gem, [(1, False), (304, True)])
    kit.pop_in(gem, 304, dur=8)
    kit.visible(bot.light, [(1, True), (304, False)])
    # sets off: turns toward the forest and walks
    pts = [(x, y, kit.ground_z(height, x, y)) for x, y in path]
    bot.place(318, pts[0], yaw_deg=180)
    kit.follow(bot, pts, 320, speed=4.0)
    bot.key(296, head=(0, 0, 0))
    bot.key(304, head=(8, 0, -20))   # a little nod as it switches on
    bot.key(312, head=(0, 0, 0))

    cam, tgt = kit.camera(lens=32)
    head = Vector((0, 0, gz + 1.6))
    kit.cam_keys(cam, tgt, [
        (1, head + Vector((1.3, -2.9, 0.45)), head + Vector((-1.6, 8, -0.6))),
        (290, head + Vector((1.2, -2.7, 0.4)), head + Vector((-1.6, 8, -0.6))),
        (335, head + Vector((5.5, -4.5, 1.2)), head + Vector((1.0, 1.5, -0.5))),
        (FRAMES, head + Vector((8.0, 2.0, 3.2)), Vector(pts[-2]) + Vector((0, 0, 1.2))),
    ])

    # the panel: a floating screen in front of the camera
    bpy.ops.mesh.primitive_plane_add(size=1)
    panel = bpy.context.active_object
    panel.name = "Panel"
    panel.data.materials.append(panel_material())
    panel.parent = cam
    w, h = 1.72, 1.72 * 960 / 1680
    rest = Vector((0.05, 0.0, -2.05))
    panel.rotation_euler = Euler((0, math.radians(0), 0))
    keys = [(34, rest + Vector((0, -0.6, 0.6)), 0.0, (math.radians(-35), math.radians(18), 0)),
            (46, rest + Vector((0, 0.02, 0)), 1.03, (math.radians(-3), math.radians(-4), 0)),
            (52, rest, 1.0, (math.radians(-2), math.radians(-3), 0)),
            (286, rest, 1.0, (math.radians(-2), math.radians(-3), 0)),
            (300, rest + Vector((0.9, 0.7, -0.6)), 0.0, (math.radians(20), math.radians(-30), math.radians(-10)))]
    for f, loc, s, rot in keys:
        panel.location = loc
        panel.scale = (w * s, h * s, 1)
        panel.rotation_euler = Euler(rot)
        panel.keyframe_insert("location", frame=f)
        panel.keyframe_insert("scale", frame=f)
        panel.keyframe_insert("rotation_euler", frame=f)
    panel.scale = (0, 0, 1)
    panel.keyframe_insert("scale", frame=1)
    keycap(cam)
    chat_command(cam, "!ac collect diamond 32", 322)
    return FRAMES


def chat_command(cam, cmd, f0, cps=2):
    """The same thing as a chat command, typed into the chat bar in the lower left (cps characters a frame)."""
    x, y, z = -0.86, -0.40, -1.6          # camera space; lens 32 shows ±0.9 × ±0.51 at this depth
    bpy.ops.mesh.primitive_plane_add(size=1)
    bar = bpy.context.active_object
    bar.data.materials.append(kit.mat_flat("chatbar", kit.hexc("#000000"), alpha=0.5))
    bar.parent = cam
    bar.scale = (0.62, 0.05, 1)
    bar.location = (x + 0.30, y, z - 0.001)
    kit.visible(bar, [(1, False), (f0 - 6, True)])
    steps = list(range(cps, len(cmd) + cps, cps))
    for i, n in enumerate(steps):
        t = kit.text(cmd[:n] + ("_" if i < len(steps) - 1 else ""), size=0.032, color=kit.WHITE, emission=1.2,
                     font="consola.ttf", align="LEFT", name=f"chat{i}")
        t.parent = cam
        t.location = (x, y, z)
        last = i == len(steps) - 1
        kit.visible(t, [(1, False), (f0 + i, True)] + ([] if last else [(f0 + i + 1, False)]))
