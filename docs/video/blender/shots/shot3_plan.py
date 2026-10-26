"""Shot 3 — the plan comes alive: a tall tree of the bot's plan (log → … → diamond); beside it, floating islands where
the bot carries out each step; finished items fly to their node and the next link lights up."""
import math
import random
import bpy
from mathutils import Vector, Euler
import kit

FRAMES = 600
# node: (item kind, height, island)
STEP = 4.6
NODES = [(k, i * STEP, isl) for i, (k, isl) in enumerate([("log", "A"), ("planks", "B"), ("pick_wood", "B"), ("cobble", "C"),
                                                          ("pick_stone", "C"), ("raw_iron", "D"), ("ingot", "E"),
                                                          ("pick_iron", "E"), ("diamond", "F")])]
# island: (centre x, z, stage start, stage end)
ISLANDS = {"A": (-8.5, -1.5, 40, 120), "B": (8.5, 5.4, 120, 200), "C": (-8.5, 14.6, 200, 290),
           "D": (8.5, 21.6, 290, 360), "E": (-8.5, 28.4, 360, 450), "F": (8.5, 35.4, 450, 565)}
# when each node is done (its item arrives)
DONE_AT = [112, 160, 196, 254, 292, 352, 414, 456, 548]


def node_pos(i):
    _, z, _ = NODES[i]
    return Vector((1.3 if i % 2 else -1.3, 0, z))


def yaw_to(src, dst):
    d = Vector(dst) - Vector(src)
    return math.degrees(math.atan2(d.x, -d.y))


def island(name, cx, cz, top="grass", r=3):
    """A floating voxel island: a disc of `top` with dirt and a tapered rocky underside."""
    blocks = {}
    for x in range(-r, r + 1):
        for y in range(-r, r + 1):
            d = math.hypot(x, y)
            if d > r + 0.4:
                continue
            depth = 1 + int((r + 0.5 - d) * 1.2)
            for k in range(depth + 1):
                blocks[(x, y, -k)] = top if k == 0 else ("dirt" if k < 2 and top == "grass" else "stone" if top != "deepslate" else "deepslate")
    ob = kit.block_mesh(name, blocks)
    ob.location = (cx, 0, cz)
    return ob, blocks


def block_obj(kind, loc, name=None):
    ob = kit.item(kind, name=name, size=1.0)
    ob.location = Vector(loc) + Vector((0.5, 0.5, 0.5))
    return ob


def fly(obj, src, dst, f0, f1, arc=2.0, spin=True):
    f1 = max(f1, f0 + 6)
    for k in range(0, f1 - f0 + 1, 2):
        t = k / (f1 - f0)
        e = t * t * (3 - 2 * t)
        p = Vector(src).lerp(Vector(dst), e) + Vector((0, 0, math.sin(t * math.pi) * arc))
        obj.location = p
        obj.keyframe_insert("location", frame=f0 + k)
        if spin:
            obj.rotation_euler = Euler((0, 0, t * math.pi * 3))
            obj.keyframe_insert("rotation_euler", frame=f0 + k)


def craft(center, kinds, f0, f1, result, result_size=0.9):
    """Ingredients orbit above `center`, spiral in, and the result pops out and hovers, spinning."""
    c = Vector(center)
    obs = []
    for i, k in enumerate(kinds):
        ob = kit.item(k, size=0.45)
        a0 = i * 2 * math.pi / len(kinds)
        ob.scale = (0, 0, 0)
        ob.keyframe_insert("scale", frame=f0 - 1)
        for f in range(f0, f1 + 1, 2):
            t = (f - f0) / (f1 - f0)
            r = 0.9 * (1 - t ** 1.6)
            a = a0 + t * 4 * math.pi
            ob.location = c + Vector((math.cos(a) * r, math.sin(a) * r, 0.6 + 0.4 * math.sin(t * math.pi)))
            ob.keyframe_insert("location", frame=f)
            s = min(1.0, (f - f0) / 6) * (1 - max(0, t - 0.85) / 0.15)
            ob.scale = (s, s, s)
            ob.keyframe_insert("scale", frame=f)
        obs.append(ob)
    res = kit.item(result, size=result_size)
    res.location = c + Vector((0, 0, 0.9))
    kit.pop_in(res, f1, dur=8, overshoot=1.3)
    for f in range(f1, f1 + 30, 3):
        res.rotation_euler = Euler((0, 0, (f - f1) * 0.18))
        res.keyframe_insert("rotation_euler", frame=f)
    kit.burst(c + Vector((0, 0, 0.9)), kit.hexc("#fff3c4"), f1, n=10, speed=2.2, life=14, size=0.06, seed=f1, gravity=4)
    return res


def build(preview=False):
    kit.reset(FRAMES, samples=24)
    kit.sky_grad(zenith="#2a5aa8", horizon="#a9cdf2", middle="#6f9fd8", ground="#5f8cc6", ambient=0.5, sun_elev=35,
                 sun_rot=-30, sun_energy=4.0, sun_color="#fff2dc")
    kit.bloom(threshold=0.8, size=7)
    rng = random.Random(4)

    ghost = kit.mat_flat("ghost", kit.CYAN, emission=1.2, alpha=0.28)
    ring_dim = kit.mat_flat("ring_dim", kit.hexc("#3a6b88"), emission=0.6)
    ring_done = kit.mat_flat("ring_done", kit.GREEN, emission=3.5)

    # ---- the plan tree
    for i, (kind, z, isl) in enumerate(NODES):
        p = node_pos(i)
        appear = 6 + i * 3
        g = kit.item(kind, name=f"ghost{i}", size=1.2)
        g.data = g.data.copy()
        g.data.materials.clear()
        g.data.materials.append(ghost)
        g.location = p
        kit.pop_in(g, appear, dur=8)
        kit.visible(g, [(1, True), (DONE_AT[i], False)])
        real = kit.item(kind, name=f"node{i}", size=1.2)
        real.location = p
        kit.visible(real, [(1, False), (DONE_AT[i], True)])
        kit.pop_in(real, DONE_AT[i], dur=8, overshoot=1.35)
        for f in range(DONE_AT[i], FRAMES + 1, 6):
            real.rotation_euler = Euler((0, 0, (f - DONE_AT[i]) * 0.035))
            real.keyframe_insert("rotation_euler", frame=f)
        # pedestal ring: dim until done, then green
        for mat, vis in ((ring_dim, [(1, True), (DONE_AT[i], False)]), (ring_done, [(1, False), (DONE_AT[i], True)])):
            bpy.ops.mesh.primitive_torus_add(major_radius=0.95, minor_radius=0.05, location=p + Vector((0, 0, -0.95)))
            ring = bpy.context.active_object
            ring.data.materials.append(mat)
            kit.visible(ring, vis)
            if mat is ring_done:
                kit.pop_in(ring, DONE_AT[i], dur=10, overshoot=1.4)
        # links: a dim guide, then a bright segment that draws itself when the node is done
        if i + 1 < len(NODES):
            a, b = p + Vector((0, 0, 0.75)), node_pos(i + 1) + Vector((0, 0, -0.95))
            mid = (a + b) / 2 + Vector((0, -0.4, 0))
            kit.ribbon(f"dim{i}", [a, mid, b], kit.hexc("#2c5a7a"), width=0.035, emission=0.8)
            kit.ribbon(f"lit{i}", [a, mid, b], kit.CYAN, width=0.06, emission=6.0, grow=(DONE_AT[i], DONE_AT[i] + 14))

    # ---- islands and the bot on each
    # A: chop a tree
    cx, cz, s0, s1 = ISLANDS["A"]
    island("isA", cx, cz)
    trunk = [block_obj("log", (cx - 0.5 + 1, -0.5, cz + 1 + k), f"trunk{k}") for k in range(4)]
    canopy = {}
    kit.tree(canopy, 0, 0, 0, 4)
    leaves = kit.block_mesh("canopyA", {p: k for p, k in canopy.items() if k == "leaves"})
    leaves.location = (cx + 1 - 0.5 + 0.5, 0, cz + 1)
    botA = kit.bot("BotA")
    standA = (cx - 1.0, -0.2, cz + 1)
    botA.place(1, standA, yaw_deg=yaw_to(standA, (cx + 1, 0, cz + 1)))
    kit.mine_swings(botA, s0 + 4, s1 - 20, period=10)
    for k, f in zip(range(3, -1, -1), (s0 + 22, s0 + 38, s0 + 54, s0 + 70)):
        kit.pop_out(trunk[k], f, dur=4)
        kit.burst(trunk[k].location, kit.hexc("#7a5b34"), f, n=10, speed=3, seed=100 + k)
    kit.pop_out(leaves, s0 + 74, dur=8)
    kit.burst(Vector((cx + 1, 0, cz + 6)), kit.hexc("#3b7f2b"), s0 + 74, n=18, speed=4, seed=110, size=0.18)
    logA = kit.item("log", size=0.6)
    fly(logA, (cx + 1, 0, cz + 2), node_pos(0), s1 - 30, DONE_AT[0], arc=2.5)
    kit.visible(logA, [(1, False), (s1 - 30, True), (DONE_AT[0], False)])

    # B: crafting table: planks, then a wooden pickaxe
    cx, cz, s0, s1 = ISLANDS["B"]
    island("isB", cx, cz)
    table = block_obj("table", (cx - 0.5, -0.5, cz + 1), "tableB")
    botB = kit.bot("BotB")
    standB = (cx + 1.6, -0.6, cz + 1)
    botB.place(1, standB, yaw_deg=yaw_to(standB, (cx, 0, cz + 1)))
    kit.mine_swings(botB, s0 + 6, s0 + 22, period=8)
    kit.mine_swings(botB, s0 + 42, s0 + 60, period=8)
    tc = Vector((cx, 0, cz + 2.0))
    planks = craft(tc, ["log"], s0 + 6, s0 + 22, "planks", result_size=0.55)
    fly(planks, tc + Vector((0, 0, 0.9)), node_pos(1), s0 + 24, DONE_AT[1], arc=2.0)
    kit.visible(planks, [(1, False), (s0 + 22, True), (DONE_AT[1], False)])
    pick = craft(tc, ["planks", "planks", "stick"], s0 + 40, s0 + 58, "pick_wood")
    fly(pick, tc + Vector((0, 0, 0.9)), node_pos(2), s0 + 60, DONE_AT[2], arc=2.0)
    kit.visible(pick, [(1, False), (s0 + 58, True), (DONE_AT[2], False)])

    # C: mine stone, then a stone pickaxe at a table
    cx, cz, s0, s1 = ISLANDS["C"]
    _, bl = island("isC", cx, cz)
    rocks = [block_obj("stone", (cx + 0.5 + dx, -0.5 + dy, cz + 1 + dz), f"rockC{i}") for i, (dx, dy, dz) in
             enumerate([(1, 0, 0), (1, 1, 0), (1, 0, 1)])]
    for dx, dy, dz in [(2, 0, 0), (2, 1, 0), (2, 0, 1), (1, -1, 0)]:
        block_obj("stone", (cx + 0.5 + dx, -0.5 + dy, cz + 1 + dz))
    tableC = block_obj("table", (cx - 2.5, -0.5, cz + 1), "tableC")
    botC = kit.bot("BotC")
    standC = (cx - 0.4, -0.3, cz + 1)
    botC.place(1, standC, yaw_deg=yaw_to(standC, (cx + 1.5, 0, cz + 1)))
    kit.mine_swings(botC, s0 + 4, s0 + 36, period=8)
    for k, f in enumerate((s0 + 12, s0 + 24, s0 + 36)):
        kit.pop_out(rocks[k], f, dur=4)
        kit.burst(rocks[k].location, kit.hexc("#8a8a8a"), f, n=10, speed=3, seed=200 + k)
    cob = kit.item("cobble", size=0.55)
    fly(cob, rocks[0].location, node_pos(3), s0 + 38, DONE_AT[3], arc=2.0)
    kit.visible(cob, [(1, False), (s0 + 38, True), (DONE_AT[3], False)])
    botC.place(s0 + 40, standC, yaw_deg=yaw_to(standC, (cx + 1.5, 0, cz + 1)))
    botC.place(s0 + 48, (cx - 1.2, -0.3, cz + 1), yaw_deg=yaw_to((cx - 1.2, -0.3, cz + 1), (cx - 2, 0, cz + 1)))
    kit.mine_swings(botC, s0 + 52, s0 + 74, period=8)
    tcC = Vector((cx - 2, 0, cz + 2.0))
    spick = craft(tcC, ["cobble", "cobble", "stick"], s0 + 52, s0 + 74, "pick_stone")
    fly(spick, tcC + Vector((0, 0, 0.9)), node_pos(4), s0 + 76, DONE_AT[4], arc=2.0)
    kit.visible(spick, [(1, False), (s0 + 74, True), (DONE_AT[4], False)])

    # D: mine iron ore out of a little cliff
    cx, cz, s0, s1 = ISLANDS["D"]
    island("isD", cx, cz)
    cliff = {}
    for dx in range(-1, 2):
        for dz in range(0, 3):
            if (dx, dz) != (0, 0):
                cliff[(dx, 2, dz + 1)] = "stone"
    cliff[(1, 2, 3)] = "coal_ore"
    kit.block_mesh("cliffD", cliff).location = (cx, 0, cz)
    ore = block_obj("iron_ore", (cx, 2 - 0.5 + 0.5 - 0.5, cz + 1), "oreD")
    ore.location = Vector((cx + 0.5, 2.5, cz + 1.5))
    botD = kit.bot("BotD")
    standD = (cx + 0.5, 0.2, cz + 1)
    botD.place(1, standD, yaw_deg=yaw_to(standD, (cx + 0.5, 2.5, cz + 1)))
    kit.mine_swings(botD, s0 + 6, s0 + 40, period=8)
    kit.pop_out(ore, s0 + 40, dur=4)
    kit.burst(ore.location, kit.hexc("#d8a476"), s0 + 40, n=14, speed=3, seed=300)
    raw = kit.item("raw_iron", size=0.6)
    fly(raw, ore.location, node_pos(5), s0 + 42, DONE_AT[5], arc=2.2)
    kit.visible(raw, [(1, False), (s0 + 42, True), (DONE_AT[5], False)])

    # E: a furnace smelts the iron; then the iron pickaxe
    cx, cz, s0, s1 = ISLANDS["E"]
    island("isE", cx, cz)
    furnace = block_obj("furnace", (cx + 0.5, -0.5, cz + 1), "furnaceE")
    lit = block_obj("furnace_lit", (cx + 0.5, -0.5, cz + 1), "furnaceLitE")
    kit.visible(furnace, [(1, True), (s0 + 8, False)])
    kit.visible(lit, [(1, False), (s0 + 8, True), (s0 + 40, False)])
    kit.visible(furnace, [(s0 + 40, True)])
    glow = kit.mat_flat("fireglow", kit.hexc("#ff8a2a"), emission=12)
    bpy.ops.mesh.primitive_plane_add(size=0.5, location=(cx + 1.0, -0.52, cz + 1.25), rotation=(math.radians(90), 0, 0))
    fire = bpy.context.active_object
    fire.data.materials.append(glow)
    kit.visible(fire, [(1, False), (s0 + 8, True), (s0 + 40, False)])
    tableE = block_obj("table", (cx - 2.0, -0.5, cz + 1), "tableE")
    botE = kit.bot("BotE")
    standE = (cx - 0.5, -1.4, cz + 1)
    botE.place(1, standE, yaw_deg=yaw_to(standE, (cx + 1, 0, cz + 1)))
    ingot = kit.item("ingot", size=0.6)
    ingot.location = Vector((cx + 1, -1.0, cz + 1.5))
    kit.visible(ingot, [(1, False), (s0 + 34, True), (DONE_AT[6], False)])
    kit.pop_in(ingot, s0 + 34, dur=6)
    fly(ingot, ingot.location.copy(), node_pos(6), s0 + 40, DONE_AT[6], arc=2.0)
    botE.place(s0 + 44, standE, yaw_deg=yaw_to(standE, (cx + 1, 0, cz + 1)))
    standE2 = (cx - 1.3, -1.2, cz + 1)
    botE.place(s0 + 52, standE2, yaw_deg=yaw_to(standE2, (cx - 1.5, 0, cz + 1)))
    kit.mine_swings(botE, s0 + 56, s0 + 78, period=8)
    tcE = Vector((cx - 1.5, 0, cz + 2.0))
    ipick = craft(tcE, ["ingot", "ingot", "stick"], s0 + 56, s0 + 78, "pick_iron")
    fly(ipick, tcE + Vector((0, 0, 0.9)), node_pos(7), s0 + 80, DONE_AT[7], arc=2.0)
    kit.visible(ipick, [(1, False), (s0 + 78, True), (DONE_AT[7], False)])

    # F: deep cave: diamonds
    cx, cz, s0, s1 = ISLANDS["F"]
    island("isF", cx, cz, top="deepslate", r=3)
    cave = {}
    for dx in range(-2, 3):
        for dz in range(1, 4):
            cave[(dx, 2, dz)] = "deepslate"
    for dx in (-2, 2):
        for dy in (0, 1):
            for dz in range(1, 4):
                cave[(dx, dy, dz)] = "deepslate"
    kit.block_mesh("caveF", cave).location = (cx, 0, cz)
    dia_glow = kit.mat_blocks("diaglow", emission=1.2)
    gems = []
    for i, (dx, dz) in enumerate([(-1, 1), (0, 1), (1, 2)]):
        ob = kit.item("deepslate_diamond", name=f"dia{i}", size=1.0)
        ob.data.materials.clear()
        ob.data.materials.append(dia_glow)
        ob.location = Vector((cx + dx + 0.5, 1.5, cz + dz + 0.5))
        gems.append(ob)
    lamp = bpy.data.lights.new("caveLight", "POINT")
    lamp.energy = 120
    lamp.color = (0.4, 0.95, 1.0)
    lo = bpy.data.objects.new("caveLight", lamp)
    kit.link(lo)
    lo.location = (cx, 0.2, cz + 2.5)
    botF = kit.bot("BotF")
    standF = (cx, -1.0, cz + 1)
    botF.place(1, standF, yaw_deg=yaw_to(standF, (cx, 1.5, cz + 1)))
    held = kit.item("pick_iron", size=0.55)
    held.parent = botF.pivots["arm_r"]
    held.location = (0.05, -0.15, -0.62)
    held.rotation_euler = Euler((math.radians(-90), math.radians(-30), 0))
    kit.mine_swings(botF, s0 + 6, s0 + 70, period=8)
    for k, f in enumerate((s0 + 26, s0 + 46, s0 + 66)):
        kit.pop_out(gems[k], f, dur=4)
        kit.burst(gems[k].location, kit.hexc("#4ff0e6"), f, n=16, speed=3.5, seed=400 + k, size=0.1)
    shower = []
    for k in range(5):
        d = kit.item("diamond", size=0.45)
        start = gems[k % 3].location + Vector((0, -0.3, 0))
        f0 = s0 + 68 + k * 3
        fly(d, start, node_pos(8) + Vector((rng.uniform(-0.3, 0.3), 0, rng.uniform(-0.3, 0.3))), f0, DONE_AT[8], arc=3.0)
        kit.visible(d, [(1, False), (f0, True), (DONE_AT[8], False)])
        shower.append(d)
    kit.burst(node_pos(8), kit.hexc("#4ff0e6"), DONE_AT[8], n=24, speed=4.5, seed=450, size=0.12, gravity=3)

    # ---- camera climbs the tree with the work
    cam, tgt = kit.camera(lens=30)
    keys = [(1, (-3, -19, 3.2), (-3.5, 0, 1.4))]
    for name in "ABCDEF":
        cx, cz, s0, s1 = ISLANDS[name]
        keys.append((s0 + 10, (cx * 0.32, -13.5, cz + 2.6), (cx * 0.52, 0, cz + 1.8)))
        keys.append((s1 - 6, (cx * 0.3, -13.0, cz + 3.2), (cx * 0.5, 0, cz + 2.2)))
    keys.append((FRAMES, (0, -64, 19), (0, 0, 17.5)))
    kit.cam_keys(cam, tgt, keys)
    return FRAMES
