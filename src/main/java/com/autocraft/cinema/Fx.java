package com.autocraft.cinema;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The film's floating graphics, drawn into the world each frame so they sit in it like everything else (depth,
 * fog, a shader pack's bloom): glowing ribbons, rings and boxes (additive, like lightning), see-through glass
 * panels, block outlines, text and items, and ghost blocks.
 */
final class Fx {
	final PoseStack poses;
	final SubmitNodeCollector out;
	final Vec3 cam;
	final Quaternionf camRot;
	final float partial;
	private final Minecraft mc = Minecraft.getInstance();

	Fx(LevelRenderContext ctx, float partial) {
		this.poses = ctx.poseStack();
		this.out = ctx.submitNodeCollector();
		this.cam = ctx.levelState().cameraRenderState.pos;
		this.camRot = new Quaternionf(ctx.levelState().cameraRenderState.orientation);
		this.partial = partial;
	}

	static int argb(int a, int rgb) {
		return (Math.max(0, Math.min(255, a)) << 24) | (rgb & 0xFFFFFF);
	}

	static int fade(int argb, double f) {
		return argb((int) (((argb >>> 24) & 0xFF) * Anim.clamp01(f)), argb);
	}

	/** An RGB colour a fraction u of the way from `from` to `to`. */
	static int mix(int from, int to, double u) {
		double k = Anim.clamp01(u);
		int r = (int) Math.round(((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * k);
		int g = (int) Math.round(((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * k);
		int b = (int) Math.round((from & 255) + ((to & 255) - (from & 255)) * k);
		return (r << 16) | (g << 8) | b;
	}

	// ---- glowing geometry ------------------------------------------------------------------------------------------
	// Solid cores are additive (lightning: bright, writes depth, culls back faces). The soft parts that fade to
	// nothing — halos, the falloff of a light — are blended without writing depth (debug quads), so they never punch
	// holes in the glass behind them or in water drawn later.

	private interface Quads {
		void emit(Matrix4f m, VertexConsumer b, Vector3f tmp);
	}

	private void glow(Quads q) {
		out.submitCustomGeometry(poses, RenderTypes.lightning(), (pose, buffer) -> q.emit(pose.pose(), buffer, new Vector3f()));
	}

	/** Blended, no depth write, no culling: glass, and the soft falloff of glows. */
	private void glass(Quads q) {
		out.submitCustomGeometry(poses, RenderTypes.debugQuads(), (pose, buffer) -> q.emit(pose.pose(), buffer, new Vector3f()));
	}

	private void v(Matrix4f m, VertexConsumer b, Vector3f tmp, Vec3 p, int color) {
		m.transformPosition((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z), tmp);
		b.addVertex(tmp.x, tmp.y, tmp.z).setColor(color);
	}

	private void quad(Matrix4f m, VertexConsumer b, Vector3f tmp, Vec3 a, Vec3 bb, Vec3 c, Vec3 d, int color) {
		v(m, b, tmp, a, color);
		v(m, b, tmp, bb, color);
		v(m, b, tmp, c, color);
		v(m, b, tmp, d, color);
	}

	/** A quad with a colour per corner, wound both ways (the additive pipeline culls back faces). */
	private void quad2(Matrix4f m, VertexConsumer b, Vector3f tmp, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, int c0, int c1, int c2, int c3) {
		quad4(m, b, tmp, p0, p1, p2, p3, c0, c1, c2, c3);
		quad4(m, b, tmp, p3, p2, p1, p0, c3, c2, c1, c0);
	}

	/** A quad with a colour per corner, wound once (for the unculled blended pipeline). */
	private void quad4(Matrix4f m, VertexConsumer b, Vector3f tmp, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, int c0, int c1, int c2, int c3) {
		v(m, b, tmp, p0, c0);
		v(m, b, tmp, p1, c1);
		v(m, b, tmp, p2, c2);
		v(m, b, tmp, p3, c3);
	}

	/**
	 * A glowing strip along points, turned to face the camera, with a soft halo fading out on both sides;
	 * grow 0..1 draws it from the start.
	 */
	void ribbon(List<Vec3> pts, double width, int color, double grow) {
		if (pts.size() < 2 || grow <= 0) return;
		List<Vec3> path = cut(pts, Anim.clamp01(grow));
		int halo = fade(color, 0.4), clear = color & 0xFFFFFF;
		List<Vec3[]> strips = new ArrayList<>(path.size());
		for (int i = 0; i < path.size() - 1; i++) {
			Vec3 p0 = path.get(i), p1 = path.get(i + 1);
			Vec3 side = p1.subtract(p0).cross(cam.subtract(p0)).normalize();
			strips.add(new Vec3[] {p0, p1, side.scale(width / 2), side.scale(width * 2)});
		}
		glow((m, b, tmp) -> {
			for (Vec3[] s : strips) quad2(m, b, tmp, s[0].subtract(s[2]), s[0].add(s[2]), s[1].add(s[2]), s[1].subtract(s[2]), color, color, color, color);
		});
		glass((m, b, tmp) -> {
			for (Vec3[] s : strips) {
				quad4(m, b, tmp, s[0].add(s[2]), s[0].add(s[3]), s[1].add(s[3]), s[1].add(s[2]), halo, clear, clear, halo);
				quad4(m, b, tmp, s[0].subtract(s[3]), s[0].subtract(s[2]), s[1].subtract(s[2]), s[1].subtract(s[3]), clear, halo, halo, clear);
			}
		});
	}

	/** The first fraction `u` of a polyline (by length). */
	static List<Vec3> cut(List<Vec3> pts, double u) {
		if (u >= 1) return pts;
		double total = 0;
		for (int i = 0; i < pts.size() - 1; i++) total += pts.get(i).distanceTo(pts.get(i + 1));
		double want = total * u;
		List<Vec3> outPts = new ArrayList<>();
		outPts.add(pts.getFirst());
		for (int i = 0; i < pts.size() - 1; i++) {
			double len = pts.get(i).distanceTo(pts.get(i + 1));
			if (want <= len) {
				outPts.add(Anim.lerp(pts.get(i), pts.get(i + 1), len <= 0 ? 1 : want / len));
				return outPts;
			}
			want -= len;
			outPts.add(pts.get(i + 1));
		}
		return outPts;
	}

	/** A glowing flat ring lying on the ground, soft on both edges; grow 0..1 draws it round from +X. */
	void ring(Vec3 c, double radius, double thickness, int color, double grow) {
		if (grow <= 0 || radius <= 0) return;
		int seg = Math.max(24, (int) (radius * 12));
		int n = (int) Math.ceil(seg * Anim.clamp01(grow));
		int halo = fade(color, 0.4), clear = color & 0xFFFFFF;
		double[] r = {Math.max(0, radius - thickness * 2), radius - thickness / 2, radius + thickness / 2, radius + thickness * 2};
		glow((m, b, tmp) -> {
			for (int i = 0; i < n; i++) {
				double a0 = i * 2 * Math.PI / seg, a1 = (i + 1) * 2 * Math.PI / seg;
				Vec3 d0 = new Vec3(Math.cos(a0), 0, Math.sin(a0)), d1 = new Vec3(Math.cos(a1), 0, Math.sin(a1));
				quad2(m, b, tmp, c.add(d0.scale(r[1])), c.add(d0.scale(r[2])), c.add(d1.scale(r[2])), c.add(d1.scale(r[1])), color, color, color, color);
			}
		});
		glass((m, b, tmp) -> {
			for (int i = 0; i < n; i++) {
				double a0 = i * 2 * Math.PI / seg, a1 = (i + 1) * 2 * Math.PI / seg;
				Vec3 d0 = new Vec3(Math.cos(a0), 0, Math.sin(a0)), d1 = new Vec3(Math.cos(a1), 0, Math.sin(a1));
				quad4(m, b, tmp, c.add(d0.scale(r[0])), c.add(d0.scale(r[1])), c.add(d1.scale(r[1])), c.add(d1.scale(r[0])), clear, halo, halo, clear);
				quad4(m, b, tmp, c.add(d0.scale(r[2])), c.add(d0.scale(r[3])), c.add(d1.scale(r[3])), c.add(d1.scale(r[2])), halo, clear, clear, halo);
			}
		});
	}

	/** A glowing solid box (both faces of each side, so it reads from inside too). */
	void glowBox(AABB box, int color) {
		glow((m, b, tmp) -> box(m, b, tmp, box, color));
	}

	/** A see-through tinted box (x-ray marks, hologram slabs). */
	void glassBox(AABB box, int color) {
		glass((m, b, tmp) -> box(m, b, tmp, box, color));
	}

	private void box(Matrix4f m, VertexConsumer b, Vector3f tmp, AABB x, int color) {
		Vec3 p000 = new Vec3(x.minX, x.minY, x.minZ), p100 = new Vec3(x.maxX, x.minY, x.minZ), p110 = new Vec3(x.maxX, x.maxY, x.minZ),
				p010 = new Vec3(x.minX, x.maxY, x.minZ), p001 = new Vec3(x.minX, x.minY, x.maxZ), p101 = new Vec3(x.maxX, x.minY, x.maxZ),
				p111 = new Vec3(x.maxX, x.maxY, x.maxZ), p011 = new Vec3(x.minX, x.maxY, x.maxZ);
		quad(m, b, tmp, p000, p100, p110, p010, color);
		quad(m, b, tmp, p101, p001, p011, p111, color);
		quad(m, b, tmp, p001, p000, p010, p011, color);
		quad(m, b, tmp, p100, p101, p111, p110, color);
		quad(m, b, tmp, p010, p110, p111, p011, color);
		quad(m, b, tmp, p001, p101, p100, p000, color);
	}

	/** Pixels across each face of {@link #pixelCube}, and how bright each ring is from the rim (0) to the middle. */
	private static final int CUBE_PX = 8;
	private static final double[] CUBE_RINGS = {0.74, 0.84, 0.93, 1.0};
	private static final Vec3 CUBE_SUN = new Vec3(0.3, 1, 0.5).normalize();

	/**
	 * A small solid cube turned `yawDeg` about its vertical axis, each face an 8×8 pixel pattern: dark at the rim,
	 * lit in the middle (the bot's status light). flash 0..1 brightens it toward white.
	 */
	void pixelCube(Vec3 c, double size, float yawDeg, int rgb, double flash) {
		double yr = Math.toRadians(yawDeg), h = size / 2, px = size / CUBE_PX;
		Vec3 x = new Vec3(Math.cos(yr), 0, Math.sin(yr)), z = new Vec3(-Math.sin(yr), 0, Math.cos(yr)), y = new Vec3(0, 1, 0);
		Vec3[][] faces = {{y, x, z}, {y.reverse(), x, z.reverse()}, {x, z.reverse(), y}, {x.reverse(), z, y}, {z, x, y}, {z.reverse(), x.reverse(), y}};
		double r = ((rgb >> 16) & 255) / 255.0, g = ((rgb >> 8) & 255) / 255.0, b = (rgb & 255) / 255.0;
		glass((m, buf, tmp) -> {
			for (int f = 0; f < faces.length; f++) {
				Vec3 n = faces[f][0], a = faces[f][1], v = faces[f][2];
				double shade = 0.72 + 0.28 * Math.max(0, n.dot(CUBE_SUN));
				Vec3 corner = c.add(n.scale(h)).subtract(a.scale(h)).subtract(v.scale(h));
				for (int i = 0; i < CUBE_PX; i++) {
					for (int j = 0; j < CUBE_PX; j++) {
						int ring = Math.min(Math.min(i, j), Math.min(CUBE_PX - 1 - i, CUBE_PX - 1 - j));
						double k = CUBE_RINGS[ring] * shade * (0.95 + 0.1 * (((i * 73 + j * 151 + f * 37) * 2654435761L >>> 16) & 255) / 255.0);
						double white = ring == CUBE_RINGS.length - 1 ? 0.45 : ring == CUBE_RINGS.length - 2 ? 0.12 : 0;
						white = white + (1 - white) * 0.35 * flash;
						int color = argb(255, rgbOf(lerp(r * k, 1, white), lerp(g * k, 1, white), lerp(b * k, 1, white)));
						Vec3 p00 = corner.add(a.scale(i * px)).add(v.scale(j * px));
						quad(m, buf, tmp, p00, p00.add(a.scale(px)), p00.add(a.scale(px)).add(v.scale(px)), p00.add(v.scale(px)), color);
					}
				}
			}
		});
	}

	private static double lerp(double from, double to, double u) {
		return from + (to - from) * u;
	}

	private static int rgbOf(double r, double g, double b) {
		return ((int) (Math.min(1, r) * 255) << 16) | ((int) (Math.min(1, g) * 255) << 8) | (int) (Math.min(1, b) * 255);
	}

	/** A soft round glow facing the camera (a light, a spark, a glint): a small bright core in a fading halo. */
	void spark(Vec3 c, double size, int color) {
		Vec3[] rim = rim(size);
		halo(c, rim, color);
		int core = fade(color, 1);
		glow((m, b, tmp) -> {
			for (int i = 0; i < rim.length - 1; i++) quad2(m, b, tmp, c, c.add(rim[i].scale(0.18)), c.add(rim[i + 1].scale(0.18)), c, core, core, core, core);
		});
	}

	/** Only the fading halo of a {@link #spark}: a glow around something solid, which no bright core shows through. */
	void halo(Vec3 c, double size, int color) {
		halo(c, rim(size), color);
	}

	/** Falls off like bloom: steeply near the middle (to 35% halfway out), then a long faint tail to the rim. */
	private void halo(Vec3 c, Vec3[] rim, int color) {
		int mid = fade(color, 0.35), clear = color & 0xFFFFFF;
		glass((m, b, tmp) -> {
			for (int i = 0; i < rim.length - 1; i++) {
				Vec3 h0 = c.add(rim[i].scale(0.5)), h1 = c.add(rim[i + 1].scale(0.5));
				quad4(m, b, tmp, c, h0, h1, c, color, mid, mid, color);
				quad4(m, b, tmp, h0, c.add(rim[i]), c.add(rim[i + 1]), h1, mid, clear, clear, mid);
			}
		});
	}

	/** A camera-facing circle of `size` across, as 16 offsets from its centre (the first repeated at the end). */
	private Vec3[] rim(double size) {
		Vector3f right = new Vector3f(1, 0, 0).rotate(camRot), up = new Vector3f(0, 1, 0).rotate(camRot);
		Vec3 r = new Vec3(right.x, right.y, right.z).scale(size / 2), u = new Vec3(up.x, up.y, up.z).scale(size / 2);
		int seg = 16;
		Vec3[] rim = new Vec3[seg + 1];
		for (int i = 0; i <= seg; i++) {
			double a = i * 2 * Math.PI / seg;
			rim[i] = r.scale(Math.cos(a)).add(u.scale(Math.sin(a)));
		}
		return rim;
	}

	/** A vertical glass panel: centre, facing yaw (degrees, Minecraft convention), width × height. */
	void panel(Vec3 c, float yawDeg, double w, double h, int fill, int border, double borderWidth) {
		Vec3[] k = panelCorners(c, yawDeg, w, h);
		glass((m, b, tmp) -> quad(m, b, tmp, k[0], k[1], k[2], k[3], fill));
		if (border != 0) {
			List<Vec3> edge = List.of(k[0], k[1], k[2], k[3], k[0]);
			ribbon(edge, borderWidth, border, 1);
		}
	}

	/** Corners (bottom-left, bottom-right, top-right, top-left as seen from the front) of a panel. */
	static Vec3[] panelCorners(Vec3 c, float yawDeg, double w, double h) {
		double yr = Math.toRadians(yawDeg);
		Vec3 right = new Vec3(Math.cos(yr), 0, Math.sin(yr)).scale(w / 2);   // the viewer's right, facing the panel
		Vec3 up = new Vec3(0, h / 2, 0);
		return new Vec3[] {c.subtract(right).subtract(up), c.add(right).subtract(up), c.add(right).add(up), c.subtract(right).add(up)};
	}

	// ---- outlines, text, items, ghost blocks ------------------------------------------------------------------------

	void outline(BlockPos pos, int color, float width) {
		outline(new AABB(pos), color, width);
	}

	void outline(AABB box, int color, float width) {
		poses.pushPose();
		poses.translate(box.minX - cam.x, box.minY - cam.y, box.minZ - cam.z);
		out.submitShapeOutline(poses, Shapes.create(0, 0, 0, box.getXsize(), box.getYsize(), box.getZsize()), RenderTypes.lines(), color, width, false);
		poses.popPose();
	}

	/**
	 * Text in the world, Minecraft's font. billboard: faces the camera; otherwise faces `yawDeg`. scale: blocks per
	 * font pixel. Returns nothing; centred on `pos` when centred.
	 */
	void text(Vec3 pos, Component text, float scale, int color, int background, boolean billboard, float yawDeg, boolean centred) {
		Font font = mc.font;
		poses.pushPose();
		poses.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
		if (billboard) {
			poses.mulPose(new Matrix4f().rotation(camRot));
		} else {
			poses.mulPose(new Matrix4f().rotationY((float) Math.toRadians(-yawDeg)));   // unrotated text faces +Z (yaw 0)
		}
		poses.scale(scale, -scale, scale);
		float x = centred ? -font.width(text) / 2f : 0;
		out.submitText(poses, x, -font.lineHeight / 2f, text.getVisualOrderText(), false, Font.DisplayMode.POLYGON_OFFSET, LightCoordsUtil.FULL_BRIGHT,
				color, background, 0);
		poses.popPose();
	}

	private final ItemStackRenderState itemState = new ItemStackRenderState();

	/** An item floating in the world: size in blocks, turned `spinDeg` about Y (or facing the camera). */
	void item(Vec3 pos, ItemStack stack, double size, float spinDeg, boolean billboard) {
		item(pos, stack, size, billboard ? null : new Matrix4f().rotationY((float) Math.toRadians(spinDeg)), ItemDisplayContext.FIXED);
	}

	/** An item as the inventory shows it (blocks as little 3D cubes), facing the camera: for icons on panels. */
	void guiItem(Vec3 pos, ItemStack stack, double size) {
		item(pos, stack, size, null, ItemDisplayContext.GUI);
	}

	/** turn: the item's orientation, or null to face the camera. */
	private void item(Vec3 pos, ItemStack stack, double size, Matrix4f turn, ItemDisplayContext context) {
		if (stack.isEmpty() || size <= 0.001) return;
		mc.getItemModelResolver().updateForTopItem(itemState, stack, context, mc.level, null, 0);
		poses.pushPose();
		poses.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
		poses.mulPose(turn != null ? turn : new Matrix4f().rotation(camRot).rotateY((float) Math.PI));
		poses.scale((float) size * 2, (float) size * 2, (float) size * 2);
		itemState.submit(poses, out, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
		poses.popPose();
	}

	/** A block model's quads with their tint colours; faces[i] is the side quad i is culled against (null = never). */
	record Model(BakedQuad[] quads, int[] tints, Direction[] faces) {}

	/** One block of a ghost build: the sides in `hidden` (bit per Direction ordinal) touch the build's own solid blocks. */
	record GhostBlock(BlockPos pos, Model model, int hidden) {}

	private static final Map<BlockState, Model> MODELS = new HashMap<>();

	/** A block model drawn see-through (a schematic ghost, a flying block), alpha 0..255; corner = its min corner. */
	void ghost(Vec3 corner, BlockState state, int alpha, double scale) {
		Model model = model(state);
		if (model.quads.length == 0 || alpha <= 0) return;
		out.submitCustomGeometry(poses, RenderTypes.translucentMovingBlock(), (pose, buffer) -> {
			Vector3f p = new Vector3f();
			double c = (1 - scale) / 2;
			emit(pose.pose(), buffer, p, model, 0, corner.x + c, corner.y + c, corner.z + c, scale, alpha);
		});
	}

	/** Many ghost blocks in one batch (blocks[from..to)), like Litematica's schematic overlay. */
	void ghosts(List<GhostBlock> blocks, int from, int to, int alpha) {
		if (from >= to || alpha <= 0) return;
		out.submitCustomGeometry(poses, RenderTypes.translucentMovingBlock(), (pose, buffer) -> {
			Matrix4f m = pose.pose();
			Vector3f p = new Vector3f();
			for (int i = from; i < to; i++) {
				GhostBlock g = blocks.get(i);
				emit(m, buffer, p, g.model, g.hidden, g.pos.getX(), g.pos.getY(), g.pos.getZ(), 1, alpha);
			}
		});
	}

	private void emit(Matrix4f m, VertexConsumer buffer, Vector3f p, Model model, int hidden, double x, double y, double z, double scale, int alpha) {
		for (int q = 0; q < model.quads.length; q++) {
			Direction face = model.faces[q];
			if (face != null && (hidden & (1 << face.ordinal())) != 0) continue;
			BakedQuad quad = model.quads[q];
			int color = argb(alpha, model.tints[q]);
			for (int k = 0; k < 4; k++) {
				Vector3fc local = quad.position(k);
				long uv = quad.packedUV(k);
				m.transformPosition((float) (x + local.x() * scale - cam.x), (float) (y + local.y() * scale - cam.y), (float) (z + local.z() * scale - cam.z), p);
				buffer.addVertex(p.x, p.y, p.z).setColor(color).setUv(UVPair.unpackU(uv), UVPair.unpackV(uv)).setLight(LightCoordsUtil.FULL_BRIGHT);
			}
		}
	}

	static Model model(BlockState state) {
		return MODELS.computeIfAbsent(state, s -> {
			List<BakedQuad> quads = new ArrayList<>();
			List<Integer> tints = new ArrayList<>();
			List<Direction> faces = new ArrayList<>();
			if (s.getRenderShape() == RenderShape.MODEL) {
				Minecraft mc = Minecraft.getInstance();
				BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(s);
				List<BlockStateModelPart> parts = new ArrayList<>();
				model.collectParts(RandomSource.create(42L), parts);
				for (BlockStateModelPart part : parts) {
					add(mc, s, part.getQuads(null), null, quads, tints, faces);
					for (Direction d : Direction.values()) add(mc, s, part.getQuads(d), d, quads, tints, faces);
				}
			}
			return new Model(quads.toArray(BakedQuad[]::new), tints.stream().mapToInt(Integer::intValue).toArray(), faces.toArray(Direction[]::new));
		});
	}

	private static void add(Minecraft mc, BlockState state, List<BakedQuad> from, Direction face, List<BakedQuad> quads, List<Integer> tints,
			List<Direction> faces) {
		for (BakedQuad q : from) {
			int rgb = 0xFFFFFF;
			int tint = q.materialInfo().tintIndex();
			if (tint >= 0) {
				BlockTintSource source = mc.getBlockColors().getTintSource(state, tint);
				if (source != null) rgb = source.color(state) & 0xFFFFFF;
			}
			quads.add(q);
			tints.add(rgb);
			faces.add(face);
		}
	}
}
