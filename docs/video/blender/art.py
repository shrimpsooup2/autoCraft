"""
Pure-Python pixel art shared by the Blender scenes (kit.py) and the 2D UI frames (ui_frames.py): block textures and
item sprites, all generated in code (original art, not Mojang's textures).
"""
import math
import zlib
import numpy as np


def hexc(h, a=1.0):
    h = h.lstrip("#")
    return (int(h[0:2], 16) / 255, int(h[2:4], 16) / 255, int(h[4:6], 16) / 255, a)



TILE = 16
TILES = ["grass_top", "grass_side", "dirt", "stone", "cobble", "deepslate", "log_side", "log_top", "planks", "leaves",
         "sand", "water", "iron_ore", "coal_ore", "diamond_ore", "glass", "bricks", "table_top", "table_side",
         "furnace_front", "furnace_lit", "furnace_side", "chest", "wool_red", "farmland", "wheat", "gravel",
         "deepslate_diamond", "snow", "path", "deepslate_bricks", "deepslate_tiles", "sculk", "sensor_top", "sensor_side",
         "reinforced", "shrieker_top", "shrieker_side", "chest_inner"]
ATLAS_W = 8
ATLAS_H = (len(TILES) + ATLAS_W - 1) // ATLAS_W


def _pal(rng, colors, shape, weights=None):
    idx = rng.choice(len(colors), size=shape, p=weights)
    return np.array([hexc(c) for c in colors])[idx]


def tile_pixels(name):
    rng = np.random.default_rng(zlib.crc32(name.encode()))  # stable across runs: every shot gets the same textures
    S = TILE
    t = np.zeros((S, S, 4))
    stone = ["#7d7d7d", "#8a8a8a", "#6f6f6f", "#949494", "#777777"]
    dirt = ["#79553a", "#8b6446", "#6a4a31", "#9a7050"]
    if name == "grass_top":
        t = _pal(rng, ["#64a33e", "#6aaa42", "#5e9c3b", "#70b047", "#5a9638"], (S, S))
    elif name in ("grass_side",):
        t = _pal(rng, dirt, (S, S))
        g = _pal(rng, ["#5e9e3a", "#6cb043", "#548f33"], (S, S))
        for x in range(S):
            h = 3 + rng.integers(0, 3)
            t[:h, x] = g[:h, x]
    elif name == "dirt":
        t = _pal(rng, dirt, (S, S))
    elif name == "path":
        t = _pal(rng, ["#9a7d4b", "#8c703f", "#a88a55"], (S, S))
    elif name == "stone":
        t = _pal(rng, stone, (S, S), [0.3, 0.25, 0.2, 0.1, 0.15])
    elif name == "gravel":
        t = _pal(rng, ["#837e7c", "#6d6866", "#9a9492", "#5c5755"], (S, S))
    elif name == "cobble":
        t = _pal(rng, ["#8c8c8c", "#a0a0a0", "#7a7a7a"], (S, S))
        for _ in range(9):  # dark mortar outlines around blobby stones
            cx, cy = rng.integers(0, S, 2)
            for y in range(S):
                for x in range(S):
                    d = abs(((x - cx + 8) % S) - 8) + abs(((y - cy + 8) % S) - 8)
                    if d == 4:
                        t[y, x] = hexc("#4e4e4e")
    elif name in ("deepslate", "deepslate_diamond"):
        t = _pal(rng, ["#4d4d55", "#57575f", "#45454c", "#5f5f66"], (S, S))
        for y in range(0, S, 4):
            t[y, rng.integers(0, S, 6)] = hexc("#3a3a40")
    elif name == "log_side":
        t = _pal(rng, ["#6b4f2c", "#5c4226", "#7a5c35"], (S, S))
        for x in range(0, S, 3):
            t[:, x] = hexc("#4a3519")
    elif name == "log_top":
        t[:] = hexc("#6b4f2c")
        for y in range(S):
            for x in range(S):
                r = math.hypot(x - 7.5, y - 7.5)
                if r < 7:
                    t[y, x] = hexc("#b18c56") if int(r) % 2 else hexc("#9c7845")
    elif name in ("planks", "table_side"):
        t = _pal(rng, ["#b48b55", "#a57d49", "#c19861"], (S, S))
        for y in (3, 7, 11, 15):
            t[y] = hexc("#7d5c33")
        for y0 in (0, 4, 8, 12):
            x = rng.integers(0, S)
            t[y0:y0 + 3, x] = hexc("#7d5c33")
        if name == "table_side":
            t[5:10, 3] = hexc("#7a7a7a"); t[5, 2:5] = hexc("#9a9a9a")      # a saw and a hammer hanging
            t[6:11, 11] = hexc("#5c4226"); t[6:8, 10:13] = hexc("#8a8a8a")
    elif name == "chest":
        # orange oak boards with a dark grey-brown rim; the latch is a separate part on the front of the lid
        t = _pal(rng, ["#b4782f", "#a86f2b", "#bf8436", "#ad732d"], (S, S))
        for y in range(2, S - 2):
            if rng.random() < 0.45:                      # long grain streaks, darker and lighter
                x0 = rng.integers(1, 8)
                t[y, x0:x0 + rng.integers(4, 9)] = hexc("#8d5a22" if rng.random() < 0.6 else "#c98f3e")
        t[0, :] = t[-1, :] = t[:, 0] = t[:, -1] = hexc("#433422")
    elif name == "chest_inner":
        t = _pal(rng, ["#3a2410", "#33200e", "#402812"], (S, S))
        t[0, :] = t[-1, :] = t[:, 0] = t[:, -1] = hexc("#433422")
    elif name == "table_top":
        t = _pal(rng, ["#b48b55", "#a57d49", "#c19861"], (S, S))
        t[[0, 5, 10, 15], :] = hexc("#6b4a24"); t[:, [0, 5, 10, 15]] = hexc("#6b4a24")
    elif name == "leaves":
        t = _pal(rng, ["#2f6b22", "#3b7f2b", "#25561b", "#47913a", "#1d4515"], (S, S), [0.3, 0.25, 0.2, 0.15, 0.1])
    elif name == "sand":
        t = _pal(rng, ["#dccf9a", "#e6d9a6", "#cfc28c", "#d6c994"], (S, S))
    elif name == "snow":
        t = _pal(rng, ["#f4f8fb", "#e8eef4", "#ffffff"], (S, S))
    elif name == "water":
        t = _pal(rng, ["#2c63b0", "#3672c2", "#2958a3", "#3b7acb"], (S, S))
    elif name in ("iron_ore", "coal_ore", "diamond_ore"):
        t = _pal(rng, stone, (S, S), [0.3, 0.25, 0.2, 0.1, 0.15])
        c = {"iron_ore": ["#d8a476", "#c08a5c", "#e8c09a"], "coal_ore": ["#1d1d1d", "#2b2b2b", "#111111"],
             "diamond_ore": ["#4ff0e6", "#2fc9c0", "#b8fffa"]}[name]
        for _ in range(5):
            cx, cy = rng.integers(2, S - 2, 2)
            for dx, dy in ((0, 0), (1, 0), (0, 1), (-1, 0), (1, 1)):
                if rng.random() < 0.85:
                    t[cy + dy, cx + dx] = hexc(c[rng.integers(0, 3)])
    elif name == "glass":
        t[:] = (0.75, 0.9, 1.0, 1.0)
        t[0, :] = t[-1, :] = t[:, 0] = t[:, -1] = hexc("#ffffff")
        for i in range(3, 7):
            t[i, i - 1] = hexc("#ffffff")
    elif name == "bricks":
        t[:] = hexc("#9c4a3a")
        for y in range(S):
            if y % 4 == 3:
                t[y, :] = hexc("#cdbfae")
            else:
                off = 0 if (y // 4) % 2 == 0 else 4
                t[y, (np.arange(S) + off) % 8 == 0] = hexc("#cdbfae")
        t[:, :, :3] *= rng.uniform(0.92, 1.05, (S, S, 1))
    elif name in ("furnace_front", "furnace_lit", "furnace_side"):
        t = _pal(rng, ["#8c8c8c", "#7a7a7a", "#6d6d6d"], (S, S))
        t[0, :] = t[-1, :] = t[:, 0] = t[:, -1] = hexc("#5a5a5a")
        if name != "furnace_side":
            t[8:13, 4:12] = hexc("#2a2a2a")
            if name == "furnace_lit":
                t[10:13, 5:11] = hexc("#ff9a2e"); t[11:13, 6:10] = hexc("#ffd36a")
    elif name == "wool_red":
        t = _pal(rng, ["#b0302a", "#a12924", "#c0372f"], (S, S))
    elif name == "farmland":
        t = _pal(rng, ["#5a3b22", "#4b311c", "#6a4528"], (S, S))
        t[::3, :] = hexc("#3c2715")
    elif name == "wheat":
        t = _pal(rng, ["#c9a93a", "#b8962c", "#dcbc4c", "#7f9a2a"], (S, S))
    elif name == "deepslate_bricks":
        t = _pal(rng, ["#4a4a52", "#52525a", "#44444b", "#57575f"], (S, S))
        for y in range(S):
            if y % 4 == 3:
                t[y, :] = hexc("#2c2c32")
            else:
                off = 0 if (y // 4) % 2 == 0 else 4
                t[y, (np.arange(S) + off) % 8 == 7] = hexc("#2c2c32")
    elif name == "deepslate_tiles":
        t = _pal(rng, ["#3d3d44", "#43434a", "#38383f"], (S, S))
        for (y0, x0), k in zip(((0, 0), (0, 8), (8, 0), (8, 8)), rng.uniform(0.85, 1.1, 4)):
            t[y0:y0 + 8, x0:x0 + 8, :3] *= k
        t[[7, 15], :] = hexc("#25252a")
        t[:, [7, 15]] = hexc("#25252a")
    elif name in ("sculk", "shrieker_side"):
        t = _pal(rng, ["#0b1d24", "#0e2830", "#081419", "#123139"], (S, S))
        for _ in range({"sculk": 9, "shrieker_side": 4}[name]):
            x, y = rng.integers(1, S - 1, 2)
            t[y, x] = hexc("#2fd3d6" if rng.random() < 0.7 else "#9ffaf6")
        if name == "shrieker_side":
            t[:5] = _pal(rng, ["#d8d1b8", "#cbc3a8", "#e4ddc6"], (5, S))
            t[5, ::2] = hexc("#b8af93")
    elif name == "sensor_top":
        # teal, mottled, with a few cyan and pale grey flecks
        t = _pal(rng, ["#0b4a57", "#0d5262", "#0a4250", "#0f5868", "#083a46"], (S, S), [0.3, 0.25, 0.2, 0.15, 0.1])
        for _ in range(7):
            x, y = rng.integers(1, S - 1, 2)
            t[y, x] = hexc("#139aa4" if rng.random() < 0.6 else "#8a9a9e")
    elif name == "sensor_side":
        # the half-slab's side (drawn for the full tile; the slab squashes it): a teal rim over near-black
        t = _pal(rng, ["#10161c", "#141b22", "#0c1217"], (S, S))
        t[:5] = _pal(rng, ["#0b4a57", "#0d5262", "#0a4250"], (5, S))
        t[5, rng.integers(0, S, 7)] = hexc("#0a4250")
        for _ in range(4):
            x, y = rng.integers(1, S - 1), rng.integers(7, S - 1)
            t[y, x] = hexc("#1fc4d0" if rng.random() < 0.5 else "#0e6c78")
    elif name == "shrieker_top":
        t = _pal(rng, ["#d8d1b8", "#cbc3a8", "#e4ddc6"], (S, S))
        t[3:13, 3:13] = hexc("#10262c")
        t[6:10, 6:10] = hexc("#2fd3d6")
        t[7:9, 7:9] = hexc("#bffcf8")
    elif name == "reinforced":
        t = _pal(rng, ["#2d3033", "#33363a", "#282a2d"], (S, S))
        t[[0, 1, 14, 15], :] = hexc("#55595e")
        t[:, [0, 1, 14, 15]] = hexc("#55595e")
        for y0 in (0, 12):
            for x0 in (0, 12):
                t[y0:y0 + 4, x0:x0 + 4] = hexc("#cbbf9a")
        t[6:10, 6:10] = hexc("#3d4146")
    t[..., 3] = 1.0
    return t



PICK_HEADS = {"wood": "#a07a45", "stone": "#8f8f8f", "iron": "#e6e6e6", "diamond": "#4ff0e6"}


def sprite(kind):
    """16×16 pixel sprite as {(x, y): rgba}, y up."""
    px = {}

    def put(x, y, c):
        px[(x, 15 - y)] = hexc(c) if isinstance(c, str) else c
    if kind.startswith("pick_"):
        head = PICK_HEADS[kind[5:]]
        for i in range(3, 14):  # handle on the diagonal
            put(i - 1, 15 - i + 1, "#6b4a23")
            put(i, 15 - i + 1, "#8a6233")
        for x, y in [(4, 2), (5, 1), (6, 1), (7, 1), (8, 1), (9, 1), (10, 2), (11, 2), (12, 3), (13, 4), (13, 5), (14, 6),
                     (14, 7), (14, 8), (14, 9), (5, 2), (6, 2), (12, 4), (13, 6), (13, 7)]:
            put(x, y, head)
    elif kind == "ingot":
        for y in range(6, 11):
            for x in range(2 + (10 - y) // 2, 14 - (10 - y) // 2):
                put(x, y, "#f2f2f2" if y == 6 else "#d0d0d0" if y < 10 else "#a8a8a8")
    elif kind == "raw_iron":
        for x in range(3, 13):
            for y in range(4, 12):
                if math.hypot((x - 7.5) / 5, (y - 7.5) / 4) < 1:
                    put(x, y, "#e3bb94" if (x + y) % 3 == 0 else "#c9946a" if (x * y) % 5 else "#9c6c48")
    elif kind == "diamond":
        for y in range(2, 14):
            w = (y - 2) if y < 6 else (13 - y) * 0.6
            for x in range(8 - int(w) - 1, 8 + int(w) + 1):
                put(x, y, "#c8fffb" if y < 6 and x < 8 else "#4ff0e6" if (x + y) % 4 else "#2fc9c0")
    elif kind == "stick":
        for i in range(3, 14):
            put(i, 15 - i, "#7a5530")
            put(i - 1, 15 - i, "#5c3f22")
    elif kind == "bread":
        for x in range(2, 14):
            for y in range(6, 11):
                if math.hypot((x - 7.5) / 6, (y - 8) / 2.6) < 1:
                    put(x, y, "#8a5a22" if x % 3 == 0 and y > 7 else "#c98b3c")
    elif kind == "sword_iron":
        for i in range(5, 14):  # blade on the diagonal, lit edge and shaded edge
            put(i, 15 - i, "#f0f0f0")
            put(i - 1, 15 - i, "#b8b8b8")
        put(14, 1, "#ffffff")
        for x, y in [(2, 9), (3, 10), (4, 11), (5, 12), (6, 13)]:  # cross guard
            put(x, y, "#4a4a4a")
        put(3, 12, "#6b4a23")
        put(2, 13, "#8a6233")
        put(1, 14, "#4a4a4a")
    elif kind in ("heart", "heart_empty"):
        rows = ["..##...##..", ".####.####.", "###########", "###########", ".#########.", "..#######..", "...#####...",
                "....###....", ".....#....."]
        for r, row in enumerate(rows):
            for c, ch in enumerate(row):
                if ch != "#":
                    continue
                if kind == "heart_empty":
                    col = "#2a2a2e"
                elif (r, c) in ((1, 1), (1, 2), (2, 1)):
                    col = "#ffb3b3"
                else:
                    col = "#e0262b" if r < 6 else "#b81b20"
                put(c + 2, r + 3, col)
    elif kind == "echo_shard":
        # a fat crystal shard on the diagonal, tapered at both ends, lit along its upper-left face
        for x in range(16):
            for y in range(16):
                d = (x + y - 15) / math.sqrt(2)          # across the shard
                t = (x - y + 15) / 2                     # along it, 0..15
                if not 2 <= t <= 14:
                    continue
                w = 2.6 * math.sin(math.pi * (t - 2) / 12)
                if abs(d) > w:
                    continue
                col = "#0b3a40" if abs(d) > w - 0.8 else "#8ff8f3" if d < -0.4 else "#1d8a92" if (x + 2 * y) % 5 else "#136068"
                put(x, 15 - y, col)
    elif kind == "book_ench":
        for x in range(3, 13):
            for y in range(2, 14):
                put(x, y, "#4a2878" if x < 5 else "#6a3fa0")
        for y in range(3, 13):
            put(12, y, "#efe7d2")
        for x, y in ((6, 4), (7, 4), (8, 5), (9, 7), (7, 8), (10, 10)):
            put(x, y, "#e8c34a")
    elif kind == "apple_gold":
        for x in range(2, 14):
            for y in range(3, 15):
                r = math.hypot(x - 7.5, (y - 9) * 1.05)
                if r < 5.6:
                    put(x, y, "#fff2a8" if (x, y) in ((5, 6), (5, 7), (6, 6)) else "#f2c84b" if r < 4 else "#d9a826")
        put(8, 3, "#6b4a23")
        put(8, 2, "#6b4a23")
        put(9, 2, "#5ea83a")
        put(10, 1, "#5ea83a")
    return px


