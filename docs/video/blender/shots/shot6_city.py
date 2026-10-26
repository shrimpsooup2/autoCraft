"""Shot 6 — structure loot in an ancient city. Deep dark: the bot sneaks along a route that never touches a sculk
sensor or shrieker. At the chest, the vibration line to a nearby sensor shows it would be heard; the bot places
wool in between, the line is cut, the chest opens and the loot (echo shards, an enchanted book, a golden apple)
spills out and is collected. Then it sneaks off."""
import heapq
import math
import random
import bpy
from mathutils import Vector
import kit

FRAMES = 360
START = (-4, -5)
CHEST = (9, 4)
STAND = (8, 4)                       # beside the chest, facing +X: its lantern hand and the chest's front face the camera
SENSORS = [(-6, -3), (-5, -1), (-3, -2), (-2, 0), (0, -1), (1, 1), (3, 0), (4, 2), (6, 1), (7, 3), (11, 6)]
SHRIEKERS = [(-4, 1), (2, -2), (5, -1), (8, 1)]
EAR = (11, 6)                        # the sensor that would hear the chest
WOOL = (10, 5)
WALK0, SPEED = 8, 2.8
SOUL = (0.32, 0.86, 1.0)
LANTERN_ARM = -38                    # the lantern held out in front, lighting its way (and its face)


def astar(start, goal, blocked, bounds):
    (x0, x1), (y0, y1) = bounds

    def h(p):
        dx, dy = abs(p[0] - goal[0]), abs(p[1] - goal[1])
        return max(dx, dy) + 0.414 * min(dx, dy)
    openq, g, parent, closed = [(h(start), 0.0, start)], {start: 0.0}, {}, set()
    while openq:
        _, c, cur = heapq.heappop(openq)
        if cur in closed:
            continue
        closed.add(cur)
        if cur == goal:
            break
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                if not dx and not dy:
                    continue
                n = (cur[0] + dx, cur[1] + dy)
                if not (x0 <= n[0] <= x1 and y0 <= n[1] <= y1) or n in blocked:
                    continue
                if dx and dy and ((cur[0] + dx, cur[1]) in blocked or (cur[0], cur[1] + dy) in blocked):
                    continue   # no corner cutting past a sensor
                nc = c + (1.414 if dx and dy else 1.0)
                if nc < g.get(n, 1e9):
                    g[n], parent[n] = nc, cur
                    heapq.heappush(openq, (nc + h(n), nc, n))
    path = [goal]
    while path[-1] in parent:
        path.append(parent[path[-1]])
    return path[::-1]


def simplify(path):
    """Keep only the corners of a grid path."""
    out = [path[0]]
    for a, b, c in zip(path, path[1:], path[2:]):
        if (b[0] - a[0], b[1] - a[1]) != (c[0] - b[0], c[1] - b[1]):
            out.append(b)
    out.append(path[-1])
    return out


def yaw_to(src, dst):
    return math.degrees(math.atan2(dst[0] - src[0], -(dst[1] - src[1])))


def unwrap(yaw, prev):
    if prev is None:
        return yaw
    while yaw - prev > 180:
        yaw -= 360
    while yaw - prev < -180:
        yaw += 360
    return yaw


def sneak_pose(bot, f, on=True):
    """Minecraft's sneak: body leans forward, head and shoulders drop and come forward."""
    k = 1.0 if on else 0.0
    bot.key(f, body=(26 * k, 0, 0))
    for part in ("head", "arm_l", "arm_r"):
        p = bot.pivots[part]
        base = bot.parts[part][0]
        p.location = (base[0], base[1] - 0.26 * k, base[2] - 0.1 * k)
        p.keyframe_insert("location", frame=f)


def sneak_steps(bot, f0, f1):
    f, n = f0, 0
    while f <= f1:
        s = 1 if n % 2 == 0 else -1
        bot.key(f, leg_l=s * 22, leg_r=-s * 22, arm_l=LANTERN_ARM - s * 6, arm_r=s * 14)
        f += 9
        n += 1
    bot.key(f1 + 3, leg_l=0, leg_r=0, arm_l=LANTERN_ARM, arm_r=0)


def move(bot, pts, f0, speed, prev_yaw):
    """Steady motion along a polyline (keys every 2 frames), facing the way it goes. Returns (end frame, yaw)."""
    lens = [(b - a).length for a, b in zip(pts, pts[1:])]
    total = sum(lens)
    dur = max(2, round(total / speed * kit.FPS))
    yaw = prev_yaw
    for k in list(range(0, dur, 2)) + [dur]:
        s, i = total * k / dur, 0
        while i < len(lens) - 1 and s > lens[i]:
            s -= lens[i]
            i += 1
        a, b = pts[i], pts[i + 1]
        yaw = unwrap(yaw_to(a, b), yaw)
        bot.place(f0 + k, a + (b - a) * (s / lens[i]), yaw_deg=yaw)
    sneak_steps(bot, f0, f0 + dur)
    return f0 + dur, yaw


# A sculk sensor's tendril, after the game's model: a dark flame-shaped feeler on each corner of the half-slab, the
# planes turned ±45°, a lighter teal at the root. Inactive (nothing heard), so they don't glow. Rows top → bottom.
TENDRIL = ["....d...",
           "...dd...",
           "..ddd...",
           "..dddd..",
           "..dddd..",
           ".ddmdd..",
           ".dmmmdd.",
           ".bmbbmb."]
TENDRIL_COLORS = {"d": "#072a33", "m": "#17525f", "b": "#1f7d92"}


def sensor_tendrils(x, y, mat, proto=[]):
    """Four tendrils on the slab's corners, swaying a little."""
    if not proto:
        vox = {(c, 0, 7 - r): kit.hexc(TENDRIL_COLORS[ch]) for r, row in enumerate(TENDRIL) for c, ch in enumerate(row)
               if ch != "."}
        ob = kit.color_mesh("tendril", vox, size=1 / 16, center=(4, 0.5, 0), material=mat)
        proto.append(ob)
        ob.hide_render = True
    for k, ((px, py), ang) in enumerate((((3, 3), 45), ((13, 3), -45), ((3, 13), -45), ((13, 13), 45))):
        t = proto[0].copy()
        kit.link(t)
        t.hide_render = False
        t.location = (x + px / 16, y + py / 16, 1.5)
        t.scale = (1.15, 1.15, 1.15)
        phase = (x * 1.7 + y * 2.3 + k * 1.9)
        for f in range(1, FRAMES + 1, 8):
            t.rotation_euler = (math.radians(7 * math.sin(f * 0.07 + phase)), 0, math.radians(ang))
            t.keyframe_insert("rotation_euler", frame=f)


def soul_lantern(x, y, z, energy=90):
    bpy.ops.mesh.primitive_cube_add(size=0.34, location=(x, y, z))
    c = bpy.context.active_object
    c.data.materials.append(kit.mat_flat("lantern_cage", kit.hexc("#2a2f36")))
    bpy.ops.mesh.primitive_cube_add(size=0.22, location=(x, y - 0.07, z))
    f = bpy.context.active_object
    f.data.materials.append(kit.mat_flat("soulfire", kit.hexc("#6ff3ff"), emission=14))
    lamp = bpy.data.lights.new("soul", "POINT")
    lamp.energy = energy
    lamp.color = SOUL
    lamp.shadow_soft_size = 0.3
    lo = bpy.data.objects.new("soul", lamp)
    kit.link(lo)
    lo.location = (x, y - 0.3, z - 0.1)


def held_lantern(bot):
    """A soul lantern hanging from the bot's arm_l (rig +X is arm_r; facing +X, arm_l is toward the camera at -Y)."""
    root = kit.empty("lantern")
    root.parent = bot.pivots["arm_l"]
    root.location = (0, -0.04, -0.72)
    cage = kit.mat_flat("held_cage", kit.hexc("#30353d"), rough=0.4)
    fire = kit.mat_flat("held_fire", kit.hexc("#6ff3ff"), emission=9)
    parts = [((0.28, 0.28, 0.05), (0, 0, -0.04), cage), ((0.28, 0.28, 0.05), (0, 0, -0.36), cage),
             ((0.04, 0.04, 0.1), (0, 0, 0.03), cage), ((0.17, 0.17, 0.22), (0, 0, -0.2), fire)]
    parts += [((0.04, 0.04, 0.3), (sx * 0.11, sy * 0.11, -0.2), cage) for sx in (-1, 1) for sy in (-1, 1)]   # corner posts
    for size, loc, mat in parts:
        bpy.ops.mesh.primitive_cube_add(size=1)
        b = bpy.context.active_object
        b.scale = size
        b.data.materials.append(mat)
        b.parent = root
        b.location = loc
        b.visible_shadow = False   # the light sits inside it
    lamp = bpy.data.lights.new("held_soul", "POINT")
    lamp.energy = 240
    lamp.color = SOUL
    lamp.shadow_soft_size = 0.12
    lo = bpy.data.objects.new("held_soul", lamp)
    kit.link(lo)
    lo.parent = root
    lo.location = (0, 0, -0.2)
    return root


def ring_at(name, loc, color, radius=0.7, emission=6.0):
    bpy.ops.mesh.primitive_torus_add(major_radius=radius, minor_radius=0.035, location=loc)
    r = bpy.context.active_object
    r.name = name
    r.data.materials.append(kit.mat_flat(name, color, emission=emission))
    return r


def build(preview=False):
    kit.reset(FRAMES, samples=24)
    kit.sky_grad(zenith="#010305", horizon="#04121a", middle="#020a10", ground="#010204", ambient=0.2, sun_elev=55,
                 sun_rot=-25, sun_energy=0.3, sun_color="#9fd8ff")
    kit.bloom(threshold=0.7, size=7)
    rng = random.Random(31)

    # ---- the city: tiled floor with sculk creeping over it, a brick wall with arches and pillars, the great frame
    world = {}
    for x in range(-18, 19):
        for y in range(-10, 13):
            world[(x, y, 0)] = "deepslate_tiles" if (x + y) % 7 else "deepslate_bricks"
            world[(x, y, -1)] = "deepslate"
    for px, py in SENSORS + SHRIEKERS + [(-7, 2), (0, 4), (12, 1), (-11, 5), (14, -4)]:
        for x in range(px - 3, px + 4):
            for y in range(py - 3, py + 4):
                if (x, y, 0) in world and math.hypot(x - px, y - py) + rng.uniform(-0.6, 0.8) < 2.2:
                    world[(x, y, 0)] = "sculk"
    for x in range(-18, 19):
        for z in range(1, 6):
            if not (z <= 3 and x % 6 in (2, 3, 4)):
                world[(x, 12, z)] = "deepslate_bricks"
        world[(x, 11, 6)] = "deepslate_bricks"
    for x in (-15, -9, -3, 3, 9, 15):
        for z in range(1, 7):
            world[(x, 10, z)] = "deepslate_bricks"
    for x in range(-9, 10):
        for y in range(14, 17):
            for z in range(0, 3):
                world[(x, y, z)] = "deepslate_tiles" if z == 2 else "deepslate_bricks"
    for z in range(3, 15):
        for x in (-7, -6, 6, 7):
            world[(x, 15, z)] = "reinforced"
    for x in range(-7, 8):
        for z in (14, 15):
            world[(x, 15, z)] = "reinforced"
    for x, z in ((-8, 13), (-9, 13), (-8, 12), (8, 13), (9, 13), (8, 12), (-5, 13), (5, 13), (-8, 4), (8, 4), (-8, 3), (8, 3)):
        world[(x, 15, z)] = "reinforced"
    for x in range(-6, 7):
        world[(x, 15, 16)] = "deepslate_bricks"
    kit.block_mesh("city", world)
    for kind, cells in (("sculk_sensor", SENSORS), ("shrieker", SHRIEKERS)):   # both are half-height blocks
        for x, y in cells:
            ob = kit.block_mesh(f"{kind}{x}_{y}", {(0, 0, 0): kind}, origin=(0.5, 0.5, 0))
            ob.location = (x + 0.5, y + 0.5, 1)
            ob.scale = (1, 1, 0.5)
    bpy.ops.mesh.primitive_plane_add(size=1, location=(0, 22, 8), rotation=(math.radians(90), 0, 0))
    cave = bpy.context.active_object   # the far cave wall, faintly lit, so the great frame reads as a silhouette
    cave.scale = (90, 30, 1)
    cave.data.materials.append(kit.mat_flat("cavewall", kit.hexc("#0a2c35"), emission=0.45))
    tendril = kit.mat_vcol("tendril", emission=0.5)
    for x, y in SENSORS:
        sensor_tendrils(x, y, tendril)
    for x in (-15, -9, -3, 3, 9, 15):
        soul_lantern(x + 0.5, 9.6, 5.2)
    soul_lantern(-7.5, 13.2, 3.4, 60)
    soul_lantern(7.5, 13.2, 3.4, 60)
    soul_lantern(STAND[0] + 4.5, STAND[1] + 1.5, 1.2, 50)
    soul_lantern(-10.5, -5.5, 1.2, 50)
    soul_lantern(1.5, 4.5, 1.2, 50)
    glow = bpy.data.lights.new("frameglow", "AREA")
    glow.energy = 900
    glow.color = SOUL
    glow.size = 10
    go = bpy.data.objects.new("frameglow", glow)
    kit.link(go)
    go.location = (0, 18, 9)
    go.rotation_euler = (math.radians(-90), 0, 0)
    motes = kit.mat_flat("mote", kit.hexc("#7ff6f2"), emission=6)
    for i in range(90):
        p0 = Vector((rng.uniform(-16, 16), rng.uniform(-8, 14), rng.uniform(0.5, 8)))
        bpy.ops.mesh.primitive_cube_add(size=rng.uniform(0.03, 0.07), location=p0)
        m = bpy.context.active_object
        m.data.materials.append(motes)
        m.keyframe_insert("location", frame=1)
        m.location = p0 + Vector((rng.uniform(-0.6, 0.6), rng.uniform(-0.6, 0.6), rng.uniform(0.8, 1.8)))
        m.keyframe_insert("location", frame=FRAMES)
        kit.linear(m)

    # ---- the chest
    cx, cy = CHEST
    base, hinge = kit.chest(cx, cy)

    # ---- the route: around every sensor and shrieker
    blocked = set(SENSORS) | set(SHRIEKERS) | {CHEST, WOOL}
    path = astar(START, STAND, blocked, ((-16, 16), (-8, 9)))
    corners = simplify(path)
    route = [Vector((x + 0.5, y + 0.5, 1.0)) for x, y in corners]
    rib = kit.ribbon("route", [p + Vector((0, 0, 0.06)) for p in route], kit.AMBER, width=0.05, emission=5.0,
                     grow=(12, 48), smooth=False)
    kit.visible(rib, [(1, False), (12, True), (240, False)])
    near = [s for s in SENSORS + SHRIEKERS if s != EAR and min(math.hypot(s[0] - x, s[1] - y) for x, y in path) <= 1.5]
    for k, (sx, sy) in enumerate(near):
        r = ring_at(f"nostep{k}", (sx + 0.5, sy + 0.5, 1.53), kit.hexc("#ff5a4a"), radius=0.62, emission=3.0)
        f0 = 16 + k * 3
        kit.pop_in(r, f0, dur=8, overshoot=1.4)
        kit.visible(r, [(1, False), (f0, True), (240, False)])

    # ---- the bot sneaks in
    bot = kit.bot()
    bot.light.data.materials.clear()
    bot.light.data.materials.append(kit.mat_flat("light_g", kit.GREEN, emission=7))
    sneak_pose(bot, 1)
    bot.key(1, arm_l=LANTERN_ARM)
    lantern = held_lantern(bot)
    t_arrive, yaw = move(bot, route, WALK0, SPEED, None)
    here = route[-1]
    bot.key(t_arrive + 10, arm_l=-10)   # lantern down at its side while it works at the chest

    # ---- at the chest: the vibration opening it would make reaches a sensor; wool cuts it
    ear = Vector((EAR[0] + 0.5, EAR[1] + 0.5, 1.5))
    chest_c = Vector((cx + 0.5, cy + 0.5, 1.5))
    wool_c = Vector((WOOL[0] + 0.5, WOOL[1] + 0.5, 1.5))
    t0 = t_arrive + 4
    wave = kit.ribbon("vibration", [chest_c, chest_c.lerp(ear, 0.5) + Vector((0, 0, 0.35)), ear], kit.hexc("#ff4a3a"),
                      width=0.05, emission=7.0, grow=(t0, t0 + 10))
    kit.visible(wave, [(1, False), (t0, True), (t0 + 28, False)])
    ear_ring = ring_at("ear_ring", (ear.x, ear.y, 1.55), kit.hexc("#ff4a3a"), radius=0.75)
    kit.pop_in(ear_ring, t0 + 10, dur=8, overshoot=1.5)
    kit.visible(ear_ring, [(1, False), (t0 + 10, True), (t0 + 30, False)])
    t_wool = t0 + 22
    bot.place(t0 + 12, here, yaw_deg=yaw)
    yaw = unwrap(yaw_to(here, wool_c), yaw)
    bot.place(t0 + 18, here, yaw_deg=yaw)
    bot.key(t_wool - 5, arm_r=0)
    bot.key(t_wool, arm_r=-80)
    bot.key(t_wool + 6, arm_r=0)
    wool = kit.item("wool_red", size=1.0)
    wool.location = wool_c
    kit.pop_in(wool, t_wool, dur=6, overshoot=1.25)
    kit.burst(wool_c, kit.hexc("#e5352b"), t_wool, n=10, speed=2, seed=t_wool, size=0.06, gravity=4)
    cut = kit.ribbon("vibration_cut", [chest_c, chest_c.lerp(wool_c, 0.5) + Vector((0, 0, 0.12)), wool_c],
                     kit.hexc("#ff4a3a"), width=0.05, emission=7.0)
    kit.visible(cut, [(1, False), (t0 + 28, True), (t0 + 44, False)])
    calm = ring_at("calm_ring", (ear.x, ear.y, 1.55), kit.GREEN, radius=0.7, emission=5.0)
    kit.pop_in(calm, t0 + 30, dur=8, overshoot=1.5)
    kit.visible(calm, [(1, False), (t0 + 30, True), (t0 + 56, False)])

    # ---- open, loot, collect
    t_open = t0 + 46
    bot.place(t_open - 8, here, yaw_deg=yaw)
    yaw = unwrap(yaw_to(here, chest_c), yaw)
    bot.place(t_open - 2, here, yaw_deg=yaw)
    bot.key(t_open - 4, arm_r=0)
    bot.key(t_open, arm_r=-70)
    bot.key(t_open + 6, arm_r=0)
    hinge.keyframe_insert("rotation_euler", frame=t_open)
    for fr, deg in ((t_open + 7, -105), (t_open + 10, -98), (t_open + 52, -98), (t_open + 58, 0)):
        hinge.rotation_euler = (math.radians(deg), 0, 0)
        hinge.keyframe_insert("rotation_euler", frame=fr)
    inside = kit.mat_flat("chest_glow", kit.hexc("#ffe6a0"), emission=0.001)
    kit.animate_input(inside, "Emission Strength", [(t_open, 0), (t_open + 4, 18), (t_open + 30, 4), (t_open + 56, 0)])
    bpy.ops.mesh.primitive_plane_add(size=0.7, location=(cx + 0.5, cy + 0.5, 1.62))
    gl = bpy.context.active_object
    gl.data.materials.append(inside)
    lamp = bpy.data.lights.new("chestlight", "POINT")
    lamp.color = (1.0, 0.85, 0.55)
    lo = bpy.data.objects.new("chestlight", lamp)
    kit.link(lo)
    lo.location = (cx + 0.5, cy + 0.3, 2.3)
    for fr, e in ((t_open, 0), (t_open + 5, 160), (t_open + 30, 60), (t_open + 56, 0)):
        lamp.energy = e
        lamp.keyframe_insert("energy", frame=fr)
    loot_mat = kit.mat_vcol("loot", emission=1.6)
    t_take = t_open + 40
    grab = here + Vector((0, 0, 1.15))
    for k, kind in enumerate(["echo_shard", "book_ench", "echo_shard", "apple_gold", "echo_shard"]):
        it = kit.item(kind, size=0.5)
        it.data.materials.clear()
        it.data.materials.append(loot_mat)
        f0 = t_open + 6 + k * 3
        hover = Vector((cx + 0.8 + (k - 2) * 0.6, cy + 0.1 - 0.2 * abs(k - 2), 2.55 + 0.25 * (k % 2)))
        it.location = chest_c + Vector((0, 0, 0.1))
        it.keyframe_insert("location", frame=f0)
        kit.pop_in(it, f0, dur=7, overshoot=1.4)
        it.location = hover
        it.keyframe_insert("location", frame=f0 + 10)
        for fr in range(f0, t_take + k * 2 + 1, 3):
            it.rotation_euler = (0, 0, (fr - f0) * 0.14)
            it.keyframe_insert("rotation_euler", frame=fr)
        it.location = hover + Vector((0, 0, 0.12))
        it.keyframe_insert("location", frame=t_take + k * 2)
        it.location = grab
        it.keyframe_insert("location", frame=t_take + k * 2 + 7)
        it.scale = (1, 1, 1)
        kit.pop_out(it, t_take + k * 2 + 5, dur=3)
        kit.burst(hover, kit.hexc("#7ff6f2"), f0 + 8, n=6, speed=1.4, life=16, seed=900 + k, size=0.05, gravity=1)

    # ---- and away, still sneaking
    t_away = t_take + 18
    bot.place(t_away, here, yaw_deg=yaw)
    move(bot, [here, here + Vector((-1.5, -2.0, 0)), here + Vector((-4.5, -4.5, 0))], t_away + 2, SPEED, yaw)

    # ---- camera: down from the great frame onto the bot, along with it, close on the chest
    cam, tgt = kit.camera(lens=30)
    fill = bpy.data.lights.new("fill", "POINT")
    fill.energy = 150
    fill.color = (0.6, 0.85, 1.0)
    fill.shadow_soft_size = 2.0
    fo = bpy.data.objects.new("fill", fill)
    kit.link(fo)
    fo.parent = cam
    fo.location = (1.0, 1.0, 0)
    mid = route[len(route) // 2]
    kit.cam_keys(cam, tgt, [
        (1, (-2, -20, 15), (0, 12, 9)),
        (50, (-7, -14, 7), (-3, -2, 1.2)),
        (130, (mid.x - 4, mid.y - 11, 6.0), (mid.x + 1, mid.y + 1, 1.2)),
        (t_arrive - 10, (here.x + 3.0, here.y - 6.5, 3.8), (here.x + 0.8, here.y + 0.5, 1.4)),
        (t_open, (here.x + 1.6, here.y - 5.8, 3.3), (here.x + 1.0, here.y + 0.4, 1.9)),
        (t_take + 6, (here.x + 1.4, here.y - 5.2, 3.2), (here.x + 1.0, here.y + 0.3, 2.2)),
        (FRAMES, (here.x + 2.0, here.y - 8.0, 4.2), (here.x - 2.5, here.y - 3.0, 1.4)),
    ])
    print("PATH", len(path), "corners", len(corners), "arrive", t_arrive, "open", t_open, "take", t_take, "away", t_away)
    return FRAMES
