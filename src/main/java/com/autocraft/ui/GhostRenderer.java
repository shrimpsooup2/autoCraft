package com.autocraft.ui;

import com.autocraft.Bot;
import com.autocraft.build.BuildPlan;
import com.autocraft.build.Placement;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Draws a schematic into the world like Litematica: translucent block models for blocks still to place, red
 * outlines where a different block is in the way, colored outlines for fluids and block-entity blocks, and the
 * bounding box. Shows the placement being positioned, otherwise the structure the bot is building.
 */
public final class GhostRenderer {
	private static final int ALPHA = 0xA0;
	private static final int REFRESH_TICKS = 10;
	private static final int MAX_BLOCKS = 16_000;
	private static final int MAX_OUTLINES = 384;
	private static final double MAX_DISTANCE = 160;
	private static final int PLACING_BOX = 0xFF7FE0FF;
	private static final int BUILDING_BOX = 0xFF66E07A;
	private static final int WRONG = 0xFFFF4040;
	private static final int WATER = 0xFF3F76E4;
	private static final int LAVA = 0xFFFF8A1F;
	private static final int ENTITY_BLOCK = 0xFFC8C8C8;

	private record Outline(BlockPos pos, int color) {}

	private static @Nullable Mesh mesh;
	private static @Nullable Object meshKey;
	private static BlockPos offset = BlockPos.ZERO;
	private static int[] visible = new int[0];
	private static int visibleCount;
	private static final List<Outline> outlines = new ArrayList<>();
	private static @Nullable BoundingBox box;
	private static int boxColor;
	private static int sinceRefresh;

	private GhostRenderer() {}

	/** Client tick: pick what to show, rebuild geometry if it changed, re-check the world every half second. */
	public static void tick(Minecraft mc) {
		Placement placing = Placements.active();
		BuildPlan building = Bot.get().buildPlan();
		if (mc.level == null || mc.player == null || placing == null && building == null) {
			mesh = null;
			meshKey = null;
			box = null;
			visibleCount = 0;
			outlines.clear();
			return;
		}
		BuildPlan plan = placing != null ? placing.localPlan() : building;
		BlockPos newOffset = placing != null ? placing.origin() : BlockPos.ZERO; // a build plan is already in world coordinates
		boolean dirty = false;
		if (plan != meshKey) {
			mesh = Mesh.build(mc, plan);
			meshKey = plan;
			dirty = true;
		}
		if (!newOffset.equals(offset)) {
			offset = newOffset;
			dirty = true;
		}
		box = placing != null ? placing.box() : building.box();
		boxColor = placing != null ? PLACING_BOX : BUILDING_BOX;
		if (dirty || ++sinceRefresh >= REFRESH_TICKS) {
			sinceRefresh = 0;
			refresh(mc.level, mc.player.position());
		}
	}

	/** Which blocks still need a ghost (not already right in the world), nearest first; what's in the way. */
	private static void refresh(ClientLevel level, Vec3 eye) {
		Mesh m = mesh;
		outlines.clear();
		if (m == null) return;
		double maxSq = MAX_DISTANCE * MAX_DISTANCE;
		List<int[]> candidates = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < m.count; i++) {
			pos.set(m.x[i] + offset.getX(), m.y[i] + offset.getY(), m.z[i] + offset.getZ());
			double d = pos.distToCenterSqr(eye);
			if (d > maxSq) continue;
			BlockState world = level.getBlockState(pos);
			if (m.tasks[i].satisfiedBy(world)) continue;
			if (!world.isAir() && !world.canBeReplaced()) {
				outline(pos, WRONG);
				continue;
			}
			if (m.outlineColor[i] != 0) outline(pos, m.outlineColor[i]);
			else candidates.add(new int[] {i, (int) d});
		}
		for (BuildPlan.Task clear : m.clears) {
			pos.set(clear.pos().getX() + offset.getX(), clear.pos().getY() + offset.getY(), clear.pos().getZ() + offset.getZ());
			if (pos.distToCenterSqr(eye) > maxSq) continue;
			BlockState world = level.getBlockState(pos);
			if (!world.isAir() && !world.canBeReplaced()) outline(pos, WRONG);
		}
		candidates.sort((a, b) -> Integer.compare(a[1], b[1]));
		int n = Math.min(candidates.size(), MAX_BLOCKS);
		int[] vis = new int[n];
		for (int k = 0; k < n; k++) vis[k] = candidates.get(k)[0];
		visible = vis;
		visibleCount = n;
	}

	private static void outline(BlockPos pos, int color) {
		if (outlines.size() < MAX_OUTLINES) outlines.add(new Outline(pos.immutable(), color));
	}

	/** Level render: submit the ghost geometry, outlines and the bounding box. */
	public static void collect(LevelRenderContext ctx) {
		Mesh m = mesh;
		BoundingBox b = box;
		if (m == null || b == null) return;
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		PoseStack poses = ctx.poseStack();
		SubmitNodeCollector out = ctx.submitNodeCollector();
		if (visibleCount > 0) {
			int[] vis = visible;
			int count = visibleCount;
			BlockPos off = offset;
			out.submitCustomGeometry(poses, RenderTypes.translucentMovingBlock(), (pose, buffer) -> m.emit(pose.pose(), buffer, vis, count, off, cam));
		}
		for (Outline o : outlines) {
			poses.pushPose();
			poses.translate(o.pos.getX() - cam.x, o.pos.getY() - cam.y, o.pos.getZ() - cam.z);
			out.submitShapeOutline(poses, Shapes.block(), RenderTypes.lines(), o.color, 2.0F, false);
			poses.popPose();
		}
		poses.pushPose();
		poses.translate(b.minX() - cam.x, b.minY() - cam.y, b.minZ() - cam.z);
		out.submitShapeOutline(poses, Shapes.create(0, 0, 0, b.getXSpan(), b.getYSpan(), b.getZSpan()), RenderTypes.lines(), boxColor, 3.0F, false);
		poses.popPose();
	}

	/** Block-model quads of a plan, relative to its origin, with faces hidden behind other schematic blocks culled. */
	private static final class Mesh {
		final int count;
		final int[] x;
		final int[] y;
		final int[] z;
		final BuildPlan.Task[] tasks;
		/** Non-zero: drawn as a colored outline instead of a model (fluids, chests and other block-entity blocks). */
		final int[] outlineColor;
		/** Quads of block {@code i} are {@code quads[quadStart[i] .. quadStart[i+1])}. */
		final int[] quadStart;
		final BakedQuad[] quads;
		final int[] colors;
		final List<BuildPlan.Task> clears;

		private Mesh(List<BuildPlan.Task> places, List<BuildPlan.Task> clears, List<BakedQuad> quadList, List<Integer> colorList, int[] quadStart,
				int[] outlineColor) {
			this.count = places.size();
			this.x = new int[count];
			this.y = new int[count];
			this.z = new int[count];
			this.tasks = new BuildPlan.Task[count];
			for (int i = 0; i < count; i++) {
				BuildPlan.Task t = places.get(i);
				x[i] = t.pos().getX();
				y[i] = t.pos().getY();
				z[i] = t.pos().getZ();
				tasks[i] = t;
			}
			this.quads = quadList.toArray(new BakedQuad[0]);
			this.colors = colorList.stream().mapToInt(Integer::intValue).toArray();
			this.quadStart = quadStart;
			this.outlineColor = outlineColor;
			this.clears = clears;
		}

		static Mesh build(Minecraft mc, BuildPlan plan) {
			List<BuildPlan.Task> places = plan.places();
			Map<BlockPos, BlockState> at = new HashMap<>();
			for (BuildPlan.Task t : places) at.put(t.pos(), t.state());
			List<BakedQuad> quads = new ArrayList<>();
			List<Integer> colors = new ArrayList<>();
			int[] start = new int[places.size() + 1];
			int[] outlineColor = new int[places.size()];
			RandomSource random = RandomSource.create(42L);
			List<BlockStateModelPart> parts = new ArrayList<>();
			for (int i = 0; i < places.size(); i++) {
				start[i] = quads.size();
				BuildPlan.Task t = places.get(i);
				BlockState state = t.state();
				if (state.is(Blocks.WATER)) {
					outlineColor[i] = WATER;
					continue;
				}
				if (state.is(Blocks.LAVA)) {
					outlineColor[i] = LAVA;
					continue;
				}
				if (state.getRenderShape() != RenderShape.MODEL) {
					outlineColor[i] = ENTITY_BLOCK;
					continue;
				}
				BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
				parts.clear();
				random.setSeed(42L);
				model.collectParts(random, parts);
				for (BlockStateModelPart part : parts) {
					addQuads(mc, state, part.getQuads(null), quads, colors);
					for (Direction d : Direction.values()) {
						BlockState neighbor = at.get(t.pos().relative(d));
						if (neighbor != null && neighbor.isSolidRender()) continue; // hidden inside the structure
						addQuads(mc, state, part.getQuads(d), quads, colors);
					}
				}
				if (quads.size() == start[i]) outlineColor[i] = ENTITY_BLOCK;
			}
			start[places.size()] = quads.size();
			return new Mesh(places, plan.clears(), quads, colors, start, outlineColor);
		}

		private static void addQuads(Minecraft mc, BlockState state, List<BakedQuad> from, List<BakedQuad> quads, List<Integer> colors) {
			for (BakedQuad q : from) {
				int rgb = 0xFFFFFF;
				int tint = q.materialInfo().tintIndex();
				if (tint >= 0) {
					BlockTintSource source = mc.getBlockColors().getTintSource(state, tint);
					if (source != null) rgb = source.color(state) & 0xFFFFFF;
				}
				quads.add(q);
				colors.add(ALPHA << 24 | rgb);
			}
		}

		/** Called while the frame renders: write the visible blocks' quads, camera-relative. */
		void emit(Matrix4f pose, VertexConsumer buffer, int[] vis, int n, BlockPos off, Vec3 cam) {
			Vector3f p = new Vector3f();
			Vector3f normal = new Vector3f();
			for (int k = 0; k < n; k++) {
				int b = vis[k];
				float bx = (float) (x[b] + off.getX() - cam.x);
				float by = (float) (y[b] + off.getY() - cam.y);
				float bz = (float) (z[b] + off.getZ() - cam.z);
				for (int q = quadStart[b]; q < quadStart[b + 1]; q++) {
					BakedQuad quad = quads[q];
					int color = colors[q];
					Direction facing = quad.direction();
					pose.transformDirection(facing.getStepX(), facing.getStepY(), facing.getStepZ(), normal).normalize();
					for (int v = 0; v < 4; v++) {
						Vector3fc local = quad.position(v);
						long uv = quad.packedUV(v);
						pose.transformPosition(bx + local.x(), by + local.y(), bz + local.z(), p);
						// every element a format might carry: shader packs add a normal and overlay to the block
						// format, and a vertex missing one crashes the frame
						buffer.addVertex(p.x, p.y, p.z, color, UVPair.unpackU(uv), UVPair.unpackV(uv), OverlayTexture.NO_OVERLAY,
								LightCoordsUtil.FULL_BRIGHT, normal.x, normal.y, normal.z);
					}
				}
			}
		}
	}
}
