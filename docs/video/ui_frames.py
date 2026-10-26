"""
Frames of the in-game panel (Tasks tab) for shot 2, drawn like the real one at 4× GUI scale, with a cursor that
adds "32 diamond", unfolds the bot's plan and presses Run all.   python ui_frames.py <out_dir>
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "blender"))
import art  # noqa: E402

S = 4                       # texture pixels per GUI pixel
W, H = 420 * S, 240 * S     # the panel is 420×240 GUI pixels
FRAMES = 250
FONT = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 8 * S)
FONT_B = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 8 * S)
FONT_S = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 7 * S)

DONE, ACTIVE, NEXT, READY, BLOCKED, GUIDE = (111, 214, 111), (255, 201, 79), (255, 255, 255), (154, 154, 154), (90, 90, 90), (85, 85, 85)
HOWC = (122, 134, 150)

_icon_cache = {}


def icon(kind, px):
    """An item/block icon `px` pixels square (block textures for blocks, sprites for items)."""
    key = (kind, px)
    if key in _icon_cache:
        return _icon_cache[key]
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    if kind in art.TILES:
        t = art.tile_pixels(kind)
        for y in range(16):
            for x in range(16):
                r, g, b, a = t[y, x]
                im.putpixel((x, y), (int(r * 255), int(g * 255), int(b * 255), 255))
        # a little shading so a flat tile reads as a block
        sh = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        d = ImageDraw.Draw(sh)
        d.rectangle([0, 0, 15, 15], outline=(0, 0, 0, 90))
        im = Image.alpha_composite(im, sh)
    else:
        for (x, y), c in art.sprite(kind).items():
            im.putpixel((x, 15 - y), tuple(int(v * 255) for v in c[:3]) + (255,))
    im = im.resize((px, px), Image.NEAREST)
    _icon_cache[key] = im
    return im


def g(v):
    return int(round(v * S))


def ease(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


# ---- the script --------------------------------------------------------------------------------------------------

TYPE_AT, AMOUNT_AT, ADD_AT, UNFOLD_AT, RUN_AT = 40, 96, 126, 151, 223
SEARCH = "diamond"
PLAN = [  # depth, amount label, icon, how
    (1, "1 Iron Pickaxe", "pick_iron", "craft"),
    (2, "3 Iron Ingot", "ingot", "smelt"),
    (3, "3 Raw Iron", "raw_iron", "mine iron ore"),
    (4, "1 Stone Pickaxe", "pick_stone", "craft"),
    (5, "3 Cobblestone", "cobble", "mine stone"),
    (6, "1 Wooden Pickaxe", "pick_wood", "craft"),
    (7, "3 Planks (any wood)", "planks", "craft"),
    (8, "1 Logs (any kind)", "log_side", "chop trees"),
    (2, "1 Furnace", "furnace_front", "craft"),
    (2, "1 Coal", "coal_ore", "mine coal ore"),
]
ALL_ITEMS = ["log_side", "planks", "stick", "stone", "cobble", "iron_ore", "coal_ore", "diamond_ore", "raw_iron", "ingot",
             "diamond", "pick_wood", "pick_stone", "pick_iron", "pick_diamond", "sand", "glass", "bricks", "bread",
             "table_top", "furnace_front", "chest", "wool_red", "gravel", "deepslate", "leaves", "wheat", "dirt"]
FOUND = ["diamond", "diamond_ore", "deepslate_diamond", "pick_diamond"]

# cursor path: (frame, x, y) in GUI px, eased between
CURSOR = [(0, 210, 150), (20, 210, 150), (34, 270, 54), (TYPE_AT + 36, 270, 54), (88, 388, 54), (AMOUNT_AT + 14, 388, 54),
          (118, 223, 87), (ADD_AT + 6, 223, 87), (142, 13, 47), (UNFOLD_AT + 30, 13, 47), (205, 150, 140),
          (215, 262, 226), (RUN_AT + 22, 262, 226), (FRAMES, 262, 226)]
CLICKS = [36, AMOUNT_AT - 3, ADD_AT, UNFOLD_AT, RUN_AT]


def cursor_at(f):
    for (f0, x0, y0), (f1, x1, y1) in zip(CURSOR, CURSOR[1:]):
        if f0 <= f <= f1:
            t = ease((f - f0) / max(1, f1 - f0))
            return x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
    return CURSOR[-1][1:]


# ---- drawing -----------------------------------------------------------------------------------------------------


def button(d, x, y, w, h, label, active=True, pressed=False, hover=False):
    top = (90, 110, 160) if pressed else (88, 88, 88) if hover else (58, 58, 58) if active else (32, 32, 32)
    d.rectangle([g(x), g(y), g(x + w) - 1, g(y + h) - 1], fill=top, outline=(20, 20, 20), width=S)
    d.line([g(x) + S, g(y) + S, g(x + w) - 2 * S, g(y) + S], fill=(110, 110, 110) if active else (50, 50, 50), width=S)
    tw = d.textlength(label, font=FONT)
    d.text((g(x + w / 2) - tw / 2, g(y + h / 2) - 5 * S), label, font=FONT, fill=(255, 255, 255) if active else (128, 128, 128))


def editbox(d, x, y, w, h, text, hint, focused, caret):
    d.rectangle([g(x), g(y), g(x + w) - 1, g(y + h) - 1], fill=(0, 0, 0), outline=(255, 255, 255) if focused else (160, 160, 160), width=S)
    if text:
        d.text((g(x + 4), g(y + 3)), text, font=FONT, fill=(224, 224, 224))
        tx = g(x + 4) + d.textlength(text, font=FONT)
    else:
        d.text((g(x + 4), g(y + 3)), hint, font=FONT, fill=(80, 80, 80))
        tx = g(x + 4)
    if focused and caret:
        d.rectangle([tx + S, g(y + 3), tx + 2 * S, g(y + 13)], fill=(224, 224, 224))


def pip(d, x, y, c):
    d.rectangle([g(x), g(y + 1), g(x + 5) - 1, g(y + 4) - 1], fill=c)
    d.rectangle([g(x + 1), g(y), g(x + 4) - 1, g(y + 5) - 1], fill=c)


def arrow(d, x, y, open_):
    c = (160, 160, 160)
    for i in range(3):
        if open_:
            d.rectangle([g(x + i), g(y + 1 + i), g(x + 5 - i) - 1, g(y + 2 + i) - 1], fill=c)
        else:
            d.rectangle([g(x + 1 + i), g(y + i), g(x + 2 + i) - 1, g(y + 5 - i) - 1], fill=c)


def frame(f):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, W - 1, H - 1], radius=3 * S, fill=(16, 16, 16, 252), outline=(80, 80, 80), width=S)
    running = f >= RUN_AT
    d.text((g(8), g(6)), "autoCraft", font=FONT_B, fill=(255, 255, 255))
    d.text((g(56), g(6)), "collect" if running else "idle", font=FONT, fill=(160, 160, 160))
    tabs = ["Tasks", "Build", "Team", "Bot", "Settings"]
    tx0 = 420 - len(tabs) * 50 - 4
    for i, t in enumerate(tabs):
        button(d, tx0 + i * 50, 4, 48, 16, t, active=i != 0)

    # ---- left: the tree
    added = f >= ADD_AT + 2
    unfolded = f >= UNFOLD_AT + 2
    shown = 0 if not unfolded else min(len(PLAN), (f - UNFOLD_AT - 2) // 5 + 1)
    todo = 1 + shown if added else 0
    head = "Tasks — running" if running else ("Tasks — %d to do" % todo if added else "Tasks")
    d.text((g(8), g(28)), head, font=FONT, fill=(255, 255, 255))
    x0, top, colw, row = 8, 40, 198, 16
    if not added:
        msg = ["Nothing to do yet. Pick items on the right", "to collect them, or add a group for a big",
               "goal and builds or items under it."]
        for i, line in enumerate(msg):
            d.text((g(x0), g(top + 4 + i * 11)), line, font=FONT_S, fill=(150, 150, 150))
    else:
        indent = 9
        rows = [(0, "32 Diamond", "diamond", "mine diamond ore", True)] + [(dp, t, ic, how, False) for dp, t, ic, how in PLAN[:shown]]
        for i, (depth, title, ic, how, is_root) in enumerate(rows):
            y = top + i * row
            appear = ADD_AT + 2 if is_root else UNFOLD_AT + 2 + (i - 1) * 5
            a = ease((f - appear) / 6)
            if a <= 0:
                continue
            yo = (1 - a) * 6
            layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
            ld = ImageDraw.Draw(layer)
            nx = x0 + depth * indent
            mid = y + row / 2 - 1 + yo
            if depth > 0:  # guide from the parent
                gx = x0 + (depth - 1) * indent + 2
                ld.rectangle([g(gx), g(y - 4 + yo), g(gx + 1) - 1, g(mid + 1)], fill=GUIDE)
                ld.rectangle([g(gx), g(mid), g(nx), g(mid + 1) - 1], fill=GUIDE)
            if is_root:
                arrow(ld, nx, mid - 2, unfolded)
            if is_root:
                c = (ACTIVE if (f // 12) % 2 == 0 else (176, 138, 48)) if running else NEXT
            else:
                c = NEXT if depth == 8 else BLOCKED if depth < 8 and title != "1 Coal" else READY
            pip(ld, nx + 7, mid - 2, c)
            layer.alpha_composite(icon(ic, g(12)), (g(nx + 15), g(y + 2 + yo)))
            tc = ACTIVE if is_root and running else (255, 255, 255) if is_root or depth == 8 else (190, 190, 190)
            ld.text((g(nx + 30), g(y + 2 + yo)), title, font=FONT, fill=tc)
            hx = g(nx + 30) + ld.textlength(title, font=FONT) + g(5)
            ld.text((hx, g(y + 2 + yo)), how, font=FONT, fill=HOWC)
            if is_root:
                cnt = "0/32"
                ld.text((g(x0 + colw - 3) - ld.textlength(cnt, font=FONT), g(y + 2 + yo)), cnt, font=FONT, fill=(160, 160, 160))
                ld.rectangle([g(nx + 30), g(y + 13 + yo), g(x0 + colw - 3), g(y + 14 + yo) - 1], fill=(43, 43, 43))
            alpha = layer.split()[3].point(lambda v: int(v * a))
            layer.putalpha(alpha)
            im.alpha_composite(layer)
        d = ImageDraw.Draw(im)

    # ---- right: add items
    rx = 214
    button(d, rx, 26, 97, 16, "Add items", active=False)
    button(d, rx + 101, 26, 97, 16, "Edit task")
    typed = SEARCH[:max(0, min(len(SEARCH), (f - TYPE_AT) // 5 + 1))] if f >= TYPE_AT else ""
    amount = "32" if f >= AMOUNT_AT + 8 else "3" if f >= AMOUNT_AT + 3 else "64"
    caret = (f // 15) % 2 == 0
    editbox(d, rx, 46, 146, 16, typed, "Search items...", 36 <= f < AMOUNT_AT - 3, caret)
    editbox(d, rx + 150, 46, 48, 16, amount, "", AMOUNT_AT - 3 <= f < ADD_AT, caret)
    d.text((g(rx), g(66)), "Adds new top-level tasks", font=FONT_S, fill=(150, 150, 150))
    items = FOUND if len(typed) >= 3 else ALL_ITEMS
    gy, cols, rows_n = 78, 10, 7
    for r in range(rows_n):
        for c in range(cols):
            i = r * cols + c
            sx, sy = rx + c * 18, gy + r * 18
            hover = i == 0 and items is FOUND and ADD_AT - 8 <= f <= ADD_AT + 8
            flash = i == 0 and items is FOUND and ADD_AT <= f <= ADD_AT + 4
            d.rectangle([g(sx), g(sy), g(sx + 18) - 1, g(sy + 18) - 1], fill=(55, 55, 55))
            d.rectangle([g(sx + 1), g(sy + 1), g(sx + 18) - 1, g(sy + 18) - 1], fill=(255, 255, 255))
            d.rectangle([g(sx + 1), g(sy + 1), g(sx + 17) - 1, g(sy + 17) - 1], fill=(255, 255, 255) if flash else (197, 197, 197) if hover else (139, 139, 139))
            if i < len(items):
                im.alpha_composite(icon(items[i], g(16)), (g(sx + 1), g(sy + 1)))
                d = ImageDraw.Draw(im)
                if items[i] in ("diamond", "ingot", "pick_wood", "pick_stone", "pick_iron", "pick_diamond", "stick", "planks", "glass", "bricks", "bread", "table_top", "furnace_front", "chest"):
                    d.rectangle([g(sx + 14), g(sy + 1), g(sx + 17) - 1, g(sy + 4) - 1], fill=(79, 163, 255))
    # tooltip on the diamond
    if items is FOUND and ADD_AT - 10 <= f < ADD_AT:
        cx, cy = cursor_at(f)
        lines = [("Diamond", (255, 255, 255)), ("Crafted / mined", (170, 170, 170)), ("Click: add 32 · Right-click: a stack", (110, 110, 110))]
        tw = max(d.textlength(t, font=FONT_S) for t, _ in lines) + g(8)
        bx, by = g(cx + 10), g(cy - 18)
        d.rectangle([bx, by, bx + tw, by + g(36)], fill=(16, 0, 16, 240), outline=(80, 0, 160), width=S)
        for k, (t, col) in enumerate(lines):
            d.text((bx + g(4), by + g(3 + k * 10)), t, font=FONT_S, fill=col)

    # ---- bottom buttons
    third = (198 - 8) // 3
    for i, lab in enumerate(["+ Group", "+ Build", "Clear done"]):
        button(d, 8 + i * (third + 4), 218, third, 18, lab)
    button(d, rx, 218, 97, 18, "Stop" if running else "Run all", pressed=RUN_AT <= f <= RUN_AT + 4, hover=RUN_AT - 8 <= f < RUN_AT)
    button(d, rx + 101, 218, 97, 18, "Run selected", active=not running)

    # ---- cursor
    cx, cy = cursor_at(f)
    if any(c <= f <= c + 4 for c in CLICKS):
        k = min(f - c for c in CLICKS if c <= f)
        r = g(3 + k * 2)
        d.ellipse([g(cx) - r, g(cy) - r, g(cx) + r, g(cy) + r], outline=(120, 190, 255, 255 - k * 50), width=S)
    pts = [(0, 0), (0, 13), (3.5, 10), (6, 15), (8, 14), (5.5, 9), (10, 9)]
    poly = [(g(cx + px), g(cy + py)) for px, py in pts]
    d.polygon(poly, fill=(255, 255, 255), outline=(0, 0, 0))
    return im


if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for f in range(FRAMES):
        frame(f).save(os.path.join(out, f"panel_{f + 1:04d}.png"))
    print("wrote", FRAMES, "frames to", out)
