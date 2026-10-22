package com.autocraft.ai;

import com.autocraft.Chat;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Tiny multilayer perceptron (inputs → tanh hidden → sigmoid) answering "is fighting this a good idea?".
 * First run: bootstrapped from a hand-written teacher on synthetic situations so it starts sensible.
 * After that it learns online from real fight outcomes and is saved to {@code config/autocraft/threatnet.json}.
 */
public final class ThreatNet {
	public static final int INPUTS = Features.COUNT;
	private static final int HIDDEN = 12;
	private static final String FILE_NAME = "threatnet.json";
	private static final Gson GSON = new Gson();

	/**
	 * Learning rate per real fight. Tuned so ~10 consistent outcomes in the same situation flip the
	 * decision (0.88 → 0.40) while one unlucky fight barely moves it.
	 */
	private static final double ONLINE_RATE = 0.006;

	private record Weights(double[][] w1, double[] b1, double[] w2, double b2, int fights) {}

	private final double[][] w1;
	private final double[] b1;
	private final double[] w2;
	private double b2;
	private int fights;
	private boolean dirty;

	private ThreatNet(double[][] w1, double[] b1, double[] w2, double b2, int fights) {
		this.w1 = w1;
		this.b1 = b1;
		this.w2 = w2;
		this.b2 = b2;
		this.fights = fights;
	}

	public static ThreatNet loadOrTrain() {
		Path file = file();
		try {
			if (Files.exists(file)) {
				Weights w = GSON.fromJson(Files.readString(file), Weights.class);
				if (w != null && w.w1 != null && w.w1.length == HIDDEN && w.w1[0].length == INPUTS && w.w2 != null && w.w2.length == HIDDEN) {
					return new ThreatNet(w.w1, w.b1, w.w2, w.b2, w.fights);
				}
				Chat.LOG.warn("threatnet.json has the wrong shape; retraining");
			}
		} catch (IOException | JsonParseException e) {
			Chat.LOG.warn("Could not read threatnet.json; retraining", e);
		}
		ThreatNet net = randomInit(new Random(42));
		long start = System.nanoTime();
		net.bootstrap(new Random(7));
		Chat.LOG.info("ThreatNet bootstrapped in {} ms", (System.nanoTime() - start) / 1_000_000);
		net.dirty = true;
		net.save();
		return net;
	}

	private static ThreatNet randomInit(Random r) {
		double[][] w1 = new double[HIDDEN][INPUTS];
		double[] w2 = new double[HIDDEN];
		double scale1 = 1 / Math.sqrt(INPUTS), scale2 = 1 / Math.sqrt(HIDDEN);
		for (int j = 0; j < HIDDEN; j++) {
			for (int k = 0; k < INPUTS; k++) w1[j][k] = r.nextGaussian() * scale1;
			w2[j] = r.nextGaussian() * scale2;
		}
		return new ThreatNet(w1, new double[HIDDEN], w2, 0, 0);
	}

	/** Probability that fighting goes well. */
	public double predict(double[] x) {
		double z = b2;
		for (int j = 0; j < HIDDEN; j++) z += w2[j] * hidden(j, x);
		return sigmoid(z);
	}

	/** One engagement's outcome: 1 = the fight went well, 0 = it didn't. A nudge, not a rewrite. */
	public void learnOutcome(double[] x, double label) {
		step(x, label, ONLINE_RATE);
		fights++;
		dirty = true;
		Chat.LOG.info("ThreatNet learned outcome {} (fights learned: {})", label, fights);
	}

	public int fightsLearned() {
		return fights;
	}

	/** One SGD step on binary cross-entropy. */
	private void step(double[] x, double target, double lr) {
		double[] h = new double[HIDDEN];
		double z = b2;
		for (int j = 0; j < HIDDEN; j++) {
			h[j] = hidden(j, x);
			z += w2[j] * h[j];
		}
		double err = sigmoid(z) - target;
		for (int j = 0; j < HIDDEN; j++) {
			double dh = err * w2[j] * (1 - h[j] * h[j]);
			w2[j] -= lr * err * h[j];
			for (int k = 0; k < INPUTS; k++) w1[j][k] -= lr * dh * x[k];
			b1[j] -= lr * dh;
		}
		b2 -= lr * err;
	}

	private double hidden(int j, double[] x) {
		double a = b1[j];
		for (int k = 0; k < INPUTS; k++) a += w1[j][k] * x[k];
		return Math.tanh(a);
	}

	private void bootstrap(Random r) {
		int n = 5000;
		double[][] xs = new double[n][];
		double[] ys = new double[n];
		for (int i = 0; i < n; i++) {
			xs[i] = Features.randomSituation(r);
			ys[i] = Features.teacher(xs[i]);
		}
		for (int epoch = 0; epoch < 30; epoch++) {
			for (int i = 0; i < n; i++) {
				int k = r.nextInt(n);
				step(xs[k], ys[k], 0.05);
			}
		}
	}

	public void save() {
		if (!dirty) return;
		try {
			Path file = file();
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(new Weights(w1, b1, w2, b2, fights)));
			dirty = false;
		} catch (IOException e) {
			Chat.LOG.warn("Could not save threatnet.json", e);
		}
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve(FILE_NAME);
	}

	private static double sigmoid(double z) {
		return 1 / (1 + Math.exp(-z));
	}
}
