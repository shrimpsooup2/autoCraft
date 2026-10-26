"""Shot 7 — building together. A cyan schematic of a cottage hovers over a meadow and the bot starts the floor alone.
Players join the server (chat lines); their bots beam in, switch on (red → green) and get linked to the leader. Roles
split as the team code does it: one clears the site, the others gather and drop materials in a depot chest, the
leader places. The build speeds up into a time-lapse, the ghost fills in, and a flag goes up."""
import math
import random
import bpy
from mathutils import Vector
import kit

FRAMES = 450
CENTER = Vector((3.5, 3.5, 0))
CHEST = (8, -2)                                   # the depot, next to the site
SOLO_END = 150                                    # until the team is working, the leader places slowly
BUILD_END = 402
JOINS = [("Alex", 52, (-6.5, -7.5), "#2a9d8f"), ("Sam", 74, (-10.5, 1.5), "#e07a2f"), ("Kai", 96, (14.5, -5.5), "#7b5ea7")]
BUMPS = [(1, 4), (4, 3), (5, 5), (2, 6)]           # grass mounds on the site that the digger clears


def cottage():
    """{(x, y, z): kind} — cobble floor, log-cornered plank walls with windows and a door, a brick gable roof."""
    s = {}
    for x in range(7):
        for y in range(7):
            s[(x, y, 1)] = "cobble"
    for z in (2, 3, 4):
        for x in range(7):
            for y in range(7):
                if x not in (0, 6) and y not in (0, 6):
                    continue
                if y == 0 and x == 3 and z in (2, 3):
                    continue                                        # the door
                corner = x in (0, 6) and y in (0, 6)
                kind = "log" if corner else "planks"
                if z == 3 and not corner and ((y in (0, 6) and x in (1, 5)) or (x in (0, 6) and y == 3) or (y == 6 and x == 3)):
                    kind = "glass"
                s[(x, y, z)] = kind
    for k in range(4):
        z = 5 + k
        for x in range(-1, 8):
            s[(x, k, z)] = "bricks"
            s[(x, 6 - k, z)] = "bricks"
        for y in range(k + 1, 6 - k):
            s[(0, y, z)] = "planks"
            s[(6, y, z)] = "planks"
    return s


def build_order(s):
    def key(p):
        x, y, z = p
        if z == 1:
            return (z, y, x)                                          # the floor goes in row by row
        if z <= 4:
            return (z, math.atan2(y + 0.5 - CENTER.y, x + 0.5 - CENTER.x))   # walls: round and round
        return (z, abs(y - 3) * -1, x)                              # roof: outer rows first, toward the ridge
    return sorted(s, key=key)


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


def stand_for(p):
    """Where the leader stands to place block p: the floor from the south, walls from outside, roof rows from the
    row below (the lowest from the ground — four blocks up is within reach)."""
    x, y, z = p
    b = Vector((x + 0.5, y + 0.5, 0))
    if z == 1:
        return Vector((b.x, b.y - 1.6, 1.0))
    if z <= 4:
        d = (b - CENTER)
        d.z = 0
        r = d.length
        out = CENTER + d.normalized() * (r + 1.7)
        return Vector((out.x, out.y, 1.0))
    k = z - 5
    south = b.y < 3.5
    if k == 0:
        return Vector((min(max(b.x, -0.5), 6.5), -1.6 if south else 8.6, 1.0))
    return Vector((min(max(b.x, -0.5), 6.5), (k - 0.5) if south else (7.5 - k), 5.0 + k))


def gem(rig, color, name):
    mat = kit.mat_flat(name, color, emission=7.0)
    bpy.ops.mesh.primitive_cube_add(size=kit.PX * 2.2)
    g = bpy.context.active_object
    g.data.materials.append(mat)
    g.parent = rig.pivots["head"]
    g.location = rig.light.location
    g.rotation_euler = rig.light.rotation_euler
    return g


def switch_on(rig, f, name):
    """The bot's light flips from red (idle) to green (running)."""
    g = gem(rig, kit.GREEN, name + "_on")
    kit.visible(g, [(1, False), (f, True)])
    kit.pop_in(g, f, dur=6, overshoot=1.7)
    kit.visible(rig.light, [(1, True), (f, False)])


def walk(rig, pts, f0, speed=4.3, carry=None):
    """kit.follow plus standing still right up to the first step."""
    kit.rest(rig, f0 - 2)
    return kit.follow(rig, [Vector(p) for p in pts], f0, speed=speed)


def beam_in(pos, f, color):
    bpy.ops.mesh.primitive_cylinder_add(radius=0.55, depth=6, location=(pos[0], pos[1], 4))
    col = bpy.context.active_object
    m = kit.mat_flat("beam", color, emission=6.0, alpha=0.5)
    col.data.materials.append(m)
    for fr, s in ((f - 1, 0.0), (f + 3, 1.0), (f + 14, 0.0)):
        col.scale = (s, s, 1)
        col.keyframe_insert("scale", frame=fr)
    kit.visible(col, [(1, False), (f - 1, True), (f + 15, False)])
    kit.burst(Vector((pos[0], pos[1], 1.2)), color, f + 2, n=14, speed=3.0, seed=f, size=0.08, gravity=6)


def build(preview=False):
    kit.reset(FRAMES, samples=24)
    kit.sky_grad(zenith="#3b7ad2", horizon="#d4e8ff", middle="#8db8ea", ground="#55703f", ambient=0.45, sun_elev=38,
                 sun_rot=-30, sun_energy=4.2, sun_color="#fff2dc")
    kit.bloom(threshold=0.9, size=6)
    rng = random.Random(77)

    # ---- the meadow: flat grass, a wood to the north, a stone outcrop to the east
    world = {}
    for x in range(-24, 31):
        for y in range(-22, 28):
            world[(x, y, 0)] = "grass"
            world[(x, y, -1)] = "dirt"
    for _ in range(26):
        tx, ty = rng.randint(-20, 26), rng.randint(13, 24)
        if all(abs(tx - a) + abs(ty - b) > 3 for (a, b, c), k in list(world.items()) if k == "log" and c == 1):
            kit.tree(world, tx, ty, 1, rng.randint(4, 6), rng)
    for x in range(13, 18):
        for y in range(4, 9):
            h = 3 - int(math.hypot(x - 15, y - 6.5))
            for z in range(1, max(1, h) + 1):
                world[(x, y, z)] = "stone"
    for bx, by in BUMPS:
        world[(bx, by, 1)] = "grass"
    bumps = {}
    for bx, by in BUMPS:
        del world[(bx, by, 1)]
        ob = kit.item("grass", name=f"bump{bx}{by}", size=1.0)
        ob.location = (bx + 0.5, by + 0.5, 1.5)
        bumps[(bx, by)] = ob
    kit.block_mesh("meadow", world)

    # ---- the schematic: a ghost for every block until the real one pops in
    plan = cottage()
    order = build_order(plan)
    n_solo = 24
    placed_at = {}
    for i, p in enumerate(order):
        if i < n_solo:
            placed_at[p] = 30 + i * 5
        else:
            placed_at[p] = SOLO_END + round((i - n_solo) * (BUILD_END - SOLO_END) / (len(order) - n_solo))
    # Litematica-style: each block of the schematic as a see-through, blue-tinted copy of the real block
    ghost = kit.mat_blocks("ghost", alpha=0.4, tint=kit.hexc("#5fc4ff"), emission=1.1)
    protos = {}
    for p in order:
        kind = plan[p]
        if kind not in protos:
            protos[kind] = kit.block_mesh(f"ghost_{kind}", {(0, 0, 0): kind}, material=ghost, origin=(0.5, 0.5, 0.5), ao=False)
            g = protos[kind]
        else:
            g = protos[kind].copy()
            g.animation_data_clear()   # a copy shares the original's action; each ghost needs its own timing
            kit.link(g)
        g.location = Vector(p) + Vector((0.5, 0.5, 0.5))
        g.scale = (0.98, 0.98, 0.98)
        kit.pop_in(g, 4 + p[2] * 2, dur=8, final=(0.98, 0.98, 0.98))
        kit.visible(g, [(1, True), (placed_at[p], False)])
    for p in order:
        ob = kit.item(plan[p], name=f"b{p}", size=1.0)
        ob.location = Vector(p) + Vector((0.5, 0.5, 0.5))
        kit.visible(ob, [(1, False), (placed_at[p], True)])
        kit.pop_in(ob, placed_at[p], dur=6, overshoot=1.22)

    # ---- the depot chest
    chest = kit.item("chest", size=0.875)
    chest.location = (CHEST[0] + 0.5, CHEST[1] + 0.5, 1 + 0.4375)
    depot_stand = {"Alex": Vector((CHEST[0] - 0.6, CHEST[1] + 0.5, 1)), "Sam": Vector((CHEST[0] + 1.6, CHEST[1] + 0.5, 1)),
                   "Kai": Vector((CHEST[0] + 0.5, CHEST[1] - 0.6, 1))}
    chest_top = Vector((CHEST[0] + 0.5, CHEST[1] + 0.5, 2.0))

    def bounce(f):
        for fr, s in ((f, 0.875), (f + 3, 0.95), (f + 7, 0.875)):
            chest.scale = (s / 0.875,) * 3
            chest.keyframe_insert("scale", frame=fr)

    # ---- the leader
    leader = kit.bot("Leader")
    switch_on(leader, 6, "Leader")
    track = []
    prev = None
    for t in range(24, BUILD_END + 4, 4):
        cur = [p for p in order if placed_at[p] <= t + 2]
        p = cur[-1] if cur else order[0]
        s = stand_for(p)
        yaw = unwrap(yaw_to(s, Vector(p) + Vector((0.5, 0.5, 0))), prev)
        prev = yaw
        leader.place(t, s, yaw_deg=yaw)
        track.append((t, s))
    kit.walk_cycle(leader, 24, BUILD_END, period=12, amp=22, arms=False)
    kit.mine_swings(leader, 28, BUILD_END, period=6)
    leader.key(BUILD_END + 8, arm_r=0)
    kit.rest(leader, BUILD_END + 8)
    end_spot = Vector((3.5, -2.5, 1.0))
    leader.place(BUILD_END + 6, track[-1][1], yaw_deg=prev)
    leader.place(BUILD_END + 30, end_spot, yaw_deg=unwrap(180, prev))   # steps back to look at it
    kit.walk_cycle(leader, BUILD_END + 6, BUILD_END + 30, period=14, amp=30)

    def leader_at(f):
        best = min(track, key=lambda e: abs(e[0] - f))
        return best[1]

    # ---- players join: chat lines, bots beam in, switch on, get linked to the leader
    chat_bg = kit.mat_flat("chat_bg", kit.hexc("#000000"), alpha=0.42)
    cam, tgt = kit.camera(lens=30)
    members = {}
    for i, (name, fj, pos, shirt) in enumerate(JOINS):
        line = kit.text(f"{name} joined the game", size=0.032, color=kit.hexc("#ffff55"), emission=1.2, font="consola.ttf",
                        align="LEFT", name=f"chat_{name}")
        line.parent = cam
        line.location = (-0.92, -0.43 + 0.05 * (len(JOINS) - 1 - i) + 0.05 * 0, -1.6)
        bpy.ops.mesh.primitive_plane_add(size=1)
        bg = bpy.context.active_object
        bg.data.materials.append(chat_bg)
        bg.parent = cam
        bg.scale = (0.5, 0.046, 1)
        bg.location = (-0.92 + 0.24, line.location.y, -1.601)
        for ob in (line, bg):
            kit.visible(ob, [(1, False), (fj, True), (250, False)])
        # older lines move up as new ones arrive
        for ob in (line, bg):
            y0 = -0.43
            ob.location.y = y0
            ob.keyframe_insert("location", frame=fj)
            for k, (_, fk, _, _) in enumerate(JOINS[i + 1:], start=1):
                ob.keyframe_insert("location", frame=fk - 1)
                ob.location.y = y0 + 0.05 * k
                ob.keyframe_insert("location", frame=fk + 2)
        rig = kit.humanoid(name, skin="#c99868", shirt=shirt, pants="#2a2f3a", shoes="#4a4a4a", hair="#3a2a1a", light=kit.RED)
        p0 = Vector((pos[0], pos[1], 1.0))
        rig.place(1, p0, yaw_deg=yaw_to(p0, CENTER))
        for _, mesh in rig.parts.values():
            kit.visible(mesh, [(1, False), (fj + 2, True)])
        kit.visible(rig.light, [(1, False), (fj + 2, True)])
        beam_in(pos, fj, kit.CYAN)
        switch_on(rig, fj + 18, name)
        kit.visible(rig.light, [(1, False), (fj + 2, True), (fj + 18, False)])   # red from arrival until it's switched on
        lp = leader_at(fj + 18) + Vector((0, 0, 2.0))
        mp = p0 + Vector((0, 0, 2.0))
        link = kit.ribbon(f"link_{name}", [lp, (lp + mp) / 2 + Vector((0, 0, 2.0)), mp], kit.CYAN, width=0.04, emission=6.0,
                          grow=(fj + 18, fj + 30))
        kit.visible(link, [(1, False), (fj + 18, True), (fj + 46, False)])
        members[name] = (rig, p0)

    # ---- roles. Alex digs the site clear, then gathers wood; Sam quarries stone; Kai chops wood.
    alex, a0 = members["Alex"]
    f = JOINS[0][1] + 26
    pos = a0
    for bx, by in BUMPS:
        spot = Vector((bx - 0.6, by + 0.5, 1.0))
        f = walk(alex, [pos, spot], f, speed=4.6)
        alex.place(f + 1, spot, yaw_deg=90)
        kit.mine_swings(alex, f + 2, f + 14, period=6)
        kit.pop_out(bumps[(bx, by)], f + 13, dur=3)
        kit.burst(bumps[(bx, by)].location, kit.hexc("#6aaa42"), f + 13, n=10, speed=3, seed=f, size=0.09)
        f += 16
        pos = spot

    def gather(rig, name, pos, f, src_stand, src_block, kind, until, carry_color):
        while f < until:
            f = walk(rig, [pos, src_stand], f, speed=4.6)
            rig.place(f + 1, src_stand, yaw_deg=yaw_to(src_stand, src_block))
            kit.mine_swings(rig, f + 2, f + 22, period=6)
            kit.burst(Vector(src_block), carry_color, f + 12, n=8, speed=2.5, seed=f, size=0.08)
            kit.burst(Vector(src_block), carry_color, f + 22, n=8, speed=2.5, seed=f + 1, size=0.08)
            f += 24
            icon = kit.item(kind, size=0.42)
            icon.parent = rig.root
            icon.location = (0, 0, 2.3)
            f_back = walk(rig, [src_stand, depot_stand[name]], f, speed=4.6)
            kit.visible(icon, [(1, False), (f, True), (f_back, False)])
            for fr in range(f, f_back + 1, 4):
                icon.rotation_euler = (0, 0, (fr - f) * 0.15)
                icon.keyframe_insert("rotation_euler", frame=fr)
            drop = kit.item(kind, size=0.42)
            start = depot_stand[name] + Vector((0, 0, 2.3))
            drop.location = start
            drop.keyframe_insert("location", frame=f_back)
            drop.location = chest_top
            drop.keyframe_insert("location", frame=f_back + 7)
            kit.visible(drop, [(1, False), (f_back, True), (f_back + 8, False)])
            bounce(f_back + 7)
            rig.place(f_back + 2, depot_stand[name], yaw_deg=yaw_to(depot_stand[name], chest_top))
            f = f_back + 8
            pos = depot_stand[name]
        return f

    gather(alex, "Alex", pos, f, Vector((-4.5, 11.0, 1)), (-4.5, 12.5, 2.5), "log", BUILD_END - 40, kit.hexc("#7a5b34"))
    sam, s0 = members["Sam"]
    gather(sam, "Sam", s0, JOINS[1][1] + 26, Vector((12.4, 6.5, 1)), (13.5, 6.5, 1.5), "cobble", BUILD_END - 40,
           kit.hexc("#8a8a8a"))
    kai, k0 = members["Kai"]
    gather(kai, "Kai", k0, JOINS[2][1] + 26, Vector((6.5, 11.5, 1)), (6.5, 13.0, 2.5), "log", BUILD_END - 40,
           kit.hexc("#7a5b34"))
    # the leader takes what it needs from the depot
    for k, fr in enumerate((176, 236, 296, 356)):
        it = kit.item(("planks", "cobble", "glass", "bricks")[k], size=0.42)
        it.location = chest_top
        it.keyframe_insert("location", frame=fr)
        it.location = leader_at(fr + 10) + Vector((0, 0, 1.4))
        it.keyframe_insert("location", frame=fr + 10)
        kit.visible(it, [(1, False), (fr, True), (fr + 11, False)])
        bounce(fr)

    # ---- done: a flag on the ridge
    pole_base = Vector((3.5, 3.5, 9.0))
    bpy.ops.mesh.primitive_cube_add(size=1, location=pole_base + Vector((0, 0, 0.9)))
    pole = bpy.context.active_object
    pole.scale = (0.08, 0.08, 1.8)
    pole.data.materials.append(kit.mat_flat("pole", kit.hexc("#6b4a23")))
    bpy.ops.mesh.primitive_plane_add(size=1, location=(0, 0, 0))
    cloth = bpy.context.active_object
    cloth.data.materials.append(kit.mat_flat("flag", kit.RED, emission=0.4))
    hinge = kit.empty("flag_hinge", pole_base + Vector((0.04, 0, 1.55)))
    cloth.parent = hinge
    cloth.location = (0.45, 0, 0)
    cloth.rotation_euler = (math.radians(90), 0, 0)
    cloth.scale = (0.9, 0.55, 1)
    for ob in (pole, hinge):
        kit.pop_in(ob, BUILD_END + 6, dur=10, overshoot=1.3)
    for fr in range(BUILD_END + 6, FRAMES + 1, 6):
        hinge.rotation_euler = (0, 0, math.radians(18 * math.sin(fr * 0.25)))
        hinge.keyframe_insert("rotation_euler", frame=fr)
    kit.burst(pole_base + Vector((0, 0, 1.4)), kit.hexc("#fff3c4"), BUILD_END + 8, n=18, speed=3.5, seed=5, size=0.08,
              gravity=5)

    # ---- camera: a slow orbit of the site
    kit.cam_keys(cam, tgt, [
        (1, (-13, -19, 10), (3, 3, 2.5)),
        (150, (-9, -20, 9.5), (3, 2.5, 2.5)),
        (300, (5, -21, 9.5), (4, 2.5, 3.0)),
        (BUILD_END, (17, -16, 10.5), (3.5, 3, 4.8)),
        (FRAMES, (15.5, -14, 9.5), (3.5, 3, 5.2)),
    ])
    print("BLOCKS", len(order), "solo", n_solo)
    return FRAMES
