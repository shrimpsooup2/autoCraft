"""
Shared toolkit for the autoCraft explainer, run inside Blender (5.x). Everything is generated: pixel textures,
voxel meshes, characters, items, effects, lighting. Units: 1 block = 1 m, Z up, characters face -Y.
"""
import math
import random
import bpy
import zlib
import numpy as np
from mathutils import Vector, Euler
from art import hexc, TILE, TILES, ATLAS_W, ATLAS_H, tile_pixels, PICK_HEADS, sprite

FPS = 30
PX = 1.8 / 32  # one character "pixel" (a player is 32 px = 1.8 blocks tall)

# ---- colour helpers ---------------------------------------------------------------------------------------------


def lin(c):
    """sRGB → linear for colours fed straight into shader sockets."""
    def f(v):
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    return (f(c[0]), f(c[1]), f(c[2]), c[3] if len(c) > 3 else 1.0)


RED = hexc("#e5352b")
CYAN = hexc("#39d0ff")
AMBER = hexc("#ffbf3c")
GREEN = hexc("#5ee36a")
WHITE = hexc("#ffffff")

# ---- scene setup ------------------------------------------------------------------------------------------------


def reset(frames, res=(1920, 1080), samples=32):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.render.engine = "BLENDER_EEVEE"
    sc.render.resolution_x, sc.render.resolution_y = res
    sc.render.resolution_percentage = 100
    sc.render.fps = FPS
    sc.frame_start, sc.frame_end = 1, frames
    sc.render.image_settings.file_format = "PNG"
    sc.render.image_settings.color_mode = "RGB"
    sc.eevee.taa_render_samples = samples
    try:
        sc.view_settings.view_transform = "AgX"
        sc.view_settings.look = "AgX - Punchy"
    except TypeError:
        pass
    world = bpy.data.worlds.new("World")
    sc.world = world
    return sc


def sky_grad(zenith, horizon, ground="#3a4a35", ambient=0.6, sun_elev=30.0, sun_rot=40.0, sun_energy=3.5,
             sun_color="#fff3e0", camera_strength=1.0, middle=None):
    """Art-directed sky: a vertical gradient the camera sees at full strength, lighting the scene at `ambient`,
    plus a sun lamp (the key light) at the given elevation/rotation (degrees)."""
    w = bpy.context.scene.world
    w.use_nodes = True
    nt = w.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputWorld")
    tc = nt.nodes.new("ShaderNodeTexCoord")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(tc.outputs["Generated"], sep.inputs[0])
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    mr = nt.nodes.new("ShaderNodeMapRange")
    mr.inputs["From Min"].default_value = -1.0
    mr.inputs["From Max"].default_value = 1.0
    nt.links.new(sep.outputs["Z"], mr.inputs["Value"])
    nt.links.new(mr.outputs["Result"], ramp.inputs["Fac"])
    els = ramp.color_ramp.elements
    els[0].position, els[0].color = 0.45, lin(hexc(ground))
    els[1].position, els[1].color = 1.0, lin(hexc(zenith))
    e = els.new(0.505)
    e.color = lin(hexc(horizon))
    e2 = els.new(0.62)
    hz, zn = hexc(horizon), hexc(zenith)
    mid = hexc(middle) if middle else tuple(hz[i] * 0.35 + zn[i] * 0.25 + 0.4 * (0.85, 0.88, 0.95)[i] for i in range(3)) + (1,)
    e2.color = lin(mid)
    lp = nt.nodes.new("ShaderNodeLightPath")
    bg_cam = nt.nodes.new("ShaderNodeBackground")
    bg_light = nt.nodes.new("ShaderNodeBackground")
    nt.links.new(ramp.outputs["Color"], bg_cam.inputs["Color"])
    nt.links.new(ramp.outputs["Color"], bg_light.inputs["Color"])
    bg_cam.inputs["Strength"].default_value = camera_strength
    bg_light.inputs["Strength"].default_value = ambient
    mix = nt.nodes.new("ShaderNodeMixShader")
    nt.links.new(lp.outputs["Is Camera Ray"], mix.inputs["Fac"])
    nt.links.new(bg_light.outputs[0], mix.inputs[1])
    nt.links.new(bg_cam.outputs[0], mix.inputs[2])
    nt.links.new(mix.outputs[0], out.inputs[0])
    sun_data = bpy.data.lights.new("Sun", "SUN")
    sun_data.energy = sun_energy
    sun_data.color = lin(hexc(sun_color))[:3]
    sun_data.angle = math.radians(3.0)
    sun = bpy.data.objects.new("Sun", sun_data)
    bpy.context.scene.collection.objects.link(sun)
    sun.rotation_euler = Euler((math.radians(90 - sun_elev), 0, math.radians(sun_rot)), "XYZ")
    return sun


def sky(sun_elev=12.0, sun_rot=40.0, strength=1.0, tint=None, air=1.0):
    """Physical sky plus a matching sun lamp. tint: optional colour multiplied into the sky (night, sunset)."""
    w = bpy.context.scene.world
    w.use_nodes = True
    nt = w.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputWorld")
    bg = nt.nodes.new("ShaderNodeBackground")
    sk = nt.nodes.new("ShaderNodeTexSky")
    try:
        sk.sky_type = "MULTIPLE_SCATTERING"
    except TypeError:
        try:
            sk.sky_type = "NISHITA"
        except TypeError:
            pass
    for attr, v in (("sun_elevation", math.radians(sun_elev)), ("sun_rotation", math.radians(sun_rot)), ("air_density", air)):
        if hasattr(sk, attr):
            setattr(sk, attr, v)
    src = sk.outputs[0]
    if tint is not None:
        mix = nt.nodes.new("ShaderNodeMix")
        mix.data_type = "RGBA"
        mix.blend_type = "MULTIPLY"
        mix.inputs["Factor"].default_value = 1.0
        nt.links.new(sk.outputs[0], mix.inputs["A"])
        mix.inputs["B"].default_value = lin(tint)
        src = mix.outputs["Result"]
    nt.links.new(src, bg.inputs["Color"])
    bg.inputs["Strength"].default_value = strength
    nt.links.new(bg.outputs[0], out.inputs[0])
    sun_data = bpy.data.lights.new("Sun", "SUN")
    sun_data.energy = 4.0 * strength
    sun_data.angle = math.radians(2.0)
    sun = bpy.data.objects.new("Sun", sun_data)
    bpy.context.scene.collection.objects.link(sun)
    sun.rotation_euler = Euler((math.radians(90 - sun_elev), 0, math.radians(sun_rot + 90)), "XYZ")
    return sun


def bloom(threshold=0.9, size=6, mix=0.0):
    """Compositor glare for glowing things (the 5.x compositor uses a node group on the scene)."""
    sc = bpy.context.scene
    try:
        tree = bpy.data.node_groups.new("Comp", "CompositorNodeTree")
        sc.compositing_node_group = tree
        rl = tree.nodes.new("CompositorNodeRLayers")
        gl = tree.nodes.new("CompositorNodeGlare")
        out = tree.nodes.new("NodeGroupOutput")
        tree.interface.new_socket("Image", in_out="OUTPUT", socket_type="NodeSocketColor")
        set_input(gl, "Type", "Bloom", attr="glare_type")
        set_input(gl, "Threshold", threshold, attr="threshold")
        set_input(gl, "Size", size, attr="size")
        tree.links.new(rl.outputs["Image"], gl.inputs["Image"])
        tree.links.new(gl.outputs["Image"], out.inputs[0])
    except Exception as e:  # compositor API drift between versions: render without bloom rather than fail
        print("bloom unavailable:", e)


def set_input(node, name, value, attr=None):
    if name in node.inputs:
        try:
            node.inputs[name].default_value = value
            return
        except Exception:
            pass
    if attr and hasattr(node, attr):
        v = value.upper() if isinstance(value, str) else value
        try:
            setattr(node, attr, v)
        except Exception:
            pass


def link(obj, collection=None):
    (collection or bpy.context.scene.collection).objects.link(obj)
    return obj


def empty(name, loc=(0, 0, 0), parent=None):
    e = bpy.data.objects.new(name, None)
    e.empty_display_size = 0.2
    e.location = loc
    link(e)
    if parent:
        e.parent = parent
    return e


# ---- materials --------------------------------------------------------------------------------------------------


def mat_image(name, image, rough=0.85, emission=0.0, alpha=None, emission_color=None):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = image
    tex.interpolation = "Closest"
    nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
    bsdf.inputs["Roughness"].default_value = rough
    if emission:
        if emission_color is not None:
            bsdf.inputs["Emission Color"].default_value = lin(emission_color)
        else:
            nt.links.new(tex.outputs["Color"], bsdf.inputs["Emission Color"])
        bsdf.inputs["Emission Strength"].default_value = emission
    if alpha is not None:
        bsdf.inputs["Alpha"].default_value = alpha
        transparent(m)
    return m


def mat_vcol(name, rough=0.8, emission=0.0, alpha=None):
    """Colour from the mesh's per-face-corner colour attribute ("Col")."""
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    attr = nt.nodes.new("ShaderNodeAttribute")
    attr.attribute_name = "Col"
    nt.links.new(attr.outputs["Color"], bsdf.inputs["Base Color"])
    bsdf.inputs["Roughness"].default_value = rough
    if emission:
        nt.links.new(attr.outputs["Color"], bsdf.inputs["Emission Color"])
        bsdf.inputs["Emission Strength"].default_value = emission
    if alpha is not None:
        bsdf.inputs["Alpha"].default_value = alpha
        transparent(m)
    return m


def mat_flat(name, color, emission=0.0, alpha=None, rough=0.6):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    bsdf = m.node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Base Color"].default_value = lin(color)
    bsdf.inputs["Roughness"].default_value = rough
    if emission:
        bsdf.inputs["Emission Color"].default_value = lin(color)
        bsdf.inputs["Emission Strength"].default_value = emission
    if alpha is not None:
        bsdf.inputs["Alpha"].default_value = alpha
        transparent(m)
    return m


def transparent(m):
    for attr, v in (("surface_render_method", "BLENDED"), ("blend_method", "BLEND")):
        if hasattr(m, attr):
            try:
                setattr(m, attr, v)
            except Exception:
                pass
    if hasattr(m, "use_transparency_overlap"):
        m.use_transparency_overlap = False


def animate_input(mat, socket, keys):
    """keys: [(frame, value), ...] on the Principled BSDF input `socket`."""
    s = mat.node_tree.nodes["Principled BSDF"].inputs[socket]
    for f, v in keys:
        s.default_value = v
        s.keyframe_insert("default_value", frame=f)


# ---- pixel textures ---------------------------------------------------------------------------------------------

def atlas():
    img = bpy.data.images.get("atlas")
    if img:
        return img
    W, H = ATLAS_W * TILE, ATLAS_H * TILE
    px = np.zeros((H, W, 4))
    for i, name in enumerate(TILES):
        tx, ty = i % ATLAS_W, i // ATLAS_W
        px[ty * TILE:(ty + 1) * TILE, tx * TILE:(tx + 1) * TILE] = tile_pixels(name)
    img = bpy.data.images.new("atlas", W, H, alpha=True)
    img.pixels.foreach_set(np.flipud(px).astype(np.float32).ravel())  # Blender images start bottom-left
    img.pack()
    return img


def tile_uv(name):
    i = TILES.index(name)
    tx, ty = i % ATLAS_W, i // ATLAS_W
    u0, u1 = tx / ATLAS_W, (tx + 1) / ATLAS_W
    v1 = 1 - ty / ATLAS_H
    v0 = 1 - (ty + 1) / ATLAS_H
    e = 0.001
    return u0 + e, v0 + e, u1 - e, v1 - e


# block → (top, side, bottom) tiles; side may face-specific via FRONT
BLOCKS = {
    "grass": ("grass_top", "grass_side", "dirt"), "dirt": ("dirt",) * 3, "stone": ("stone",) * 3, "cobble": ("cobble",) * 3,
    "deepslate": ("deepslate",) * 3, "log": ("log_top", "log_side", "log_top"), "planks": ("planks",) * 3,
    "leaves": ("leaves",) * 3, "sand": ("sand",) * 3, "water": ("water",) * 3, "iron_ore": ("iron_ore",) * 3,
    "coal_ore": ("coal_ore",) * 3, "diamond_ore": ("diamond_ore",) * 3, "deepslate_diamond": ("deepslate_diamond",) * 3,
    "glass": ("glass",) * 3, "bricks": ("bricks",) * 3, "table": ("table_top", "table_side", "planks"),
    "furnace": ("furnace_side", "furnace_side", "furnace_side"), "furnace_lit": ("furnace_side", "furnace_side", "furnace_side"),
    "chest": ("chest", "chest", "chest_inner"), "wool_red": ("wool_red",) * 3, "farmland": ("farmland", "dirt", "dirt"),
    "wheat": ("wheat", "wheat", "farmland"), "gravel": ("gravel",) * 3, "snow": ("snow", "grass_side", "dirt"),
    "path": ("path", "dirt", "dirt"), "deepslate_bricks": ("deepslate_bricks",) * 3, "deepslate_tiles": ("deepslate_tiles",) * 3,
    "sculk": ("sculk",) * 3, "sculk_sensor": ("sensor_top", "sensor_side", "sculk"), "reinforced": ("reinforced",) * 3,
    "shrieker": ("shrieker_top", "shrieker_side", "sculk"),
}
FRONT = {"furnace": "furnace_front", "furnace_lit": "furnace_lit"}  # their -Y face
TRANSPARENT = {"water", "glass", "leaves"}

DIRS = [((1, 0, 0), "side"), ((-1, 0, 0), "side"), ((0, 1, 0), "side"), ((0, -1, 0), "side"), ((0, 0, 1), "top"), ((0, 0, -1), "bottom")]
FACE_VERTS = {
    (1, 0, 0): [(1, 0, 0), (1, 1, 0), (1, 1, 1), (1, 0, 1)],
    (-1, 0, 0): [(0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)],
    (0, 1, 0): [(1, 1, 0), (0, 1, 0), (0, 1, 1), (1, 1, 1)],
    (0, -1, 0): [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)],
    (0, 0, 1): [(0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)],
    (0, 0, -1): [(0, 1, 0), (1, 1, 0), (1, 0, 0), (0, 0, 0)],
}


AO_LEVELS = (0.42, 0.62, 0.82, 1.0)


def block_mesh(name, blocks, material=None, scale=1.0, origin=(0, 0, 0), cull_against=None, ao=True):
    """blocks: {(x,y,z): kind}. One mesh of the faces that can be seen (opaque neighbours hide faces; a transparent
    block hides only faces against the same kind). cull_against: extra blocks that hide faces but aren't drawn.
    Each face corner gets baked ambient occlusion ("AO" attribute) from the blocks around it."""
    verts, faces, uvs, aos = [], [], [], []
    solid = dict(cull_against or {})
    solid.update(blocks)

    def opaque(p):
        k = solid.get(p)
        return k is not None and k not in TRANSPARENT
    for (x, y, z), kind in blocks.items():
        top, side, bottom = BLOCKS[kind]
        for d, role in DIRS:
            n = solid.get((x + d[0], y + d[1], z + d[2]))
            if n is not None and (n not in TRANSPARENT or n == kind):
                continue
            tile = top if role == "top" else bottom if role == "bottom" else (FRONT.get(kind, side) if d == (0, -1, 0) else side)
            u0, v0, u1, v1 = tile_uv(tile)
            base = len(verts)
            axis = 0 if d[0] else 1 if d[1] else 2
            t1, t2 = [a for a in range(3) if a != axis]
            for vx, vy, vz in FACE_VERTS[d]:
                verts.append(((x + vx - origin[0]) * scale, (y + vy - origin[1]) * scale, (z + vz - origin[2]) * scale))
                if ao and kind not in TRANSPARENT:
                    c = (vx, vy, vz)
                    P = [x + d[0], y + d[1], z + d[2]]
                    s1 = [0, 0, 0]
                    s2 = [0, 0, 0]
                    s1[t1] = 1 if c[t1] else -1
                    s2[t2] = 1 if c[t2] else -1
                    a = opaque((P[0] + s1[0], P[1] + s1[1], P[2] + s1[2]))
                    b = opaque((P[0] + s2[0], P[1] + s2[1], P[2] + s2[2]))
                    cn = opaque((P[0] + s1[0] + s2[0], P[1] + s1[1] + s2[1], P[2] + s1[2] + s2[2]))
                    level = 0 if (a and b) else 3 - (a + b + cn)
                    v = AO_LEVELS[level]
                else:
                    v = 1.0
                aos.append((v, v, v, 1.0))
            faces.append((base, base + 1, base + 2, base + 3))
            uvs.extend([(u0, v0), (u1, v0), (u1, v1), (u0, v1)])
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    uv = me.uv_layers.new(name="UVMap")
    if uvs:
        uv.data.foreach_set("uv", np.array(uvs, dtype=np.float32).ravel())
    col = me.color_attributes.new("AO", "FLOAT_COLOR", "CORNER")
    if aos:
        col.data.foreach_set("color", np.array(aos, dtype=np.float32).ravel())
    me.update()
    ob = bpy.data.objects.new(name, me)
    link(ob)
    ob.data.materials.append(material or world_material())
    return ob


def mat_blocks(name, rough=0.85, emission=0.0, alpha=None, tint=None):
    """Atlas texture × baked AO (× tint): the material for block meshes."""
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = atlas()
    tex.interpolation = "Closest"
    ao = nt.nodes.new("ShaderNodeAttribute")
    ao.attribute_name = "AO"
    mul = nt.nodes.new("ShaderNodeMix")
    mul.data_type = "RGBA"
    mul.blend_type = "MULTIPLY"
    mul.inputs["Factor"].default_value = 1.0
    nt.links.new(tex.outputs["Color"], mul.inputs["A"])
    nt.links.new(ao.outputs["Color"], mul.inputs["B"])
    col = mul.outputs["Result"]
    if tint is not None:
        t2 = nt.nodes.new("ShaderNodeMix")
        t2.data_type = "RGBA"
        t2.blend_type = "MULTIPLY"
        t2.inputs["Factor"].default_value = 1.0
        nt.links.new(col, t2.inputs["A"])
        t2.inputs["B"].default_value = lin(tint)
        col = t2.outputs["Result"]
    nt.links.new(col, bsdf.inputs["Base Color"])
    bsdf.inputs["Roughness"].default_value = rough
    if emission:
        nt.links.new(col, bsdf.inputs["Emission Color"])
        bsdf.inputs["Emission Strength"].default_value = emission
    if alpha is not None:
        bsdf.inputs["Alpha"].default_value = alpha
        transparent(m)
    return m


def world_material():
    m = bpy.data.materials.get("Voxel")
    return m or mat_blocks("Voxel")


def water_material(color="#1d58b0"):
    """Deep blue, glossy and see-through (the bed shows through near the banks)."""
    m = bpy.data.materials.get("Water")
    if m:
        return m
    m = mat_flat("Water", hexc(color), alpha=0.84, rough=0.14)
    bsdf = m.node_tree.nodes["Principled BSDF"]
    if "Specular IOR Level" in bsdf.inputs:
        bsdf.inputs["Specular IOR Level"].default_value = 0.35
    return m


# ---- colour voxels (characters, items, logo, particles) ---------------------------------------------------------


def color_mesh(name, vox, size=PX, center=(0, 0, 0), material=None):
    """vox: {(x,y,z): rgba}. Visible faces only, per-face colour in the "Col" corner attribute."""
    verts, faces, cols = [], [], []
    for (x, y, z), c in vox.items():
        for d, role in DIRS:
            if (x + d[0], y + d[1], z + d[2]) in vox:
                continue
            base = len(verts)
            shade = {"top": 1.0, "bottom": 0.75}.get(role, 0.9 if d[0] else 0.85)
            for vx, vy, vz in FACE_VERTS[d]:
                verts.append(((x + vx - center[0]) * size, (y + vy - center[1]) * size, (z + vz - center[2]) * size))
            faces.append((base, base + 1, base + 2, base + 3))
            cc = (c[0] * shade, c[1] * shade, c[2] * shade, c[3])
            cols.extend([cc] * 4)
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    attr = me.color_attributes.new("Col", "FLOAT_COLOR", "CORNER")
    if cols:
        attr.data.foreach_set("color", np.array([lin(c) for c in cols], dtype=np.float32).ravel())
    me.update()
    ob = bpy.data.objects.new(name, me)
    link(ob)
    ob.data.materials.append(material or vcol_material())
    return ob


def vcol_material():
    m = bpy.data.materials.get("VCol")
    return m or mat_vcol("VCol")


def box(vox, x0, y0, z0, w, d, h, color_fn):
    """Fill a box of colour voxels; color_fn(x, y, z, face) → rgba (face = which outer face the voxel lies on)."""
    for x in range(x0, x0 + w):
        for y in range(y0, y0 + d):
            for z in range(z0, z0 + h):
                vox[(x, y, z)] = color_fn(x - x0, y - y0, z - z0)


def noisy(colors, seed):
    rng = random.Random(seed)
    cs = [hexc(c) for c in colors]
    return lambda *a: cs[rng.randrange(len(cs))]


# ---- characters -------------------------------------------------------------------------------------------------


class Rig:
    """A blocky figure: root (feet, on the ground) → hips → body/head/arms/legs pivots. Faces -Y."""

    def __init__(self, name, parts):
        self.name = name
        self.root = empty(name)
        self.pivots = {}
        for part, (pivot, mesh) in parts.items():
            p = empty(f"{name}.{part}", pivot, self.root)
            mesh.parent = p
            mesh.location = (0, 0, 0)
            self.pivots[part] = p
        self.parts = parts

    def key(self, frame, **rot):
        """Keyframe pivot rotations (degrees, about X = swing forward/back)."""
        for part, deg in rot.items():
            p = self.pivots[part]
            if isinstance(deg, (tuple, list)):
                p.rotation_euler = Euler([math.radians(v) for v in deg])
            else:
                p.rotation_euler = Euler((math.radians(deg), 0, 0))
            p.keyframe_insert("rotation_euler", frame=frame)

    def place(self, frame, loc, yaw_deg=None, z=None):
        self.root.location = Vector((loc[0], loc[1], loc[2] if z is None else z))
        self.root.keyframe_insert("location", frame=frame)
        if yaw_deg is not None:
            self.root.rotation_euler = Euler((0, 0, math.radians(yaw_deg)))
            self.root.keyframe_insert("rotation_euler", frame=frame)


def humanoid(name, skin, shirt, pants, shoes, hair=None, eyes=("#ffffff", "#3b6fd8"), face="player", light=None, thin=False):
    """Player-proportioned figure built from colour voxels. Returns a Rig."""
    sk, sh, pa, so = hexc(skin), hexc(shirt), hexc(pants), hexc(shoes)
    hr = hexc(hair) if hair else None
    limb = 2 if thin else 4
    # head 8×8×8, front face is y = 0
    head = {}

    def headc(x, y, z):
        c = sk
        if hr and (z >= 6 or (y > 0 and z >= 3) or (x in (0, 7) and z >= 4 and y > 0)):
            c = hr
        if y == 0:
            if face == "player":
                if hr and z >= 6:
                    c = hr
                if z == 3 and x in (1, 6):
                    c = hexc(eyes[0])
                if z == 3 and x in (2, 5):
                    c = hexc(eyes[1])
                if z == 1 and 3 <= x <= 4:
                    c = hexc("#9c5b3c")
            elif face == "zombie":
                if z == 3 and x in (1, 2, 5, 6):
                    c = hexc("#1d3b23")
                if z == 1 and 2 <= x <= 5:
                    c = hexc("#2c4f33")
            elif face == "skeleton":
                if z in (3, 4) and x in (1, 2, 5, 6):
                    c = hexc("#3a3a3a")
                if z == 1 and 1 <= x <= 6 and x % 2:
                    c = hexc("#4a4a4a")
            elif face == "creeper":
                if z in (4, 5) and x in (1, 2, 5, 6):
                    c = hexc("#121212")
                if (z == 3 and x in (3, 4)) or (z in (1, 2) and x in (2, 3, 4, 5)) or (z == 0 and x in (2, 5)):
                    c = hexc("#121212")
        return c
    box(head, 0, 0, 0, 8, 8, 8, headc)
    shade = lambda c, f: (c[0] * f, c[1] * f, c[2] * f, 1)
    body = {}
    box(body, 0, 0, 0, 8, 4, 12, lambda x, y, z: shade(sh, 0.92 + 0.08 * ((x + z) % 2)) if z < 11 else sh)
    arm_l, arm_r = {}, {}
    box(arm_l, 0, 0, 0, limb, limb, 12, lambda x, y, z: sk if z < 3 else sh)
    box(arm_r, 0, 0, 0, limb, limb, 12, lambda x, y, z: sk if z < 3 else sh)
    leg_l, leg_r = {}, {}
    box(leg_l, 0, 0, 0, limb, limb, 12, lambda x, y, z: so if z < 2 else pa)
    box(leg_r, 0, 0, 0, limb, limb, 12, lambda x, y, z: so if z < 2 else pa)
    # meshes centred so each pivot sits at the joint: head pivot at neck (bottom centre), limbs at their tops
    parts = {
        "head": ((0, 0, 24 * PX), color_mesh(f"{name}_head", head, center=(4, 4, 0))),
        "body": ((0, 0, 12 * PX), color_mesh(f"{name}_body", body, center=(4, 2, 0))),
        "arm_l": ((-(4 + limb / 2) * PX, 0, 24 * PX), color_mesh(f"{name}_arml", arm_l, center=(limb / 2, limb / 2, 12))),
        "arm_r": (((4 + limb / 2) * PX, 0, 24 * PX), color_mesh(f"{name}_armr", arm_r, center=(limb / 2, limb / 2, 12))),
        "leg_l": ((-limb / 2 * PX - (0 if thin else 0), 0, 12 * PX), color_mesh(f"{name}_legl", leg_l, center=(limb / 2, limb / 2, 12))),
        "leg_r": ((limb / 2 * PX, 0, 12 * PX), color_mesh(f"{name}_legr", leg_r, center=(limb / 2, limb / 2, 12))),
    }
    if thin:
        parts["leg_l"] = ((-2 * PX, 0, 12 * PX), parts["leg_l"][1])
        parts["leg_r"] = ((2 * PX, 0, 12 * PX), parts["leg_r"][1])
    rig = Rig(name, parts)
    if light:
        lm = mat_flat(f"{name}_light", light, emission=6.0)
        bpy.ops.mesh.primitive_cube_add(size=PX * 2.2)
        gem = bpy.context.active_object
        gem.name = f"{name}_light"
        gem.data.materials.append(lm)
        gem.parent = rig.pivots["head"]
        gem.location = (0, 4 * PX, 11 * PX)
        gem.rotation_euler = Euler((math.radians(45), math.radians(35), 0))
        rig.light = gem
        rig.light_mat = lm
    return rig


def bot(name="Bot"):
    return humanoid(name, skin="#c99868", shirt="#2d3340", pants="#1f2430", shoes="#4a4a4a", hair="#4a3020", light=RED)


def zombie(name="Zombie"):
    return humanoid(name, skin="#3f8f6a", shirt="#2fa5a5", pants="#3b46a5", shoes="#3b46a5", face="zombie")


def skeleton(name="Skeleton"):
    return humanoid(name, skin="#cfcfcf", shirt="#bdbdbd", pants="#b0b0b0", shoes="#a0a0a0", face="skeleton", thin=True)


def creeper(name="Creeper"):
    g = noisy(["#4fae4a", "#5cc256", "#3f9a3b", "#6bd265", "#2f7d2c"], zlib.crc32(name.encode()) & 0xffff)
    head, body = {}, {}
    hc = lambda x, y, z: hexc("#121212") if y == 0 and ((z in (4, 5) and x in (1, 2, 5, 6)) or (z == 3 and x in (3, 4))
                                                      or (z in (1, 2) and x in (2, 3, 4, 5)) or (z == 0 and x in (2, 5))) else g()
    box(head, 0, 0, 0, 8, 8, 8, hc)
    box(body, 0, 0, 0, 8, 4, 12, lambda *a: g())
    legs = {}
    for k in ("fl", "fr", "bl", "br"):
        lv = {}
        box(lv, 0, 0, 0, 4, 4, 6, lambda *a: g())
        legs[k] = lv
    parts = {
        "head": ((0, 0, 18 * PX), color_mesh(f"{name}_head", head, center=(4, 4, 0))),
        "body": ((0, 0, 6 * PX), color_mesh(f"{name}_body", body, center=(4, 2, 0))),
        "leg_fl": ((-2 * PX, -4 * PX, 6 * PX), color_mesh(f"{name}_lfl", legs["fl"], center=(2, 2, 6))),
        "leg_fr": ((2 * PX, -4 * PX, 6 * PX), color_mesh(f"{name}_lfr", legs["fr"], center=(2, 2, 6))),
        "leg_bl": ((-2 * PX, 4 * PX, 6 * PX), color_mesh(f"{name}_lbl", legs["bl"], center=(2, 2, 6))),
        "leg_br": ((2 * PX, 4 * PX, 6 * PX), color_mesh(f"{name}_lbr", legs["br"], center=(2, 2, 6))),
    }
    return Rig(name, parts)


# ---- motions ----------------------------------------------------------------------------------------------------


def walk_cycle(rig, f0, f1, period=16, amp=35, arms=True, run=False):
    a = amp * (1.4 if run else 1.0)
    f = f0
    i = 0
    while f <= f1:
        s = 1 if i % 2 == 0 else -1
        rig.key(f, leg_l=s * a, leg_r=-s * a)
        if arms and "arm_l" in rig.pivots:
            rig.key(f, arm_l=-s * a * 0.8, arm_r=s * a * 0.8)
        if "leg_fl" in rig.pivots:
            rig.key(f, leg_fl=s * a * 0.7, leg_br=s * a * 0.7, leg_fr=-s * a * 0.7, leg_bl=-s * a * 0.7)
        f += period // 2
        i += 1
    rest(rig, f1 + 4)


def rest(rig, f):
    keys = {k: 0 for k in rig.pivots if k.startswith(("leg", "arm"))}
    rig.key(f, **keys)


def mine_swings(rig, f0, f1, period=8, hand="arm_r"):
    f = f0
    while f + period <= f1:
        rig.key(f, **{hand: -100})
        rig.key(f + period // 2, **{hand: -40})
        f += period
    rig.key(f1, **{hand: 0})


def follow(rig, points, f0, speed=4.3, run=False, bob=True, height=None):
    """Walk the root along points [(x,y,z)...] at `speed` blocks/s, facing the way it goes. Returns the end frame."""
    f = f0
    prev = Vector(points[0])
    yaw = None
    rig.place(f, prev)
    for p in points[1:]:
        p = Vector(p)
        d = p - prev
        dist = d.length
        if dist < 1e-6:
            continue
        new_yaw = math.degrees(math.atan2(d.x, -d.y))
        if yaw is not None:
            while new_yaw - yaw > 180:
                new_yaw -= 360
            while new_yaw - yaw < -180:
                new_yaw += 360
        frames = max(2, round(dist / speed * FPS))
        rig.root.rotation_euler = Euler((0, 0, math.radians(new_yaw)))
        rig.root.keyframe_insert("rotation_euler", frame=f + 2)
        rig.place(f + frames, p)
        rig.root.rotation_euler = Euler((0, 0, math.radians(new_yaw)))
        rig.root.keyframe_insert("rotation_euler", frame=f + frames)
        yaw = new_yaw
        f += frames
        prev = p
    walk_cycle(rig, f0, f, period=12 if run else 16, run=run)
    return f


def linear(obj, path="location"):
    if obj.animation_data and obj.animation_data.action:
        for fc in fcurves(obj):
            if fc.data_path == path:
                for k in fc.keyframe_points:
                    k.interpolation = "LINEAR"


def fcurves(obj):
    """F-curves of an object's action (5.x layered actions keep them in channel bags)."""
    act = obj.animation_data.action if obj.animation_data else None
    if act is None:
        return []
    if hasattr(act, "fcurves") and len(getattr(act, "fcurves", [])):
        return list(act.fcurves)
    out = []
    for layer in getattr(act, "layers", []):
        for strip in layer.strips:
            for bag in getattr(strip, "channelbags", []):
                out.extend(bag.fcurves)
    return out


def ease_all(obj, kind="BEZIER"):
    for fc in fcurves(obj):
        for k in fc.keyframe_points:
            k.interpolation = kind


# ---- items (16×16 sprites extruded) -----------------------------------------------------------------------------

def item(kind, name=None, size=1.0):
    """A held/floating item: extruded sprite, or a mini block for block items. size = width in blocks."""
    name = name or kind
    if kind in BLOCKS:
        ob = block_mesh(name, {(0, 0, 0): kind}, scale=size, origin=(0.5, 0.5, 0.5))
        return ob
    px = sprite(kind)
    vox = {(x, 0, y): c for (x, y), c in px.items()}
    return color_mesh(name, vox, size=size / 16, center=(8, 0.5, 8))


def chest(x, y, z=1):
    """A chest standing on block (x, y) with its front to -Y: returns (base, hinge). Rotate the hinge about X to open
    the lid (about -100° is fully open); the latch rides on the lid."""
    base = block_mesh("chest_base", {(0, 0, 0): "chest"}, scale=0.875, origin=(0.5, 0.5, 0))
    base.location = (x + 0.5, y + 0.5, z)
    base.scale = (1, 1, 0.7)
    hinge = empty("chest_hinge", (x + 0.5, y + 0.5 + 0.4375, z + 0.6125))
    lid = block_mesh("chest_lid", {(0, 0, 0): "chest"}, scale=0.875, origin=(0.5, 1.0, 0))
    lid.parent = hinge
    lid.scale = (1, 1, 0.3)
    bpy.ops.mesh.primitive_cube_add(size=1)
    latch = bpy.context.active_object
    latch.scale = (0.125, 0.06, 0.25)
    latch.data.materials.append(mat_flat("latch", hexc("#cfcfcf"), rough=0.35))
    latch.parent = hinge
    latch.location = (0, -0.875 - 0.03, 0.02)
    return base, hinge


# ---- effects ----------------------------------------------------------------------------------------------------


def burst(pos, color, frame, n=12, speed=3.0, life=18, size=0.12, seed=0, gravity=14.0, collection=None):
    """Small cubes flying out of `pos` from `frame`, falling and shrinking away."""
    rng = random.Random(seed)
    mat = mat_flat(f"burst{seed}", color)
    obs = []
    for i in range(n):
        bpy.ops.mesh.primitive_cube_add(size=size)
        c = bpy.context.active_object
        c.data.materials.append(mat)
        v = Vector((rng.uniform(-1, 1), rng.uniform(-1, 1), rng.uniform(0.6, 1.6))).normalized() * speed * rng.uniform(0.6, 1.2)
        c.scale = (0, 0, 0)
        c.keyframe_insert("scale", frame=frame - 1)
        for k in range(0, life + 1, 2):
            t = k / FPS
            p = Vector(pos) + v * t + Vector((0, 0, -0.5 * gravity * t * t))
            c.location = p
            c.keyframe_insert("location", frame=frame + k)
            s = 1 - k / life
            c.scale = (s, s, s)
            c.keyframe_insert("scale", frame=frame + k)
            c.rotation_euler = Euler((t * 7, t * 5, t * 3))
            c.keyframe_insert("rotation_euler", frame=frame + k)
        obs.append(c)
    return obs


def ribbon(name, points, color, width=0.08, emission=4.0, grow=None, smooth=True):
    """A glowing tube along points; grow=(f0, f1) animates it drawing itself."""
    cu = bpy.data.curves.new(name, "CURVE")
    cu.dimensions = "3D"
    cu.bevel_depth = width
    cu.bevel_resolution = 2
    sp = cu.splines.new("POLY" if not smooth else "NURBS")
    sp.points.add(len(points) - 1)
    for i, p in enumerate(points):
        sp.points[i].co = (p[0], p[1], p[2], 1)
    if smooth:
        sp.order_u = 3
        sp.use_endpoint_u = True
    ob = bpy.data.objects.new(name, cu)
    link(ob)
    ob.data.materials.append(mat_flat(f"{name}_mat", color, emission=emission))
    if grow:
        cu.bevel_factor_end = 0.0
        cu.keyframe_insert("bevel_factor_end", frame=grow[0])
        cu.bevel_factor_end = 1.0
        cu.keyframe_insert("bevel_factor_end", frame=grow[1])
    return ob


def text(body, size=1.0, color=WHITE, emission=0.0, font="segoeuib.ttf", align="CENTER", extrude=0.0, name=None):
    cu = bpy.data.curves.new(name or f"txt_{body[:10]}", "FONT")
    cu.body = body
    cu.size = size
    cu.align_x = align
    cu.align_y = "CENTER"
    cu.extrude = extrude
    try:
        cu.font = bpy.data.fonts.load(f"C:/Windows/Fonts/{font}", check_existing=True)
    except Exception:
        pass
    ob = bpy.data.objects.new(cu.name, cu)
    link(ob)
    ob.data.materials.append(mat_flat(f"{cu.name}_m", color, emission=emission))
    return ob


def pop_in(obj, frame, dur=8, overshoot=1.15, final=None):
    s = final or tuple(obj.scale)
    obj.scale = (0, 0, 0)
    obj.keyframe_insert("scale", frame=frame)
    obj.scale = tuple(v * overshoot for v in s)
    obj.keyframe_insert("scale", frame=frame + int(dur * 0.65))
    obj.scale = s
    obj.keyframe_insert("scale", frame=frame + dur)


def pop_out(obj, frame, dur=6):
    s = tuple(obj.scale)
    obj.keyframe_insert("scale", frame=frame)
    obj.scale = (0, 0, 0)
    obj.keyframe_insert("scale", frame=frame + dur)
    obj.scale = s


def visible(obj, keys):
    """keys: [(frame, bool)] — hides/shows the object in renders (constant interpolation)."""
    for f, v in keys:
        obj.hide_render = not v
        obj.keyframe_insert("hide_render", frame=f)
        obj.hide_viewport = not v
        obj.keyframe_insert("hide_viewport", frame=f)


# ---- camera -----------------------------------------------------------------------------------------------------


def camera(lens=35, dof=None):
    cd = bpy.data.cameras.new("Cam")
    cd.lens = lens
    cd.clip_end = 1000
    cam = bpy.data.objects.new("Cam", cd)
    link(cam)
    bpy.context.scene.camera = cam
    target = empty("CamTarget")
    tc = cam.constraints.new("TRACK_TO")
    tc.target = target
    tc.track_axis = "TRACK_NEGATIVE_Z"
    tc.up_axis = "UP_Y"
    if dof:
        cd.dof.use_dof = True
        cd.dof.focus_object = target
        cd.dof.aperture_fstop = dof
    return cam, target


def cam_keys(cam, target, keys):
    """keys: [(frame, cam_loc, target_loc)] with smooth easing between them."""
    for f, c, t in keys:
        cam.location = c
        cam.keyframe_insert("location", frame=f)
        target.location = t
        target.keyframe_insert("location", frame=f)


# ---- terrain ----------------------------------------------------------------------------------------------------


def value_noise(w, h, scale, seed, octaves=3):
    rng = np.random.default_rng(seed)
    out = np.zeros((h, w))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        s = scale / (2 ** o)
        gw, gh = int(w / s) + 3, int(h / s) + 3
        grid = rng.random((gh, gw))
        ys, xs = np.mgrid[0:h, 0:w]
        fx, fy = xs / s, ys / s
        x0, y0 = fx.astype(int), fy.astype(int)
        tx, ty = fx - x0, fy - y0
        tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
        a = grid[y0, x0] * (1 - tx) + grid[y0, x0 + 1] * tx
        b = grid[y0 + 1, x0] * (1 - tx) + grid[y0 + 1, x0 + 1] * tx
        out += amp * (a * (1 - ty) + b * ty)
        total += amp
        amp *= 0.5
    return out / total


def tree(blocks, x, y, z, height=5, rng=None):
    rng = rng or random.Random(x * 31 + y)
    for k in range(height):
        blocks[(x, y, z + k)] = "log"
    top = z + height
    for dz in range(-2, 2):
        r = 2 if dz < 0 else 1
        for dx in range(-r, r + 1):
            for dy in range(-r, r + 1):
                if abs(dx) == r and abs(dy) == r and (dz == -1 or rng.random() < 0.5):
                    continue
                p = (x + dx, y + dy, top + dz)
                if p not in blocks:
                    blocks[p] = "leaves"
    blocks[(x, y, top + 1)] = "leaves"


# ---- landscape --------------------------------------------------------------------------------------------------


def landscape(size=96, seed=7, base=6, amp=9, water=6, river=True, trees=0.02, forest_x=None, depth=4, rng_seed=3):
    """Rolling hills with an optional river. Returns (blocks, water_blocks, height[y][x] dict). Columns are filled
    `depth` blocks down (grass, dirt, stone); everything is centred on (0, 0)."""
    h = value_noise(size, size, 24, seed, octaves=3)
    half = size // 2
    blocks, wat, height = {}, {}, {}
    rng = random.Random(rng_seed)
    for j in range(size):
        for i in range(size):
            x, y = i - half, j - half
            z = int(base + amp * (h[j, i] - 0.35))
            riv = False
            if river:
                cy = 10 * math.sin(x / 17.0) - 6
                if abs(y - cy) < 3.2:
                    z = min(z, water - 1 - (1 if abs(y - cy) < 1.8 else 0))
                    riv = True
                elif abs(y - cy) < 5:
                    z = min(z, water)
            height[(x, y)] = z
            top = "sand" if (z <= water and (riv or z <= water)) else "grass"
            for k in range(depth):
                zz = z - k
                blocks[(x, y, zz)] = top if k == 0 else ("dirt" if k < 3 and top == "grass" else "sand" if top == "sand" and k < 2 else "stone")
            if z < water:
                for zz in range(z + 1, water + 1):
                    wat[(x, y, zz)] = "water"
    if trees:
        for (x, y), z in height.items():
            if z <= water or abs(x) > half - 3 or abs(y) > half - 3:
                continue
            p = trees * (3.0 if forest_x is not None and x > forest_x else 0.4)
            if rng.random() < p and all((x + dx, y + dy, z + 1) not in blocks for dx in (-2, 0, 2) for dy in (-2, 0, 2)):
                tree(blocks, x, y, z + 1, rng.randint(4, 6), rng)
    return blocks, wat, height


def ground_z(height, x, y):
    return height.get((round(x), round(y)), 0) + 1


# ---- logo -------------------------------------------------------------------------------------------------------

LOGO = [
    "................................", "................................", "................................", "................................",
    "...........#####................", "...........#####................", "......##...#####...........###..", ".....####..#####......######.#..",
    "....######.#####.....#.....###..", "...##############.###...........", "...#################......#.....", "....################...##.......",
    ".....##############....##.......", "......#############.............", ".###########...######...........", ".##########.....#######.....###.",
    ".##########.....#############.#.", ".##########.....#######.....###.", ".###########...######...........", "......#############.............",
    ".....##############....##.......", "....################...##.......", "...#################......#.....", "...##############.###...........",
    "....######.#####.....#.....###..", ".....####..#####......######.#..", "......##...#####...........###..", "...........#####................",
    "...........#####................", "................................", "................................", "................................",
]


def logo_columns(voxel=0.25, depth=2, color=RED, emission=0.6):
    """The icon as voxel columns (one object per column, origin at the logo centre's column position), so a shot
    can fly them in one by one. Returns [(x_index, object)]."""
    mat = mat_flat("LogoRed", color, emission=emission, rough=0.35)
    cols = []
    for cx in range(32):
        vox = {}
        for r in range(32):
            if LOGO[r][cx] == "#":
                for d in range(depth):
                    vox[(0, d, 31 - r)] = color
        if not vox:
            continue
        ob = color_mesh(f"logo_col{cx}", vox, size=voxel, center=(0.5, depth / 2, 16), material=mat)
        cols.append((cx, ob))
    return cols


def clear_corridor(blocks, height, points, radius=3.0, keep=("grass", "dirt", "stone", "sand", "water", "gravel", "path")):
    """Remove trees (anything not ground) within `radius` of the polyline `points` [(x, y), ...]: room for a walk
    or a camera move."""
    def dist(px, py):
        best = 1e9
        for (x0, y0), (x1, y1) in zip(points, points[1:]):
            dx, dy = x1 - x0, y1 - y0
            L = dx * dx + dy * dy
            t = 0 if L == 0 else max(0, min(1, ((px - x0) * dx + (py - y0) * dy) / L))
            best = min(best, math.hypot(px - (x0 + t * dx), py - (y0 + t * dy)))
        return best
    for p in [p for p, k in blocks.items() if k not in keep]:
        if dist(p[0], p[1]) <= radius:
            del blocks[p]
