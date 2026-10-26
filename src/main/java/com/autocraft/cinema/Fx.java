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

	// ---- glowing geometry (additive) ------------------------------------------------------------------------------

	private interface Quads {
		void emit(Matrix4f m, VertexConsumer b, Vector3f tmp);
	}

	private void glow(Quads q) {
		out.submitCustomGeometry(poses, RenderTypes.lightning(), (pose, buffer) -> q.emit(pose.pose(), buffer, new Vector3f()));
	}

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
		v(m, b, tmp, p0, c0);
		v(m, b, tmp, p1, c1);
		v(m, b, tmp, p2, c2);
		v(m, b, tmp, p3, c3);
		v(m, b, tmp, p3, c3);
		v(m, b, tmp, p2, c2);
		v(m, b, tmp, p1, c1);
		v(m, b, tmp, p0, c0);
	}

	/**
	 * A glowing strip along points, turned to face the camera, with a soft halo fading out on both sides;
	 * grow 0..1 draws it from the start.
	 */
	void ribbon(List<Vec3> pts, double width, int color, double grow) {
		if (pts.size() < 2 || grow <= 0) return;
		List<Vec3> path = cut(pts, Anim.clamp01(grow));
		int halo = fade(color, 0.35), clear = color & 0xFFFFFF;
		glow((m, b, tmp) -> {
			for (int i = 0; i < path.size() - 1; i++) {
				Vec3 p0 = path.get(i), p1 = path.get(i + 1);
				Vec3 side = p1.subtract(p0).cross(cam.subtract(p0)).normalize();
				Vec3 in = side.scale(width / 2), out = side.scale(width * 2);
				quad2(m, b, tmp, p0.subtract(in), p0.add(in), p1.add(in), p1.subtract(in), color, color, color, color);
				quad2(m, b, tmp, p0.add(in), p0.add(out), p1.add(out), p1.add(in), halo, clear, clear, halo);
				quad2(m, b, tmp, p0.subtract(out), p0.subtract(in), p1.subtract(in), p1.subtract(out), clear, halo, halo, clear);
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
		int halo = fade(color, 0.35), clear = color & 0xFFFFFF;
		double[] r = {Math.max(0, radius - thickness * 2), radius - thickness / 2, radius + thickness / 2, radius + thickness * 2};
		int[] col = {clear, halo, halo, clear};
		glow((m, b, tmp) -> {
			for (int i = 0; i < n; i++) {
				double a0 = i * 2 * Math.PI / seg, a1 = (i + 1) * 2 * Math.PI / seg;
				double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
				for (int k = 0; k < 3; k++) {
					int ci = k == 1 ? color : col[k], co = k == 1 ? color : col[k + 1];
					quad2(m, b, tmp, c.add(c0 * r[k], 0, s0 * r[k]), c.add(c0 * r[k + 1], 0, s0 * r[k + 1]), c.add(c1 * r[k + 1], 0, s1 * r[k + 1]),
							c.add(c1 * r[k], 0, s1 * r[k]), ci, co, co, ci);
				}
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

	/** A soft round glow facing the camera (a light, a spark, a glint): bright in the middle, fading to nothing. */
	void spark(Vec3 c, double size, int color) {
		Vector3f right = new Vector3f(1, 0, 0).rotate(camRot), up = new Vector3f(0, 1, 0).rotate(camRot);
		Vec3 r = new Vec3(right.x, right.y, right.z).scale(size / 2), u = new Vec3(up.x, up.y, up.z).scale(size / 2);
		int clear = color & 0xFFFFFF;
		glow((m, b, tmp) -> {
			int seg = 16;
			for (int i = 0; i < seg; i++) {
				double a0 = i * 2 * Math.PI / seg, a1 = (i + 1) * 2 * Math.PI / seg;
				Vec3 e0 = c.add(r.scale(Math.cos(a0))).add(u.scale(Math.sin(a0))), e1 = c.add(r.scale(Math.cos(a1))).add(u.scale(Math.sin(a1)));
				quad2(m, b, tmp, c, e0, e1, c, color, clear, clear, color);
			}
		});
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
		if (stack.isEmpty() || size <= 0.001) return;
		mc.getItemModelResolver().updateForTopItem(itemState, stack, ItemDisplayContext.FIXED, mc.level, null, 0);
		poses.pushPose();
		poses.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
		if (billboard) {
			poses.mulPose(new Matrix4f().rotation(camRot).rotateY((float) Math.PI));
		} else {
			poses.mulPose(new Matrix4f().rotationY((float) Math.toRadians(spinDeg)));
		}
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
