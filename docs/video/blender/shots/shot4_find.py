"""Shot 4 — find: a cutaway hill; x-ray scan lights up the diamond ores, the candidates get brackets, an A* search
spreads and resolves into a path to the chosen one, and the bot walks, swims and digs its way there."""
import heapq
import math
import random
import bpy
from mathutils import Vector
import kit

FRAMES = 465
X0, X1, DEPTH = -15, 15, 7          # diorama x range, y depth (front face at y = 0)
WATER_TOP = 2
XRAY_ON, XRAY_OFF = 62, 246
PATH_AT = 212                       # the search resolves into the path
WALK_START = 238


def profile(x):
    """Surface height along x: a hill on the left, a pond, a stone ridge on the right."""
    if x <= -8:
        return 7
    if x <= -4:
        return 7 - (x + 8)          # slope down to 3
    if x <= 0:
        return 0                    # pond bed (water up to WATER_TOP)
    if x <= 2:
        return 3
    if x <= 10:
        return 8                    # the ridge
    return 6


def solid_kind(x, y, z, top):
    if z == top:
        return "stone" if 3 <= x <= 10 else ("sand" if -1 <= x <= 0 else "grass")
    if z >= top - 2 and not (3 <= x <= 10):
        return "dirt"
    return "deepslate" if z < -4 else "stone"


def astar(start, goal, solid, water, greed=1.6):
    """Weighted A* in the front slice (x, z): walk, step up/down, swim, dig. Returns (path, explored order)."""
    def stand(x, z):
        return (x, z - 1) in solid or (x, z) in water

    def h(p):
        return abs(p[0] - goal[0]) + abs(p[1] - goal[1])
    openq = [(h(start), 0, start)]
    g = {start: 0}
    parent = {}
    seen = []
    closed = set()
    while openq:
        _, cost, cur = heapq.heappop(openq)
        if cur in closed:
            continue
        closed.add(cur)
        seen.append(cur)
        if cur == goal:
            break
        x, z = cur
        moves = [(dx, dz) for dx in (-1, 1) for dz in (-1, 0, 1)]
        moves += [(dx, -k) for dx in (-1, 1) for k in (2, 3)]   # drops
        for dx, dz in moves:
            nx, nz = x + dx, z + dz
            if not (X0 <= nx <= X1 and -6 <= nz <= 12):
                continue
            if dz < -1:   # a drop: the column it falls down must be open, and it must land on something
                if any((nx, zz) in solid for zz in range(nz, z + 2)) or not stand(nx, nz):
                    continue
                c = 1.0 + 0.3 * -dz
            else:
                c = 1.0 + 0.5 * abs(dz)
                dig = sum(1 for b in ((nx, nz), (nx, nz + 1)) if b in solid)
                c += dig * 3.5
                if not stand(nx, nz) and dig == 0:
                    continue
                if dz == 1 and (x, z + 2) in solid:
                    c += 3.5
            if (nx, nz) in water:
                c += 1.0
            n = (nx, nz)
            if cost + c < g.get(n, 1e9):
                g[n] = cost + c
                parent[n] = cur
                heapq.heappush(openq, (cost + c + greed * h(n), cost + c, n))
    path = [goal]
    while path[-1] in parent:
        path.append(parent[path[-1]])
    return path[::-1], seen


def bracket(loc, color, size=1.25, name="bracket", emission=5.0):
    """A square frame facing the camera (-Y) around a block."""
    mat = kit.mat_flat(name + "_m", color, emission=emission)
    parts = []
    s, t = size / 2, 0.06
    for (cx, cz, w, h) in ((0, s, size, t), (0, -s, size, t), (s, 0, t, size), (-s, 0, t, size)):
        bpy.ops.mesh.primitive_cube_add(size=1, location=(cx, 0, cz))
        b = bpy.context.active_object
        b.scale = (w, t, h)
        b.data.materials.append(mat)
        parts.append(b)
    root = kit.empty(name, loc)
    for b in parts:
        b.parent = root
    return root


def build(preview=False):
    kit.reset(FRAMES, samples=24)
    kit.sky_grad(zenith="#3a78cc", horizon="#d6e9ff", middle="#94bcea", ground="#6b8fb8", ambient=0.45, sun_elev=38,
                 sun_rot=-40, sun_energy=4.0)
    kit.bloom(threshold=0.85, size=7)
    rng = random.Random(9)

    solid, water, world, water_blocks = {}, set(), {}, {}
    for x in range(X0, X1 + 1):
        top = profile(x)
        for y in range(0, DEPTH):
            for z in range(-6, top + 1):
                world[(x, y, z)] = solid_kind(x, y, z, top)
            for z in range(top + 1, WATER_TOP + 1):
                water_blocks[(x, y, z)] = "water"
        for z in range(-6, top + 1):
            solid[(x, z)] = True
        for z in range(top + 1, WATER_TOP + 1):
            water.add((x, z))
    # ordinary ores speckle the cut face so it reads as rock, not a wall
    for _ in range(26):
        x, y, z = rng.randint(X0, X1), rng.randint(0, DEPTH - 1), rng.randint(-5, 5)
        if z < profile(x) - 1:
            world[(x, y, z)] = rng.choice(["coal_ore", "coal_ore", "iron_ore"])
    # trees behind the front slice: they never touch the path
    for tx, ty in ((-13, 3), (-10, 5), (-14, 6), (13, 4), (12, 2)):
        kit.tree(world, tx, ty, profile(tx) + 1, 5 if tx < 0 else 4)

    # diamond ores; the target sits in the front slice inside the ridge
    target = (8, 0, 3)
    ores = [target] + [(rng.randint(-13, 13), rng.randint(0, DEPTH - 1), rng.randint(-5, 2)) for _ in range(13)]
    ores = [(x, y, min(z, profile(x) - 2)) for x, y, z in ores]

    start = (-11, profile(-11) + 1)
    path, seen = astar(start, (target[0] - 1, target[2]), solid, water)
    dig_cells = []
    for x, z in path:
        for b in ((x, z), (x, z + 1)):
            if b in solid and b not in dig_cells:
                dig_cells.append(b)

    for x, y, z in ores:
        world.pop((x, y, z), None)
    for x, z in dig_cells:
        world.pop((x, 0, z), None)
    normal = kit.block_mesh("hill", world)
    xmat = kit.mat_blocks("xray", alpha=0.13, tint=kit.hexc("#5f9ae0"), emission=0.35)
    xray = kit.block_mesh("hill_x", {p: k for p, k in world.items() if k not in ("log", "leaves")}, material=xmat, ao=False)
    kit.block_mesh("pond", water_blocks, material=kit.water_material(), cull_against=world)
    kit.visible(normal, [(1, True), (XRAY_ON, False), (XRAY_OFF, True)])
    kit.visible(xray, [(1, False), (XRAY_ON, True), (XRAY_OFF, False)])
    digs = {}
    for x, z in dig_cells:
        ob = kit.item(solid_kind(x, 0, z, profile(x)), name=f"dig{x}_{z}", size=1.0)
        ob.location = (x + 0.5, 0.5, z + 0.5)
        digs[(x, z)] = ob

    # x-ray vision: the world behind darkens while the scan runs
    bpy.ops.mesh.primitive_plane_add(size=220, location=(0, 18, 0), rotation=(math.radians(90), 0, 0))
    backdrop = bpy.context.active_object
    backdrop.visible_shadow = False
    dark = kit.mat_flat("backdrop", kit.hexc("#081428"), emission=1.0, alpha=0.0)
    backdrop.data.materials.append(dark)
    kit.animate_input(dark, "Alpha", [(1, 0.0), (XRAY_ON, 0.0), (XRAY_ON + 10, 0.94), (XRAY_OFF - 6, 0.94), (XRAY_OFF + 4, 0.0)])

    glow = kit.mat_blocks("oreglow", emission=0.001, tint=kit.hexc("#a8f4ff"))
    kit.animate_input(glow, "Emission Strength", [(1, 0.0), (XRAY_ON + 2, 0.0), (XRAY_ON + 10, 3.0), (XRAY_OFF - 6, 3.0),
                                                  (XRAY_OFF + 4, 0.25)])
    ore_obs = []
    for i, (x, y, z) in enumerate(ores):
        ob = kit.item("diamond_ore", name=f"ore{i}", size=1.0)
        ob.data.materials.clear()
        ob.data.materials.append(glow)
        ob.location = (x + 0.5, y + 0.5, z + 0.5)
        ore_obs.append(ob)

    # the bot on the hilltop looks around
    bot = kit.bot()
    bot.light.data.materials.clear()
    bot.light.data.materials.append(kit.mat_flat("lightg", kit.GREEN, emission=7))
    sx, sz = start
    bot.place(1, (sx + 0.5, 0.5, sz), yaw_deg=90)
    bot.key(10, head=(0, 0, 25))
    bot.key(28, head=(0, 0, -25))
    bot.key(42, head=(0, 0, 0))

    # scan: a pulse ring from the bot, then square chunk rings sweeping outward
    bpy.ops.mesh.primitive_torus_add(major_radius=1.0, minor_radius=0.04, location=(sx + 0.5, -0.2, sz + 1.0),
                                     rotation=(math.radians(90), 0, 0))
    pulse = bpy.context.active_object
    pulse.data.materials.append(kit.mat_flat("pulse", kit.CYAN, emission=8))
    for f, s in ((44, 0.01), (68, 28.0)):
        pulse.scale = (s, s, s)
        pulse.keyframe_insert("scale", frame=f)
    kit.visible(pulse, [(1, False), (44, True), (70, False)])
    for k, (f0, half) in enumerate(((76, 3), (92, 6), (108, 9))):
        cx, cz = sx + 0.5, sz - 2
        pts = [(cx - half, -0.15, cz - half), (cx + half, -0.15, cz - half), (cx + half, -0.15, cz + half),
               (cx - half, -0.15, cz + half), (cx - half, -0.15, cz - half)]
        r = kit.ribbon(f"ring{k}", pts, kit.CYAN, width=0.04, emission=5.0, grow=(f0, f0 + 12), smooth=False)
        kit.visible(r, [(1, False), (f0, True), (f0 + 30, False)])

    # candidates near the front get pale brackets; when the path resolves, the chosen ore's turns amber
    bp = Vector((sx + 0.5, 0.5, sz + 0.5))
    cands = sorted((i for i in range(len(ores)) if ores[i][1] <= 2), key=lambda i: (Vector(ore_obs[i].location) - bp).length)
    for rank, i in enumerate(cands):
        loc = Vector(ore_obs[i].location) + Vector((0, -0.7 - ores[i][1], 0))
        f0 = 122 + rank * 6
        br = bracket(loc, kit.hexc("#cfefff"), name=f"br{rank}", emission=3.0)
        kit.pop_in(br, f0, dur=8, overshoot=1.3)
        for ob in [br, *br.children]:   # an empty's visibility doesn't reach its children
            kit.visible(ob, [(1, False), (f0, True), (PATH_AT if i == 0 else XRAY_OFF, False)])
        if i == 0:
            chosen = bracket(loc, kit.AMBER, size=1.35, name="chosen", emission=6.0)
            kit.pop_in(chosen, PATH_AT, dur=10, overshoot=1.5)
            for ob in [chosen, *chosen.children]:
                kit.visible(ob, [(1, False), (PATH_AT, True)])

    # A* exploration spreading through the slice, then the path
    dot_mat = kit.mat_flat("explored", kit.hexc("#7fd8ff"), emission=3.0)
    shown = seen[:170]
    for k, (x, z) in enumerate(shown):
        bpy.ops.mesh.primitive_cube_add(size=0.16, location=(x + 0.5, -0.2, z + 0.5))
        d = bpy.context.active_object
        d.data.materials.append(dot_mat)
        f0 = 166 + int(44 * k / max(1, len(shown)))
        kit.pop_in(d, f0, dur=5, overshoot=1.6)
        kit.visible(d, [(1, False), (f0, True), (XRAY_OFF, False)])
    ppts = [(x + 0.5, -0.25, z + 0.35) for x, z in path]
    rib = kit.ribbon("path", ppts, kit.AMBER, width=0.09, emission=7.0, grow=(PATH_AT, PATH_AT + 24), smooth=False)
    kit.visible(rib, [(1, False), (PATH_AT, True), (440, False)])

    # the bot follows the path: walks, swims, digs (one swing per block of its two-tall body)
    def body(x, z):
        return Vector((x + 0.5, 0.5, z - 0.45 if (x, z) in water else z))
    f = WALK_START
    track = [(f, body(*path[0]))]
    for (x0, z0), (x1, z1) in zip(path, path[1:]):
        yaw = 90 if x1 > x0 else -90
        cells = [b for b in ((x1, z1 + 1), (x1, z1)) if b in digs]
        if cells:
            bot.place(f, body(x0, z0), yaw_deg=yaw)
            kit.mine_swings(bot, f, f + 5 * len(cells), period=5)
            for k, b in enumerate(cells):
                fb = f + 4 + 5 * k
                kit.pop_out(digs[b], fb, dur=3)
                kit.burst(Vector(digs[b].location), kit.hexc("#8a8a8a"), fb, n=8, speed=2.5, seed=fb)
            f += 5 * len(cells) + 1
        swim = (x1, z1) in water
        dur = 7 if swim else 4
        bot.place(f, body(x0, z0), yaw_deg=yaw)
        bot.place(f + dur, body(x1, z1), yaw_deg=yaw)
        bot.key(f, leg_l=30, leg_r=-30, arm_l=-25, arm_r=25)
        bot.key(f + dur // 2, leg_l=-30, leg_r=30, arm_l=25, arm_r=-25)
        f += dur
        track.append((f, body(x1, z1)))
    kit.rest(bot, f + 2)
    # mine the ore; three diamonds hop out
    kit.mine_swings(bot, f + 2, f + 20, period=6)
    t = ore_obs[0]
    kit.pop_out(t, f + 18, dur=4)
    kit.burst(Vector(t.location), kit.hexc("#4ff0e6"), f + 18, n=20, speed=3.5, seed=999, size=0.1)
    shine = kit.mat_flat("dmd_glow", kit.hexc("#5ff3ea"), emission=2.5, rough=0.2)
    for k in range(3):
        dmd = kit.item("diamond", size=0.6)
        dmd.data.materials.clear()
        dmd.data.materials.append(shine)
        f0 = f + 20 + k * 3
        home = Vector(t.location) + Vector(((k - 1) * 0.55, -1.1, 0.25 + 0.15 * (k == 1)))
        dmd.location = Vector(t.location) + Vector((0, -0.4, 0))
        dmd.keyframe_insert("location", frame=f0)
        kit.pop_in(dmd, f0, dur=6, overshoot=1.4)
        for fr in range(f0 + 8, FRAMES + 1, 4):   # hover, bob and spin
            dmd.location = home + Vector((0, 0, 0.08 * math.sin((fr - f0) * 0.2 + k)))
            dmd.keyframe_insert("location", frame=fr)
            dmd.rotation_euler = (0, 0, (fr - f0) * 0.12)
            dmd.keyframe_insert("rotation_euler", frame=fr)
    end = f + 40

    # camera: the whole diorama for the scan and search, then it follows the bot in, ending close at the ore
    cam, tgt = kit.camera(lens=28)
    keys = [(1, (-4, -27, 9), (-3, 2, 3)), (150, (-1.5, -25.5, 8), (-1, 2, 2.5)), (WALK_START, (-1, -24.5, 7.5), (-0.5, 2, 2.5))]
    for fr, p in track:
        if fr - keys[-1][0] >= 36 and fr < f - 16:
            keys.append((fr, (p.x + 1.5, -10.5, p.z + 2.6), (p.x + 2.0, 1, p.z + 0.8)))
    tl = Vector(t.location)
    keys.append((f + 10, (tl.x - 2.0, -8.0, tl.z + 2.2), (tl.x - 0.8, 1, tl.z + 0.2)))
    keys.append((FRAMES, (tl.x - 1.6, -7.0, tl.z + 2.0), (tl.x - 0.7, 1, tl.z + 0.2)))
    kit.cam_keys(cam, tgt, keys)
    print("PATH", len(path), "explored", len(seen), "digs", len(dig_cells), "ends at", end)
    return FRAMES
