package com.autocraft;

import com.autocraft.build.BuildOrder;
import com.autocraft.build.Substitution;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.goal.Resources;
import com.autocraft.schematic.Schematic;
import com.autocraft.schematic.SchematicLibrary;
import com.autocraft.world.WorldKey;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.jspecify.annotations.Nullable;

/**
 * The task in progress — job (with deposited counts and skipped items), build order, death recovery — per world,
 * saved to {@code config/autocraft/tasks.json} so it picks up again after a relog or a crash. Build progress needs
 * no saving (the builder reads the world) and bases have their own file ({@link Bases}). Saved when work starts
 * or stops and every few seconds while running; restored a few seconds after joining.
 */
public final class TaskSave {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long RESTORE_DELAY_MS = 5000;

	private record Line(String name, int amount) {}

	private record SavedBuild(String schematic, int x, int y, int z, Rotation rotation, Mirror mirror, String substitution, BuildOrder.Purpose purpose) {}

	private record Spot(int x, int y, int z, Dim dim) {
		BlockPos pos() {
			return new BlockPos(x, y, z);
		}

		static @Nullable Spot of(@Nullable BlockPos pos, Dim dim) {
			return pos == null ? null : new Spot(pos.getX(), pos.getY(), pos.getZ(), dim);
		}
	}

	private record State(List<Line> job, Map<String, Integer> stored, List<String> skipped, @Nullable SavedBuild build, @Nullable Spot recovery,
			boolean running) {}

	private static @Nullable Map<String, State> all;
	private static long restoreAt = -1;

	private TaskSave() {}

	/** Remember what the bot is doing (and whether it should resume) for this world. */
	public static void save(Bot bot, boolean running) {
		Job job = bot.job();
		List<Line> lines = new ArrayList<>();
		Map<String, Integer> stored = Map.of();
		List<String> skipped = List.of();
		if (job != null) {
			for (Requirement r : job.requirements()) lines.add(new Line(r.name(), r.amount()));
			stored = job.stored();
			skipped = List.copyOf(job.skippedNames());
		}
		BuildOrder b = bot.buildOrder();
		SavedBuild build = b == null ? null : new SavedBuild(b.schematic().name(), b.origin().getX(), b.origin().getY(), b.origin().getZ(),
				b.rotation(), b.mirror(), b.substitution().encode(), b.purpose());
		Bot.Recovery r = bot.recovery();
		State state = new State(lines, stored, skipped, build, r == null ? null : Spot.of(r.pos(), r.dim()), running && (job != null || build != null));
		states().put(WorldKey.current(), state);
		write();
	}

	/** Joined a world: restore a little later, once chunks are in. */
	public static void onJoin() {
		restoreAt = System.currentTimeMillis() + RESTORE_DELAY_MS;
	}

	/** Just joined: the saved task hasn't been restored yet. */
	public static boolean restorePending() {
		return restoreAt >= 0;
	}

	/** Every client tick: restore the saved task when it's time. */
	public static void tick(Minecraft mc) {
		if (restoreAt < 0 || System.currentTimeMillis() < restoreAt || mc.player == null || mc.level == null) return;
		restoreAt = -1;
		State s = states().get(WorldKey.current());
		if (s == null) return;
		Bot bot = Bot.get();
		if (!s.running || !Settings.get().resumeAfterRelog || bot.isRunning()) return;
		List<Requirement> reqs = new ArrayList<>();
		for (Line l : s.job) {
			Requirement r = Resources.forJob(l.name, l.amount);
			if (r != null) reqs.add(r);
		}
		if (!reqs.isEmpty()) {
			Job job = new Job(reqs);
			job.restore(s.stored, s.skipped);
			bot.start(job);
		}
		if (s.build != null) {
			Schematic schematic = SchematicLibrary.get(s.build.schematic);
			if (schematic == null) {
				Chat.warn("Can't resume the build: schematic " + s.build.schematic + " is gone.");
			} else {
				bot.startBuild(new BuildOrder(schematic, new BlockPos(s.build.x, s.build.y, s.build.z), s.build.rotation, s.build.mirror,
						Substitution.decode(s.build.substitution), s.build.purpose));
			}
		}
		if (s.recovery != null) bot.setRecovery(new Bot.Recovery(s.recovery.pos(), s.recovery.dim));
		Chat.info("Resumed where we left off (turn this off under Settings).");
	}

	private static Map<String, State> states() {
		if (all == null) all = load();
		return all;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve("tasks.json");
	}

	private static Map<String, State> load() {
		try {
			if (Files.exists(file())) {
				Map<String, State> loaded = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, State>>() {}.getType());
				if (loaded != null) return new HashMap<>(loaded);
			}
		} catch (IOException | JsonParseException e) {
			Chat.LOG.warn("Could not read tasks.json", e);
		}
		return new HashMap<>();
	}

	private static void write() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(all));
		} catch (IOException e) {
			Chat.LOG.warn("Could not save tasks.json", e);
		}
	}
}
