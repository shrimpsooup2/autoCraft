"""Shot 5 — survive, at night. A zombie behind glass can't reach the bot: it's greyed out and the bot keeps mining.
A creeper closes in: slow motion, a jump crit, poof. Skeleton arrows knock hearts off; the bot sprints out of range,
eats while its hearts refill, then comes back healthy, beats the skeleton and returns to work."""
import math
import random
import bpy
from mathutils import Vector
import kit

FRAMES = 540
HUD_IN, HUD_OUT = 212, 496
BOT_HOME = Vector((2.0, 0.5, 1.0))   # mining the stone outcrop to its east
EAT_SPOT = Vector((-5.0, -3.5, 1.0))
SK_HOME = Vector((8.0, 7.0, 3.0))     # on a mound
SK_STOP = Vector((4.0, 3.5, 1.0))     # where it gives up chasing: just out of reach of the eating bot
SK_RANGE = 11.0

_last_yaw = {}


def yaw_to(src, dst):
    return math.degrees(math.atan2(dst[0] - src[0], -(dst[1] - src[1])))


def face(rig, f, loc, yaw):
    """Place with the yaw unwrapped to the nearest turn from the rig's previous yaw."""
    prev = _last_yaw.get(rig.root.name)
    if prev is not None:
        while yaw - prev > 180:
            yaw -= 360
        while yaw - prev < -180:
            yaw += 360
    _last_yaw[rig.root.name] = yaw
    rig.place(f, loc, yaw_deg=yaw)


def stride(rig, f0, f1, period=14, amp=35, arms=True):
    legs4 = "leg_fl" in rig.pivots
    # stand still right up to the first step (otherwise the pose drifts in from the last key, far earlier)
    if legs4:
        rig.key(f0 - 2, leg_fl=0, leg_br=0, leg_fr=0, leg_bl=0)
    else:
        rig.key(f0 - 2, leg_l=0, leg_r=0, **({"arm_l": 0, "arm_r": 0} if arms else {}))
    f, i = f0, 0
    while f <= f1:
        s = 1 if i % 2 == 0 else -1
        if legs4:
            rig.key(f, leg_fl=s * amp * 0.7, leg_br=s * amp * 0.7, leg_fr=-s * amp * 0.7, leg_bl=-s * amp * 0.7)
        else:
            rig.key(f, leg_l=s * amp, leg_r=-s * amp, **({"arm_l": -s * amp * 0.8, "arm_r": s * amp * 0.8} if arms else {}))
        f += period // 2
        i += 1
    if legs4:
        rig.key(f1 + 3, leg_fl=0, leg_br=0, leg_fr=0, leg_bl=0)
    else:
        rig.key(f1 + 3, leg_l=0, leg_r=0, **({"arm_l": 0, "arm_r": 0} if arms else {}))


def walk(rig, pts, f0, speed, run=False, arms=True, on_key=None):
    """Move along the polyline at a steady speed (keys every 2 frames), facing the way it goes. Returns end frame."""
    pts = [Vector(p) for p in pts]
    lens = [(b - a).length for a, b in zip(pts, pts[1:])]
    total = sum(lens)
    dur = max(2, round(total / speed * kit.FPS))
    for k in list(range(0, dur, 2)) + [dur]:
        s, i = total * k / dur, 0
        while i < len(lens) - 1 and s > lens[i]:
            s -= lens[i]
            i += 1
        a, b = pts[i], pts[i + 1]
        p = a + (b - a) * (s / lens[i] if lens[i] else 0)
        face(rig, f0 + k, p, yaw_to(a, b))
        if on_key:
            on_key(f0 + k, p)
    stride(rig, f0, f0 + dur, period=10 if run else 14, amp=50 if run else 35, arms=arms)
    return f0 + dur


def gem(bot, color, name):
    mat = kit.mat_flat(name, color, emission=7.0)
    bpy.ops.mesh.primitive_cube_add(size=kit.PX * 2.2)
    g = bpy.context.active_object
    g.data.materials.append(mat)
    g.parent = bot.pivots["head"]
    g.location = bot.light.location
    g.rotation_euler = bot.light.rotation_euler
    return g


def held(bot, kind, size=0.55, loc=(0.05, -0.15, -0.62), rot=(-90, -30, 0)):
    ob = kit.item(kind, size=size)
    ob.parent = bot.pivots["arm_r"]
    ob.location = loc
    ob.rotation_euler = [math.radians(v) for v in rot]
    return ob


def torch(x, y, z):
    bpy.ops.mesh.primitive_cube_add(size=1, location=(x, y, z + 0.3))
    s = bpy.context.active_object
    s.scale = (0.12, 0.12, 0.6)
    s.data.materials.append(kit.mat_flat("torch_stick", kit.hexc("#6b4a23")))
    bpy.ops.mesh.primitive_cube_add(size=0.15, location=(x, y, z + 0.66))
    flame = bpy.context.active_object
    flame.data.materials.append(kit.mat_flat("flame", kit.hexc("#ffcf5a"), emission=12))
    lamp = bpy.data.lights.new("torch", "POINT")
    lamp.energy = 70
    lamp.color = (1.0, 0.68, 0.32)
    lamp.shadow_soft_size = 0.2
    lo = bpy.data.objects.new("torch", lamp)
    kit.link(lo)
    lo.location = (x, y, z + 0.8)


def ring(name, color, radius, emission=5.0, minor=0.05):
    bpy.ops.mesh.primitive_torus_add(major_radius=radius, minor_radius=minor, major_segments=96)
    r = bpy.context.active_object
    r.name = name
    r.data.materials.append(kit.mat_flat(name + "_m", color, emission=emission))
    return r


def arrow():
    root = kit.empty("arrow")
    root.rotation_mode = "QUATERNION"
    for dims, off, col in (((0.75, 0.035, 0.035), 0.0, "#8a6233"), ((0.12, 0.08, 0.08), 0.42, "#cfcfcf"),
                           ((0.16, 0.012, 0.13), -0.33, "#f2f2f2")):
        bpy.ops.mesh.primitive_cube_add(size=1, location=(off, 0, 0))
        b = bpy.context.active_object
        b.scale = dims
        b.data.materials.append(kit.mat_flat("arrow_" + col, kit.hexc(col)))
        b.parent = root
    return root


def shoot(src, dst, f0, f1, arc=0.8, stick=False):
    """An arrow flying src → dst over [f0, f1], pointing along its flight; hidden on arrival unless it sticks."""
    a = arrow()
    src, dst = Vector(src), Vector(dst)
    prev = None
    for f in range(f0, f1 + 1):
        t = (f - f0) / (f1 - f0)
        p = src.lerp(dst, t) + Vector((0, 0, 4 * arc * t * (1 - t)))
        if prev is not None:
            a.rotation_quaternion = (p - prev).to_track_quat("X", "Z")
            a.keyframe_insert("rotation_quaternion", frame=f)
        a.location = p
        a.keyframe_insert("location", frame=f)
        prev = p
    parts = [a, *a.children]
    for ob in parts:
        kit.visible(ob, [(1, False), (f0, True)] + ([] if stick else [(f1 + 1, False)]))
    return a


def flash(loc, f, energy=400, color=(1, 1, 1), parent=None, dur=6):
    lamp = bpy.data.lights.new("flash", "POINT")
    lamp.color = color
    lo = bpy.data.objects.new("flash", lamp)
    kit.link(lo)
    lo.location = loc
    if parent:
        lo.parent = parent
    for fr, e in ((f - 1, 0), (f, energy), (f + dur, 0)):
        lamp.energy = e
        lamp.keyframe_insert("energy", frame=fr)
    return lo


def hide_rig(rig, f):
    for _, mesh in rig.parts.values():
        kit.visible(mesh, [(1, True), (f, False)])


def stars(n=260, seed=5):
    rng = random.Random(seed)
    verts, faces = [], []
    for i in range(n):
        az, el = rng.uniform(-1.2, 1.2), rng.uniform(0.12, 1.2)
        d = Vector((math.sin(az) * math.cos(el), math.cos(az) * math.cos(el), math.sin(el))) * 260
        u = d.cross(Vector((0, 0, 1))).normalized()
        v = u.cross(d).normalized()
        s = rng.uniform(0.35, 0.9)
        k = len(verts)
        verts += [d + (-u - v) * s, d + (u - v) * s, d + (u + v) * s, d + (-u + v) * s]
        faces.append((k, k + 1, k + 2, k + 3))
    me = bpy.data.meshes.new("stars")
    me.from_pydata([tuple(p) for p in verts], [], faces)
    ob = bpy.data.objects.new("stars", me)
    kit.link(ob)
    ob.data.materials.append(kit.mat_flat("star", kit.hexc("#dfe8ff"), emission=6))
    ob.visible_shadow = False
    return ob


def build(preview=False):
    kit.reset(FRAMES, samples=24)
    kit.sky_grad(zenith="#04070f", horizon="#1d2d52", middle="#0b1530", ground="#0a0f1a", ambient=0.55, sun_elev=42,
                 sun_rot=60, sun_energy=1.6, sun_color="#a9c1ff")
    kit.bloom(threshold=0.8, size=7)
    rng = random.Random(12)
    stars()
    bpy.ops.mesh.primitive_plane_add(size=14, location=(-60, 200, 85), rotation=(math.radians(90), 0, math.radians(-15)))
    moon = bpy.context.active_object
    moon.data.materials.append(kit.mat_flat("moon", kit.hexc("#eef3ff"), emission=4.0))
    moon.visible_shadow = False

    # ---- world: a meadow, a mound for the skeleton, a stone outcrop, a glass pen, trees
    def ground_top(x, y):
        d = math.hypot(x - SK_HOME.x, y - SK_HOME.y)
        return 2 if d < 1.8 else 1 if d < 3.3 else 0
    world = {}
    for x in range(-26, 27):
        for y in range(-22, 30):
            top = ground_top(x, y)
            for z in range(-1, top + 1):
                world[(x, y, z)] = "grass" if z == top else "dirt"
    outcrop = {(3, 0, 1), (3, 1, 1), (4, 0, 1), (4, 1, 1), (4, -1, 1), (3, 0, 2), (4, 0, 2), (4, 1, 2), (3, 1, 2), (4, 0, 3)}
    mined = [(3, 0, 2), (3, 0, 1), (3, 1, 2)]
    for p in outcrop:
        if p not in mined:
            world[p] = "stone"
    mined_obs = []
    for p in mined:
        ob = kit.item("stone", size=1.0)
        ob.location = Vector(p) + Vector((0.5, 0.5, 0.5))
        mined_obs.append(ob)
    for _ in range(40):
        tx, ty = rng.randint(-24, 24), rng.randint(11, 27)
        if math.hypot(tx - SK_HOME.x, ty - SK_HOME.y) > 5 and not any(abs(tx - a) < 3 and abs(ty - b) < 3 for (a, b) in
                                                                        [(k[0], k[1]) for k, v in world.items() if v == "log"]):
            kit.tree(world, tx, ty, 1, rng.randint(4, 6), rng)
    for tx, ty in ((-14, 2), (-17, -6), (14, -8), (-12, 8)):
        kit.tree(world, tx, ty, 1, 5, rng)
    kit.block_mesh("ground", world)
    pen = {}
    for x in range(-8, -2):
        for z in range(1, 4):
            pen[(x, 4, z)] = "glass"
            pen[(x, 8, z)] = "glass"
    for y in range(5, 8):
        for z in range(1, 4):
            pen[(-8, y, z)] = "glass"
            pen[(-3, y, z)] = "glass"
    kit.block_mesh("pen", pen, material=kit.mat_blocks("glassmat", alpha=0.32, rough=0.08), cull_against=world)
    torch(0.3, 2.4, 1)
    torch(-1.7, 3.3, 1)

    # ---- the bot
    bot = kit.bot()
    kit.visible(bot.light, [(1, False)])
    g_green, g_amber = gem(bot, kit.GREEN, "gem_green"), gem(bot, kit.AMBER, "gem_amber")
    kit.visible(g_green, [(1, True), (288, False), (410, True)])
    kit.visible(g_amber, [(1, False), (288, True), (410, False)])
    kit.pop_in(g_amber, 288, dur=6, overshoot=1.6)
    kit.pop_in(g_green, 410, dur=6, overshoot=1.6)
    pick, sword, bread = held(bot, "pick_iron"), held(bot, "sword_iron"), held(bot, "bread", 0.6, (0, -0.12, -0.6), (-90, 0, 0))
    kit.visible(pick, [(1, True), (138, False), (214, True), (292, False), (500, True)])
    kit.visible(sword, [(1, False), (138, True), (214, False), (416, True), (500, False)])
    kit.visible(bread, [(1, False), (332, True), (402, False)])

    # A. mining while a zombie in a glass pen is noticed, found unreachable and ignored
    face(bot, 1, BOT_HOME, 90)
    kit.mine_swings(bot, 4, 132, period=8)
    for ob, f in zip(mined_obs, (40, 80, 120)):
        kit.pop_out(ob, f, dur=4)
        kit.burst(ob.location, kit.hexc("#8a8a8a"), f, n=10, speed=3, seed=f)
    zombie = kit.zombie()
    zombie.key(1, arm_l=-88, arm_r=-88)
    zf = 1
    for i in range(10):
        a, b = (Vector((-6.9, 6.0, 1)), Vector((-4.0, 6.2, 1))) if i % 2 == 0 else (Vector((-4.0, 6.2, 1)), Vector((-6.9, 6.0, 1)))
        zf = walk(zombie, [a, b], zf, 1.4, arms=False) + 10
        if zf > FRAMES:
            break
    z_ring, z_grey = ring("zring", kit.AMBER, 0.75), ring("zring_grey", kit.hexc("#8a8f96"), 0.75, emission=1.0)
    for r in (z_ring, z_grey):
        r.parent = zombie.root
        r.location = (0, 0, 0.05)
    kit.pop_in(z_ring, 20, dur=8, overshoot=1.4)
    kit.visible(z_ring, [(1, False), (20, True), (58, False)])
    kit.visible(z_grey, [(1, False), (58, True)])
    reach_from, reach_to = Vector((-5.2, 6.1, 1.9)), Vector((-2.6, 3.95, 1.9))   # toward the bot, stopped by the glass
    reach = kit.ribbon("reach", [reach_from, reach_from.lerp(reach_to, 0.5) + Vector((0, 0, 0.25)), reach_to], kit.CYAN,
                       width=0.05, emission=6.0, grow=(30, 44))
    kit.visible(reach, [(1, False), (30, True), (82, False)])
    xmat = kit.mat_flat("xmark", kit.hexc("#ffffff"), emission=5.0)
    xroot = kit.empty("xmark", reach_to + Vector((0, -0.08, 0)))
    for ang in (45, -45):
        bpy.ops.mesh.primitive_cube_add(size=1)
        b = bpy.context.active_object
        b.scale = (0.55, 0.05, 0.08)
        b.rotation_euler = (0, math.radians(ang), 0)
        b.data.materials.append(xmat)
        b.parent = xroot
        kit.visible(b, [(1, False), (45, True), (82, False)])
    kit.pop_in(xroot, 45, dur=8, overshoot=1.5)
    kit.burst(reach_to, kit.CYAN, 45, n=10, speed=2.0, seed=45, size=0.07, gravity=2)
    bpy.ops.mesh.primitive_cube_add(size=1, location=(0, 0, 0.98))
    veil = bpy.context.active_object
    veil.scale = (1.05, 0.62, 2.0)
    veil.parent = zombie.root
    veil_m = kit.mat_flat("veil", kit.hexc("#9aa0a8"), alpha=0.0, rough=0.9)
    veil.data.materials.append(veil_m)
    kit.animate_input(veil_m, "Alpha", [(1, 0.0), (58, 0.0), (70, 0.6)])

    # B. a creeper closes in; slow motion: a jump crit knocks it back, a second hit and it's gone
    creeper = kit.creeper()
    c_stop = Vector((3.6, -1.6, 1.0))
    walk(creeper, [(10.0, -5.0, 1.0), c_stop], 70, 3.0)
    face(creeper, 150, c_stop, yaw_to(c_stop, BOT_HOME))
    c_ring = ring("cring", kit.AMBER, 0.7)
    c_ring.parent = creeper.root
    c_ring.location = (0, 0, 0.05)
    kit.pop_in(c_ring, 100, dur=8, overshoot=1.4)
    for f, s in ((150, 1.0), (156, 1.08), (162, 1.0), (168, 1.1), (174, 1.02), (179, 1.13), (184, 1.0)):
        creeper.root.scale = (s, s, s)
        creeper.root.keyframe_insert("scale", frame=f)
    fuse = bpy.data.lights.new("fuse", "POINT")
    fuse.color = (1, 1, 1)
    fo = bpy.data.objects.new("fuse", fuse)
    kit.link(fo)
    fo.parent = creeper.root
    fo.location = (0, -0.6, 1.1)
    for k, f in enumerate(range(150, 182, 4)):
        fuse.energy = 260 if k % 2 == 0 else 0
        fuse.keyframe_insert("energy", frame=f)
    fuse.energy = 0
    fuse.keyframe_insert("energy", frame=183)
    # the bot turns, swaps to its sword and jumps (slow motion: long airtime)
    face(bot, 134, BOT_HOME, 90)
    face(bot, 144, BOT_HOME, yaw_to(BOT_HOME, c_stop))
    j0, apex, land = Vector((2.0, 0.5, 0.9)), Vector((2.4, 0.0, 1.9)), Vector((2.65, -0.3, 1.0))
    face(bot, 152, BOT_HOME, yaw_to(BOT_HOME, c_stop))
    face(bot, 158, j0, yaw_to(BOT_HOME, c_stop))
    face(bot, 172, apex, yaw_to(BOT_HOME, c_stop))
    face(bot, 188, land, yaw_to(BOT_HOME, c_stop))
    bot.key(150, arm_r=0, leg_l=0, leg_r=0)
    bot.key(158, arm_r=-30, leg_l=20, leg_r=20)
    bot.key(168, arm_r=-170, leg_l=-25, leg_r=10)
    bot.key(176, arm_r=-172)
    bot.key(180, arm_r=-25)
    bot.key(188, leg_l=0, leg_r=0, arm_r=-20)
    hit1 = 179
    shoulder = apex + Vector((0, 0, 1.3))
    fwd = (c_stop - BOT_HOME).normalized()
    arc = [shoulder + fwd * (0.75 * math.sin(math.radians(a))) + Vector((0, 0, 0.75 * math.cos(math.radians(a))))
           for a in range(10, 160, 15)]
    trail = kit.ribbon("trail", arc, kit.hexc("#ffffff"), width=0.02, emission=5.0, grow=(176, 181))
    kit.visible(trail, [(1, False), (176, True), (186, False)])
    c_head = c_stop + Vector((0, 0, 1.4))
    kit.burst(c_head, kit.hexc("#fff0a0"), hit1, n=16, speed=2.0, life=30, size=0.08, seed=179, gravity=3)
    kit.burst(c_head, kit.hexc("#ffffff"), hit1, n=8, speed=1.2, life=24, size=0.05, seed=180, gravity=2)
    flash(c_head, hit1, energy=500)
    kb = Vector((4.9, -3.1, 1.0))
    face(creeper, hit1, c_stop, yaw_to(c_stop, BOT_HOME))
    face(creeper, 186, (c_stop + kb) / 2 + Vector((0, 0, 0.5)), yaw_to(c_stop, BOT_HOME))
    face(creeper, 193, kb, yaw_to(c_stop, BOT_HOME))
    step = Vector((3.7, -1.6, 1.0))
    face(bot, 192, land, yaw_to(land, kb))
    face(bot, 198, step, yaw_to(step, kb))
    bot.key(195, arm_r=-150)
    bot.key(201, arm_r=-25)
    bot.key(208, arm_r=0)
    hit2 = 200
    kit.burst(kb + Vector((0, 0, 1.2)), kit.hexc("#fff0a0"), hit2, n=8, speed=2.5, seed=200, size=0.06)
    kit.burst(kb + Vector((0, 0, 0.9)), kit.hexc("#e6e6e6"), hit2 + 1, n=26, speed=1.6, life=26, size=0.32, seed=201,
              gravity=-1.5)
    hide_rig(creeper, hit2 + 2)
    kit.visible(c_ring, [(1, False), (100, True), (hit2 + 2, False)])

    # C. a skeleton shoots from its mound; hearts drop; the bot sprints out of its range
    skel = kit.skeleton()
    skel.key(1, arm_l=(-88, 0, 12), arm_r=(-88, 0, -12))
    face(skel, 1, SK_HOME, yaw_to(SK_HOME, BOT_HOME))
    bow = kit.empty("bow")
    for dz, ang in ((0.0, 0), (0.22, -35), (-0.22, 35)):
        bpy.ops.mesh.primitive_cube_add(size=1, location=(0, 0, dz))
        b = bpy.context.active_object
        b.scale = (0.05, 0.05, 0.28)
        b.rotation_euler = (math.radians(ang), 0, 0)
        b.data.materials.append(kit.mat_flat("bow", kit.hexc("#7a5530")))
        b.parent = bow
    bow.parent = skel.pivots["arm_l"]
    bow.location = (0, -0.05, -0.62)
    sk_ring = ring("skring", kit.AMBER, SK_RANGE, emission=3.0, minor=0.06)
    sk_ring.location = (SK_HOME.x, SK_HOME.y, 1.06)
    kit.pop_in(sk_ring, 222, dur=12, overshoot=1.05)
    face(bot, 214, BOT_HOME, yaw_to(BOT_HOME, step))
    walk(bot, [step, BOT_HOME + Vector((0.01, 0, 0))], 210, 4.0)
    face(bot, 226, BOT_HOME, 90)
    kit.mine_swings(bot, 228, 288, period=8)
    hand = SK_HOME + Vector((0, 0, 1.35))
    hits = [242, 264, 286]
    for k, fh in enumerate(hits):
        f0 = fh - 16
        skel.key(f0 - 4, arm_r=(-88, 0, -12))
        skel.key(f0, arm_r=(-70, 0, -12))
        skel.key(f0 + 4, arm_r=(-88, 0, -12))
        chest = BOT_HOME + Vector((0, 0, 1.2))
        shoot(hand, chest, f0, fh, arc=0.6)
        kit.burst(chest, kit.hexc("#d23a3a"), fh, n=8, speed=2.2, seed=fh, size=0.07)
        flash(chest, fh, energy=120, color=(1, 0.2, 0.2), dur=4)
        bot.key(fh, head=(0, 0, 0))
        bot.key(fh + 2, head=(-12, 0, 8))
        bot.key(fh + 8, head=(0, 0, 0))
    # recovery: out of range, fast
    flee = [BOT_HOME, Vector((0.0, -1.5, 1.0)), EAT_SPOT]
    face(bot, 290, BOT_HOME, 90)
    t_flee = walk(bot, flee, 292, 6.0, run=True)
    miss_to = SK_HOME + (EAT_SPOT - SK_HOME).normalized() * (SK_RANGE - 0.4)
    shoot(hand, Vector((miss_to.x, miss_to.y, 1.05)), 300, 318, arc=1.2, stick=True)
    skel.key(296, arm_r=(-88, 0, -12))
    skel.key(300, arm_r=(-70, 0, -12))
    skel.key(304, arm_r=(-88, 0, -12))

    # D. eat while the hearts come back; the skeleton follows but stops short, its range trailing it
    face(bot, t_flee + 2, EAT_SPOT, yaw_to(EAT_SPOT, SK_STOP))
    bot.key(334, arm_r=0, head=(0, 0, 0))
    bot.key(340, arm_r=(-72, 0, 28))
    for k, f in enumerate(range(344, 400, 14)):
        bot.key(f, head=(10, 0, 0))
        bot.key(f + 7, head=(-4, 0, 0))
        kit.burst(EAT_SPOT + Vector((0.25, -0.2, 1.55)) + (SK_STOP - EAT_SPOT).normalized() * 0.3, kit.hexc("#c98b3c"),
                  f + 3, n=7, speed=1.6, seed=f, size=0.05)
    bot.key(400, arm_r=(-72, 0, 28), head=(0, 0, 0))
    bot.key(406, arm_r=0)
    for f, s in ((340, 0.42), (362, 0.32), (384, 0.2), (400, 0.12)):
        bread.scale = (s / 0.42,) * 3
        bread.keyframe_insert("scale", frame=f)

    def ring_follow(f, p):
        sk_ring.location = (p.x, p.y, 1.06)
        sk_ring.keyframe_insert("location", frame=f)
    sk_path = [SK_HOME, Vector((6.9, 6.0, 2.0)), Vector((5.6, 4.8, 1.0)), SK_STOP]
    ring_follow(338, SK_HOME)
    t_sk = walk(skel, sk_path, 340, 1.5, arms=False, on_key=ring_follow)
    face(skel, t_sk + 4, SK_STOP, yaw_to(SK_STOP, EAT_SPOT))

    # E. healthy again: it charges, two hits, and it's back to work
    charge_to = SK_STOP + (EAT_SPOT - SK_STOP).normalized() * 1.3
    t_ch = walk(bot, [EAT_SPOT, charge_to], 418, 6.5, run=True)
    hit3, hit4 = t_ch + 3, t_ch + 15
    for h in (hit3, hit4):
        bot.key(h - 4, arm_r=-150)
        bot.key(h + 1, arm_r=-25)
        kit.burst(SK_STOP + Vector((0, 0, 1.3)), kit.hexc("#fff0a0"), h, n=10, speed=2.4, seed=h, size=0.06)
        flash(SK_STOP + Vector((0, 0, 1.3)), h, energy=250)
    face(skel, hit3, SK_STOP, yaw_to(SK_STOP, EAT_SPOT))
    back = SK_STOP + (SK_STOP - charge_to).normalized() * 0.6
    face(skel, hit3 + 6, back, yaw_to(SK_STOP, EAT_SPOT))
    face(bot, hit3 + 4, charge_to, yaw_to(charge_to, back))
    face(bot, hit4 - 4, charge_to + (back - charge_to).normalized() * 0.5, yaw_to(charge_to, back))
    kit.burst(back + Vector((0, 0, 0.9)), kit.hexc("#e6e6e6"), hit4 + 1, n=24, speed=1.6, life=26, size=0.3, seed=777,
              gravity=-1.5)
    hide_rig(skel, hit4 + 2)
    for ob in (bow, *bow.children):
        kit.visible(ob, [(1, True), (hit4 + 2, False)])
    sk_ring.scale = (1, 1, 1)
    kit.pop_out(sk_ring, hit4 + 2, dur=8)
    home_from = charge_to + (back - charge_to).normalized() * 0.5
    t_home = walk(bot, [home_from, BOT_HOME], hit4 + 12, 4.0)
    face(bot, t_home + 4, BOT_HOME, 90)
    kit.mine_swings(bot, t_home + 6, FRAMES, period=8)

    # ---- camera, then the HUD hearts parented to it
    cam, tgt = kit.camera(lens=30)
    fill = bpy.data.lights.new("fill", "SUN")   # a soft key along the view: whatever the camera looks at reads at night
    fill.energy = 0.9
    fill.color = (0.72, 0.82, 1.0)
    fill.use_shadow = False
    fo = bpy.data.objects.new("fill", fill)
    kit.link(fo)
    fo.parent = cam
    fo.rotation_euler = (math.radians(-14), math.radians(20), 0)
    kit.cam_keys(cam, tgt, [
        (1, (-5, -15, 7.5), (-1.5, 3, 1.6)),
        (80, (-3.5, -14, 6.5), (-0.5, 2.5, 1.6)),
        (130, (0.5, -10, 4.0), (3, -1, 1.4)),
        (150, (-3.4, -7.4, 2.9), (3.0, -0.8, 2.0)),
        (196, (-3.0, -6.8, 2.8), (3.3, -1.2, 1.9)),
        (226, (-3, -19, 10), (3.5, 3, 1.5)),
        (286, (-2.5, -18, 9.5), (3, 2.5, 1.5)),
        (322, (-5, -11, 4.2), (-4.0, -3, 1.6)),
        (340, (-0.6, -5.4, 2.7), (-5.0, -3.3, 1.7)),
        (414, (-0.9, -5.1, 2.6), (-5.0, -3.4, 1.7)),
        (462, (-2, -8, 4), (2.5, 2.0, 1.5)),
        (FRAMES, (6.5, -8.5, 4.5), (2.5, 1.0, 1.5)),
    ])
    hud = kit.mat_vcol("hud", emission=1.3)
    HS = 0.036
    for i in range(10):
        pair = []
        for kind, dz in (("heart_empty", -0.002), ("heart", 0.0)):
            h = kit.item(kind, name=f"{kind}{i}", size=HS)
            h.data.materials.clear()
            h.data.materials.append(hud)
            h.parent = cam
            h.rotation_euler = (math.radians(-90), 0, 0)
            h.location = ((i - 4.5) * HS * 1.1, -0.275, -1.0 + dz)
            pair.append(h)
        empty, full = pair
        events = [(HUD_IN + i * 2, "in"), (HUD_OUT, "out")]
        if i >= 4:
            events.append((hits[(9 - i) // 2], "out"))
            events.append((350 + (i - 4) * 10, "in"))
        for ob in pair:
            evs = events if ob is full else [(HUD_IN + i * 2, "in"), (HUD_OUT, "out")]
            for f, kind in sorted(evs):
                if kind == "in":
                    kit.pop_in(ob, f, dur=6, overshoot=1.35, final=(1, 1, 1))
                else:
                    ob.scale = (1, 1, 1)
                    kit.pop_out(ob, f, dur=4)
    return FRAMES
