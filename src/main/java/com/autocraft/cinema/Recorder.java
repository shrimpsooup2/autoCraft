package com.autocraft.cinema;

import com.autocraft.Chat;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.Nullable;

/**
 * Records the film itself, frame by frame, at an exact frame rate however fast the computer is: while it records,
 * the game clock moves 1/fps of a second per captured frame (the partial tick and the client ticks come from the
 * frame number, not the wall clock), so nothing stutters and the video runs at true speed. Each frame is read back
 * from the screen and piped into ffmpeg (an MP4 in {@code autocraft-film/}); without ffmpeg the frames are saved as
 * PNGs to import into any video editor. If saving falls behind, the clock waits for it.
 */
public final class Recorder {
	private static final int MAX_PENDING = 6;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	/** One step of the film clock: client ticks to run this frame, and the partial tick to draw it at. */
	public record Step(int ticks, float residual, float delta) {}

	private static int fps;
	private static boolean armed;
	private static boolean driving;
	private static boolean closing;
	private static boolean captureNow;
	private static long frame;
	private static long wholeTicks;
	private static float residual;
	private static int width;
	private static int height;
	private static final AtomicInteger pending = new AtomicInteger();
	private static @Nullable ExecutorService writer;
	private static @Nullable Process ffmpeg;
	private static @Nullable WritableByteChannel pipe;
	private static @Nullable Path output;
	private static volatile @Nullable String failure;

	private Recorder() {}

	/** The next film plays into a recording at this frame rate. */
	static void arm(int framesPerSecond) {
		fps = framesPerSecond;
		armed = true;
	}

	/** First frame of the first shot: open the output and take over the clock. */
	static void begin(Minecraft mc) {
		if (!armed || driving || closing) return;
		width = mc.gameRenderer.mainRenderTarget().width;
		height = mc.gameRenderer.mainRenderTarget().height;
		Path dir = FabricLoader.getInstance().getGameDir().resolve("autocraft-film");
		String name = "autocraft-film_" + LocalDateTime.now().format(STAMP);
		failure = null;
		pipe = null;
		ffmpeg = null;
		try {
			Files.createDirectories(dir);
			String exe = findFfmpeg();
			if (exe != null) {
				output = dir.resolve(name + ".mp4");
				List<String> cmd = List.of(exe, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-r",
						String.valueOf(fps), "-i", "-", "-vf", "crop=trunc(iw/2)*2:trunc(ih/2)*2", "-c:v", "libx264", "-preset", "medium", "-crf", "16",
						"-pix_fmt", "yuv420p", "-movflags", "+faststart", output.toString());
				Process p = new ProcessBuilder(cmd).redirectError(dir.resolve(name + ".log").toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
				ffmpeg = p;
				pipe = Channels.newChannel(p.getOutputStream());
				Chat.info("Recording " + width + "×" + height + " at " + fps + " fps to autocraft-film/" + output.getFileName()
						+ ". Leave the game alone until it says it's saved.");
			} else {
				output = Files.createDirectories(dir.resolve(name));
				Chat.warn("No ffmpeg found, so the frames are saved as PNGs in " + output
						+ ". For an MP4 straight away, install ffmpeg (\"winget install ffmpeg\") and restart the game.");
			}
		} catch (IOException e) {
			Chat.warn("Couldn't start recording: " + e.getMessage());
			armed = false;
			return;
		}
		writer = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "autocraft-film-writer");
			t.setDaemon(true);
			return t;
		});
		frame = 0;
		wholeTicks = 0;
		residual = 0;
		pending.set(0);
		driving = true;
	}

	/**
	 * The game's timer asks how far to move this frame; null leaves it on the wall clock. A new frame moves the film
	 * 1/fps of a second and is captured once drawn; while saving is behind or a screen is open (the pause menu), the
	 * clock holds and nothing is captured.
	 */
	public static @Nullable Step step() {
		if (!driving) return null;
		Minecraft mc = Minecraft.getInstance();
		if (failure == null && (mc.gameRenderer.mainRenderTarget().width != width || mc.gameRenderer.mainRenderTarget().height != height)) {
			failure = "the window was resized";
		}
		if (failure != null) {   // give the clock back; the film plays on, unrecorded
			driving = false;
			closing = true;
			return null;
		}
		if (mc.gui.screen() != null || pending.get() >= MAX_PENDING) return new Step(0, residual, 0);
		double total = frame * 20.0 / fps;
		long whole = (long) Math.floor(total);
		int ticks = (int) (whole - wholeTicks);
		wholeTicks = whole;
		residual = (float) (total - whole);
		frame++;
		captureNow = true;
		return new Step(ticks, residual, 20f / fps);
	}

	/** After each frame is drawn (world and HUD): read it back for saving; or, once stopped and drained, close up. */
	public static void afterRender() {
		if (captureNow) {
			captureNow = false;
			long index = frame;
			pending.incrementAndGet();
			Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), image -> {
				ExecutorService w = writer;
				if (w == null) {
					image.close();
					pending.decrementAndGet();
				} else {
					w.execute(() -> save(image, index));
				}
			});
		}
		if (closing && pending.get() == 0) close();
	}

	private static void save(NativeImage image, long index) {
		try (image) {
			if (failure != null) return;
			WritableByteChannel p = pipe;
			if (p != null) {
				ByteBuffer bytes = image.getPixelBytes();
				while (bytes.hasRemaining()) p.write(bytes);
			} else if (output != null) {
				image.writeToFile(output.resolve(String.format("frame_%06d.png", index)));
			}
		} catch (IOException e) {
			failure = "couldn't save a frame (" + e.getMessage() + ")";
		} finally {
			pending.decrementAndGet();
		}
	}

	/** The film ended or was stopped: hand the clock back; the file is closed once the last frames are saved. */
	static void finish() {
		armed = false;
		if (!driving) return;
		driving = false;
		closing = true;
	}

	private static void close() {
		closing = false;
		captureNow = false;
		ExecutorService w = writer;
		writer = null;
		if (w == null) return;
		Process proc = ffmpeg;
		WritableByteChannel p = pipe;
		Path out = output;
		long frames = frame;
		int rate = fps;
		w.execute(() -> {
			String problem = failure;
			try {
				if (p != null) p.close();
				if (proc != null && !proc.waitFor(10, TimeUnit.MINUTES)) problem = "ffmpeg didn't finish";
				else if (proc != null && problem == null && proc.exitValue() != 0) problem = "ffmpeg failed (see the .log next to the video)";
			} catch (IOException | InterruptedException e) {
				problem = e.getMessage();
			}
			String time = String.format("%.1f s", frames / (double) rate);
			String done = problem != null ? "Recording stopped: " + problem + ". What was saved so far is in " + out + "."
					: proc != null ? "Saved " + out + " (" + time + ")." : "Saved " + frames + " PNG frames (" + rate + " fps, " + time + ") in " + out + ".";
			boolean bad = problem != null;
			Minecraft.getInstance().execute(() -> {
				if (bad) Chat.warn(done);
				else Chat.info(done);
			});
		});
		w.shutdown();
	}

	/** ffmpeg on the PATH, where winget puts it, or a few usual folders. */
	private static @Nullable String findFfmpeg() {
		List<String> candidates = new ArrayList<>(List.of("ffmpeg"));
		String local = System.getenv("LOCALAPPDATA");
		if (local != null) candidates.add(Path.of(local, "Microsoft", "WinGet", "Links", "ffmpeg.exe").toString());
		candidates.addAll(List.of("C:\\ffmpeg\\bin\\ffmpeg.exe", "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe", "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg",
				"/usr/bin/ffmpeg"));
		for (String c : candidates) {
			try {
				Process p = new ProcessBuilder(c, "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
				if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) return c;
				p.destroy();
			} catch (IOException e) {
				// not installed there
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
		return null;
	}
}
