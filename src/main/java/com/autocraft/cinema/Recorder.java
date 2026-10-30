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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.Nullable;

/**
 * Records the film itself, frame by frame, at an exact frame rate however fast the computer is: while it records,
 * the game clock moves 1/fps of a second per captured frame (the partial tick and the client ticks come from the
 * frame number, not the wall clock), so nothing stutters and the video runs at true speed. Each frame is read back
 * from the screen and piped into ffmpeg (an MP4 in {@code autocraft-film/}); without ffmpeg the frames are saved as
 * PNGs to import into any video editor. If saving falls behind, the clock waits for it. With the song in
 * {@code autocraft-film/} (a file called music.mp3, .ogg, .wav, .m4a or .flac) it's laid under the video, in sync:
 * the film is cut to it ({@link Score}).
 */
public final class Recorder {
	private static final int MAX_PENDING = 6;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final List<String> MUSIC = List.of(".mp3", ".ogg", ".wav", ".m4a", ".flac");

	/** One step of the film clock: client ticks to run this frame, and the partial tick to draw it at. */
	public record Step(int ticks, float residual, float delta) {}

	private static int fps;
	/** Where in the song this recording starts (seconds), or -1 for no music. */
	private static double musicStart;
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
	private static @Nullable String ffmpegExe;
	private static @Nullable WritableByteChannel pipe;
	private static @Nullable Path output;
	private static volatile @Nullable String failure;

	private Recorder() {}

	/** The next film plays into a recording at this frame rate; musicAt: where it starts in the song, -1 for none. */
	static void arm(int framesPerSecond, double musicAt) {
		fps = framesPerSecond;
		musicStart = musicAt;
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
			ffmpegExe = findFfmpeg();
			if (ffmpegExe != null) {
				output = dir.resolve(name + ".mp4");
				Path silent = dir.resolve(name + "-video.mp4");
				List<String> cmd = List.of(ffmpegExe, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-r",
						String.valueOf(fps), "-i", "-", "-vf", "crop=trunc(iw/2)*2:trunc(ih/2)*2", "-c:v", "libx264", "-preset", "medium", "-crf", "16",
						"-pix_fmt", "yuv420p", "-movflags", "+faststart", silent.toString());
				Process p = new ProcessBuilder(cmd).redirectError(dir.resolve(name + ".log").toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
				ffmpeg = p;
				pipe = Channels.newChannel(p.getOutputStream());
				Chat.info("Recording " + width + "×" + height + " at " + fps + " fps to autocraft-film/" + output.getFileName()
						+ ". Leave the game alone until it says it's saved.");
				if (musicStart >= 0 && music(dir) == null) {
					Chat.info("Put the song in autocraft-film as music.mp3 to have it laid under the video in sync.");
				}
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
		if (!driving || !Film.playing()) return null;   // between shots (travelling, loading) the wall clock runs and nothing is kept
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

	/** The recorder itself threw: stop recording (what's saved is kept), let the film play on, never crash the frame. */
	public static void broke(Throwable e) {
		Chat.LOG.error("Film recording failed", e);
		if (failure == null) failure = "the recorder broke (" + e + ")";
		captureNow = false;
		if (driving) {
			driving = false;
			closing = true;
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
		String exe = ffmpegExe;
		long frames = frame;
		int rate = fps;
		double songAt = musicStart;
		w.execute(() -> {
			String problem = failure;
			String note = "";
			try {
				if (p != null) p.close();
				if (proc != null && !proc.waitFor(10, TimeUnit.MINUTES)) problem = "ffmpeg didn't finish";
				else if (proc != null && problem == null && proc.exitValue() != 0) problem = "ffmpeg failed (see the .log next to the video)";
				if (proc != null && problem == null && out != null && exe != null) note = soundtrack(exe, out, frames / (double) rate, songAt);
			} catch (IOException | InterruptedException e) {
				problem = e.getMessage();
			}
			String time = String.format("%.1f s", frames / (double) rate);
			String done = problem != null ? "Recording stopped: " + problem + ". What was saved so far is in " + out + "."
					: proc != null ? "Saved " + out + " (" + time + ")" + note + "."
					: "Saved " + frames + " PNG frames (" + rate + " fps, " + time + ") in " + out + ".";
			boolean bad = problem != null;
			Minecraft.getInstance().execute(() -> {
				if (bad) Chat.warn(done);
				else Chat.info(done);
			});
		});
		w.shutdown();
	}

	/**
	 * Lay the song under the silent video (from where this recording starts in it, fading out at the very end) and
	 * keep that as the video; without a song (or for a recording that isn't one stretch of the film) the silent
	 * video is the video.
	 */
	private static String soundtrack(String exe, Path out, double seconds, double songAt) throws IOException, InterruptedException {
		Path silent = out.resolveSibling(out.getFileName().toString().replace(".mp4", "-video.mp4"));
		Path song = songAt >= 0 ? music(out.getParent()) : null;
		if (song == null) {
			Files.move(silent, out);
			return songAt >= 0 ? ", silent (no music.mp3 in autocraft-film)" : ", silent";
		}
		String fadeAt = String.format(Locale.ROOT, "%.3f", Math.max(0, seconds - 2));
		List<String> cmd = List.of(exe, "-y", "-loglevel", "error", "-i", silent.toString(), "-ss", String.format(Locale.ROOT, "%.3f", songAt), "-i",
				song.toString(), "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-af", "afade=t=out:st=" + fadeAt + ":d=2",
				"-t", String.format(Locale.ROOT, "%.3f", seconds), "-movflags", "+faststart", out.toString());
		Process mux = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.resolveSibling(out.getFileName() + ".mux.log").toFile()).start();
		if (!mux.waitFor(5, TimeUnit.MINUTES) || mux.exitValue() != 0) {
			Files.move(silent, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return ", silent (adding " + song.getFileName() + " failed, see the .mux.log)";
		}
		Files.deleteIfExists(silent);
		Files.deleteIfExists(out.resolveSibling(out.getFileName() + ".mux.log"));
		return ", with " + song.getFileName() + " in sync";
	}

	/** The song: autocraft-film/music.(mp3|ogg|wav|m4a|flac). */
	private static @Nullable Path music(Path dir) {
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(f -> {
				String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
				return n.startsWith("music.") && MUSIC.stream().anyMatch(n::endsWith);
			}).findFirst().orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	/** ffmpeg on the PATH, where winget puts it, or a few usual folders. */
	public static @Nullable String findFfmpeg() {
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
