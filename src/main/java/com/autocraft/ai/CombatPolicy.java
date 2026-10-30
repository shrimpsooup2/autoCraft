package com.autocraft.ai;

import com.autocraft.Chat;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * The fighting policy trained in the combat simulator (tools/combatsim): a small MLP that, every tick of a melee
 * fight, picks how to move relative to the target, and whether to sprint, jump, swing or hold the shield up. Its
 * input is {@link CombatObs} — built from the game exactly as the simulator builds it. Bundled with the mod as
 * {@code assets/autocraft/combat_policy.json}; absent (not trained yet) means the hand-written fight is used.
 */
public final class CombatPolicy {
	private static final String RESOURCE = "/assets/autocraft/combat_policy.json";
	/** Dev runs (run directory beside the source tree): the trainer exports here as it improves; picked up live. */
	private static final Path DEV_SOURCE = Path.of("..", "src", "main", "resources", "assets", "autocraft", "combat_policy.json");
	private static final long DEV_CHECK_MS = 30_000;
	private static final Gson GSON = new Gson();
	private static @Nullable CombatPolicy instance;
	private static boolean loaded;
	private static long devCheckedAt;
	private static long devModified = -1;

	/** One tick's choice. {@code forward}/{@code right}: -1, 0 or 1 relative to facing the target. */
	public record Action(int forward, int right, boolean sprint, boolean jump, boolean attack, boolean shield) {}

	private record Layer(double[][] w, double[] b) {}

	private record Doc(int version, int obs, int hidden, int outs, int[][] moves, Layer[] layers, int generation) {}

	private final Layer[] layers;
	private final int[][] moves;
	private final int generation;

	private CombatPolicy(Layer[] layers, int[][] moves, int generation) {
		this.layers = layers;
		this.moves = moves;
		this.generation = generation;
	}

	/** The bundled policy (in a dev run, the trainer's latest export), or null when there is none or it doesn't fit. */
	public static @Nullable CombatPolicy get() {
		if (!loaded) {
			loaded = true;
			try (InputStream in = CombatPolicy.class.getResourceAsStream(RESOURCE)) {
				instance = in == null ? null : parse(in);
			} catch (IOException e) {
				Chat.LOG.warn("Could not read combat_policy.json; using the hand-written fight", e);
			}
		}
		if (FabricLoader.getInstance().isDevelopmentEnvironment()) reloadExport();
		return instance;
	}

	/** The trainer exported a newer policy (a better generation): fight with it from now on, no restart needed. */
	private static void reloadExport() {
		long now = System.currentTimeMillis();
		if (now - devCheckedAt < DEV_CHECK_MS) return;
		devCheckedAt = now;
		try {
			if (!Files.exists(DEV_SOURCE)) return;
			long modified = Files.getLastModifiedTime(DEV_SOURCE).toMillis();
			if (modified == devModified) return;
			devModified = modified;
			CombatPolicy fresh;
			try (InputStream in = Files.newInputStream(DEV_SOURCE)) {
				fresh = parse(in);
			}
			if (fresh != null && (instance == null || fresh.generation != instance.generation)) instance = fresh;
		} catch (IOException | RuntimeException e) { // mid-write by the trainer: the next check reads it whole
			devModified = -1;
		}
	}

	private static @Nullable CombatPolicy parse(InputStream in) {
		try {
			Doc d = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Doc.class);
			int outs = d.moves.length + 4;
			if (d.version != 1 || d.obs != CombatObs.SIZE || d.outs != outs || d.layers.length != 3 || d.layers[0].w[0].length != CombatObs.SIZE
					|| d.layers[2].w.length != outs) {
				Chat.LOG.warn("combat_policy.json doesn't fit this version (obs {} vs {}); using the hand-written fight", d.obs, CombatObs.SIZE);
				return null;
			}
			Chat.LOG.info("Loaded the trained combat policy (simulator generation {})", d.generation);
			return new CombatPolicy(d.layers, d.moves, d.generation);
		} catch (RuntimeException e) { // a malformed document
			Chat.LOG.warn("Could not read combat_policy.json; using the hand-written fight", e);
			return null;
		}
	}

	public int generation() {
		return generation;
	}

	public Action act(double[] obs) {
		double[] h = obs;
		for (int l = 0; l < layers.length; l++) {
			Layer layer = layers[l];
			double[] out = new double[layer.b.length];
			for (int j = 0; j < out.length; j++) {
				double a = layer.b[j];
				double[] row = layer.w[j];
				for (int k = 0; k < row.length; k++) a += row[k] * h[k];
				out[j] = l < layers.length - 1 ? Math.tanh(a) : a;
			}
			h = out;
		}
		int m = 0;
		for (int i = 1; i < moves.length; i++) {
			if (h[i] > h[m]) m = i;
		}
		int n = moves.length;
		return new Action(moves[m][0], moves[m][1], h[n] > 0, h[n + 1] > 0, h[n + 2] > 0, h[n + 3] > 0);
	}
}
