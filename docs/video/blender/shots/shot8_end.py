"""Shot 8 — end card. Sunset over the finished base and its wheat farm, the team at work. The bot opens a chest and
diamonds fountain out. The camera cranes up into the sky: the logo assembles, then "autoCraft" and "Press K in game"."""
import math
import random
import bpy
from mathutils import Vector
import kit
from shot7_build import cottage

FRAMES = 300
CHEST = (5, -2)
L = Vector((3.5, 34, 32))          # the logo in the sky, behind the base (framed like the opening shot)
T_OPEN = 100


def yaw_to(src, dst):
    return math.degrees(math.atan2(dst[0] - src[0], -(dst[1] - src[1])))


def torch(x, y, z):
    bpy.ops.mesh.primitive_cube_add(size=1, location=(x, y, z + 0.3))
    s = bpy.context.active_object
    s.scale = (0.12, 0.12, 0.6)
    s.data.materials.append(kit.mat_flat("torch_stick", kit.hexc("#6b4a23")))
    bpy.ops.mesh.primitive_cube_add(size=0.15, location=(x, y, z + 0.66))
    flame = bpy.context.active_object
    flame.data.materials.append(kit.mat_flat("flame", kit.hexc("#ffcf5a"), emission=12))
    lamp = bpy.data.lights.new("torch", "POINT")
    lamp.energy = 60
    lamp.color = (1.0, 0.68, 0.32)
    lo = bpy.data.objects.new("torch", lamp)
    kit.link(lo)
    lo.location = (x, y, z + 0.8)


def keycap(loc, f_press):
    """A K key on the end card that presses itself once."""
    bpy.ops.mesh.primitive_cube_add(size=1)
    k = bpy.context.active_object
    k.name = "KeyK"
    bev = k.modifiers.new("bevel", "BEVEL")
    bev.width = 0.12
    bev.segments = 3
    k.data.materials.append(kit.mat_flat("keycap", kit.hexc("#2b2f38"), rough=0.4))
    k.scale = (1.15, 0.6, 1.15)
    label = kit.text("K", size=0.85, color=kit.WHITE, emission=1.5)
    label.rotation_euler = (math.radians(90), 0, 0)
    root = kit.empty("keyroot", loc)
    k.parent = root
    label.parent = root
    label.location = (0, -0.32, -0.02)
    for f, dy in ((f_press - 3, 0.0), (f_press, 0.16), (f_press + 5, 0.0)):
        root.location = loc + Vector((0, dy, 0))
        root.keyframe_insert("location", frame=f)
    kit.animate_input(label.data.materials[0], "Emission Strength", [(1, 1.5), (f_press - 1, 1.5), (f_press + 1, 9.0),
                                                                     (f_press + 10, 1.5)])
    return root


def build(preview=False):
    kit.reset(FRAMES, samples=24)
    kit.sky_grad(zenith="#2a2e6b", horizon="#ff9450", middle="#d9707e", ground="#4b3a33", ambient=0.32, sun_elev=6,
                 sun_rot=-25, sun_energy=5.0, sun_color="#ffaa66", camera_strength=1.1)
    kit.bloom(threshold=0.85, size=7)
    rng = random.Random(8)

    # ---- the base: the cottage the team built, a path to the door, a wheat farm, trees
    world = {}
    for x in range(-26, 34):
        for y in range(-24, 30):
            world[(x, y, 0)] = "grass"
            world[(x, y, -1)] = "dirt"
    world.update(cottage())
    for y in range(-7, 0):
        world[(3, y, 0)] = "path"
    crops = []
    for x in range(9, 17):
        for y in range(-1, 8):
            if y == 3:
                world[(x, y, 0)] = "water"
            else:
                world[(x, y, 0)] = "farmland"
                crops.append((x, y))
    for _ in range(30):
        tx, ty = rng.randint(-22, 30), rng.randint(12, 26)
        if all(abs(tx - a) + abs(ty - b) > 3 for (a, b, c), k in list(world.items()) if k == "log" and c == 1):
            kit.tree(world, tx, ty, 1, rng.randint(4, 6), rng)
    for tx, ty in ((-6, 2), (-9, -4), (-5, 8)):
        kit.tree(world, tx, ty, 1, 5, rng)
    water = {p: k for p, k in world.items() if k == "water"}
    solid = {p: k for p, k in world.items() if k != "water"}
    kit.block_mesh("base", solid)
    kit.block_mesh("channel", water, material=kit.water_material(), cull_against=solid)
    # ripe wheat: two crossed flat sprites per farmland block, like the game's crop model
    wheat = {}
    wr = random.Random(3)
    for sx in (1, 4, 7, 10, 13):
        sx += wr.randint(0, 1)
        h = wr.randint(10, 14)
        for y in range(h):
            wheat[(sx, 0, y)] = kit.hexc("#7f9a2a" if y < h * 0.45 else "#b59f36")
        for y in range(h - 4, h + 1):
            for dx in (0, 1):
                wheat[(sx + dx, 0, y)] = kit.hexc("#f0d46a" if (y + dx) % 2 else "#d4b248")
    crop = kit.color_mesh("wheat", wheat, size=1 / 16, center=(8, 0.5, 0))
    crop.hide_render = True
    for i, (x, y) in enumerate(crops):
        for ang in (45, -45):
            c = crop.copy()
            kit.link(c)
            c.hide_render = False
            c.location = (x + 0.5, y + 0.5, 1.0)
            c.rotation_euler = (0, 0, math.radians(ang + (i * 37) % 20 - 10))
    torch(2.4, -0.6, 1)
    torch(4.6, -0.6, 1)
    pole = Vector((3.5, 3.5, 9.0))
    bpy.ops.mesh.primitive_cube_add(size=1, location=pole + Vector((0, 0, 0.9)))
    p = bpy.context.active_object
    p.scale = (0.08, 0.08, 1.8)
    p.data.materials.append(kit.mat_flat("pole", kit.hexc("#6b4a23")))
    bpy.ops.mesh.primitive_plane_add(size=1)
    cloth = bpy.context.active_object
    cloth.data.materials.append(kit.mat_flat("flag", kit.RED, emission=0.4))
    hinge_f = kit.empty("flag_hinge", pole + Vector((0.04, 0, 1.55)))
    cloth.parent = hinge_f
    cloth.location = (0.45, 0, 0)
    cloth.rotation_euler = (math.radians(90), 0, 0)
    cloth.scale = (0.9, 0.55, 1)
    for fr in range(1, FRAMES + 1, 6):
        hinge_f.rotation_euler = (0, 0, math.radians(18 * math.sin(fr * 0.25)))
        hinge_f.keyframe_insert("rotation_euler", frame=fr)

    # ---- the team, still at it
    def green(rig):
        rig.light.data.materials.clear()
        rig.light.data.materials.append(kit.mat_flat(rig.root.name + "_g", kit.GREEN, emission=7))
    leader = kit.bot("Leader")
    green(leader)
    stand = Vector((CHEST[0] + 0.5, CHEST[1] - 1.3, 1.0))
    chest_c = Vector((CHEST[0] + 0.5, CHEST[1] + 0.5, 1.5))
    kit.follow(leader, [Vector((1.0, -6.0, 1.0)), Vector((3.5, -4.5, 1.0)), stand], 10, speed=3.0)
    leader.place(T_OPEN - 10, stand, yaw_deg=yaw_to(stand, chest_c) - 360)
    leader.key(T_OPEN - 4, arm_r=0)
    leader.key(T_OPEN, arm_r=-70)
    leader.key(T_OPEN + 6, arm_r=0)
    leader.key(T_OPEN + 14, head=(0, 0, 0))
    leader.key(T_OPEN + 24, head=(-18, 0, 0))   # looks up at the diamonds
    farmer = kit.humanoid("Alex", skin="#c99868", shirt="#2a9d8f", pants="#2a2f3a", shoes="#4a4a4a", hair="#3a2a1a",
                          light=kit.GREEN)
    fpos = Vector((12.5, -2.0, 1.0))
    farmer.place(1, fpos, yaw_deg=180)
    kit.mine_swings(farmer, 4, FRAMES, period=10)
    hauler = kit.humanoid("Sam", skin="#c99868", shirt="#e07a2f", pants="#2a2f3a", shoes="#4a4a4a", hair="#3a2a1a",
                          light=kit.GREEN)
    kit.follow(hauler, [Vector((16.5, -4.5, 1.0)), Vector((12.5, -4.5, 1.0)), Vector((10.5, -2.6, 1.0))], 1, speed=2.6)
    builder = kit.humanoid("Kai", skin="#c99868", shirt="#7b5ea7", pants="#2a2f3a", shoes="#4a4a4a", hair="#3a2a1a",
                           light=kit.GREEN)
    bpos = Vector((-1.5, -1.8, 1.0))
    builder.place(1, bpos, yaw_deg=yaw_to(bpos, (3.5, 3.5)))
    kit.mine_swings(builder, 20, 90, period=8)

    # ---- the chest of diamonds
    base, hinge = kit.chest(*CHEST)
    hinge.keyframe_insert("rotation_euler", frame=T_OPEN)
    for fr, deg in ((T_OPEN + 7, -106), (T_OPEN + 10, -98)):
        hinge.rotation_euler = (math.radians(deg), 0, 0)
        hinge.keyframe_insert("rotation_euler", frame=fr)
    inside = kit.mat_flat("chest_glow", kit.hexc("#c8fffb"), emission=0.001)
    kit.animate_input(inside, "Emission Strength", [(T_OPEN, 0), (T_OPEN + 4, 16), (T_OPEN + 40, 5)])
    bpy.ops.mesh.primitive_plane_add(size=0.7, location=(CHEST[0] + 0.5, CHEST[1] + 0.5, 1.62))
    bpy.context.active_object.data.materials.append(inside)
    lamp = bpy.data.lights.new("chestlight", "POINT")
    lamp.color = (0.55, 1.0, 0.95)
    lo = bpy.data.objects.new("chestlight", lamp)
    kit.link(lo)
    lo.location = (CHEST[0] + 0.5, CHEST[1] + 0.2, 2.4)
    for fr, e in ((T_OPEN, 0), (T_OPEN + 5, 220), (T_OPEN + 40, 90)):
        lamp.energy = e
        lamp.keyframe_insert("energy", frame=fr)
    shine = kit.mat_flat("dmd", kit.hexc("#5ff3ea"), emission=2.2, rough=0.2)
    for k in range(9):
        d = kit.item("diamond", size=0.45)
        d.data.materials.clear()
        d.data.materials.append(shine)
        f0 = T_OPEN + 6 + k * 2
        a = (k - 4) / 4 * math.radians(70)
        home = chest_c + Vector((math.sin(a) * 1.3, -0.15 - 0.2 * math.cos(a), 1.1 + 0.55 * math.cos(a)))
        d.location = chest_c
        d.keyframe_insert("location", frame=f0)
        kit.pop_in(d, f0, dur=6, overshoot=1.4)
        d.location = home + Vector((0, 0, 0.35))
        d.keyframe_insert("location", frame=f0 + 8)
        for fr in range(f0 + 12, FRAMES + 1, 4):
            d.location = home + Vector((0, 0, 0.07 * math.sin(fr * 0.18 + k)))
            d.keyframe_insert("location", frame=fr)
            d.rotation_euler = (0, 0, fr * 0.1 + k)
            d.keyframe_insert("rotation_euler", frame=fr)
        kit.burst(home, kit.hexc("#9ffaf6"), f0 + 8, n=5, speed=1.5, life=14, seed=600 + k, size=0.05, gravity=1)

    # ---- the end card in the sky
    vox = 0.25
    cols = kit.logo_columns(voxel=vox, color=kit.hexc("#c81e14"), emission=0.06)
    for cx, ob in cols:
        final = L + Vector(((cx - 15.5) * vox, 0, 0))
        start = 168 + (cx if cx < 17 else 17 + (cx - 17))     # gear first, then the circuit
        ob.location = final + Vector((0, 0, -6))
        ob.scale = (0, 0, 0)
        ob.keyframe_insert("location", frame=start)
        ob.keyframe_insert("scale", frame=start)
        ob.location = final + Vector((0, 0, 0.3))
        ob.scale = (1.08, 1.08, 1.08)
        ob.keyframe_insert("location", frame=start + 8)
        ob.keyframe_insert("scale", frame=start + 8)
        ob.location = final
        ob.scale = (1, 1, 1)
        ob.keyframe_insert("location", frame=start + 12)
        ob.keyframe_insert("scale", frame=start + 12)
    red = cols[0][1].data.materials[0]
    kit.animate_input(red, "Emission Strength", [(1, 0.12), (206, 0.12), (212, 3.0), (226, 0.25)])
    title = kit.text("autoCraft", size=2.1, color=kit.WHITE, emission=0.35, extrude=0.08)
    title.location = L + Vector((0, 0, -5.6))
    title.rotation_euler = (math.radians(90), 0, 0)
    kit.pop_in(title, 210, dur=10)
    press = kit.text("Press", size=1.0, color=kit.hexc("#fff1e0"), emission=0.35, font="segoeuisl.ttf", align="RIGHT")
    press.location = L + Vector((-0.95, 0, -7.75))
    ingame = kit.text("in game", size=1.0, color=kit.hexc("#fff1e0"), emission=0.35, font="segoeuisl.ttf", align="LEFT")
    ingame.location = L + Vector((0.95, 0, -7.75))
    for ob in (press, ingame):
        ob.rotation_euler = (math.radians(90), 0, 0)
        kit.pop_in(ob, 224, dur=10)
    key = keycap(L + Vector((0, 0.1, -7.75)), 252)
    kit.pop_in(key, 228, dur=10, overshoot=1.3)
    url = kit.text("github.com/shrimpsooup2/autoCraft", size=0.42, color=kit.hexc("#ffd9c0"), emission=0.3,
                   font="segoeui.ttf")
    url.location = L + Vector((0, 0, -9.1))
    url.rotation_euler = (math.radians(90), 0, 0)
    kit.pop_in(url, 240, dur=10)

    # ---- camera: the base at sunset, in to the chest, then up into the sky
    cam, tgt = kit.camera(lens=32)
    kit.cam_keys(cam, tgt, [
        (1, (19, -21, 9.5), (5, 2, 2.5)),
        (80, (10.5, -11.5, 4.4), (5.5, -1.0, 1.8)),
        (T_OPEN + 4, (9.9, -6.9, 3.7), (5.5, -1.6, 2.1)),
        (150, (9.3, -6.3, 3.6), (5.5, -1.6, 2.5)),
        (176, (7.0, -5.5, 10.5), L + Vector((0, 0, -13))),
        (200, (5, -1, 16), L + Vector((0, 0, -10))),
        (FRAMES, (3.5, 12.5, 29.5), L + Vector((0, 0, -3.0))),
    ])
    return FRAMES
