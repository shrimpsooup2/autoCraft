package com.autocraft;

import com.autocraft.cinema.Recorder;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.Nullable;

/**
 * Dev harness ("record [name]" / "record stop"): records what the game shows to {@code recordings/<name>.mp4}, one
 * frame per client tick — 20 fps, so the video runs at game speed. Frames are read back from the render target, so
 * it keeps recording with the window covered or the screen locked. Unlike the film's {@link Recorder} it never holds
 * the game clock: the integrated server runs on in real time, and a client held back by a slow encoder would have
 * the bot reacting late to the mobs it fights.
 */
final class DevRecorder {
	private static final int FPS = 20;
	/** Frames read back but not yet written: past this they're dropped (and counted) rather than piling up in memory. */
	private static final int MAX_PENDING = 40;

	private static final AtomicInteger pending = new AtomicInteger();
	private static @Nullable ExecutorService writer;
	private static @Nullable Process ffmpeg;
	private static @Nullable WritableByteChannel pipe;
	private static @Nullable Path output;
	private static int width;
	private static int height;
	private static long frames;
	private static long dropped;
	private static volatile @Nullable String failure;

	private DevRecorder() {}

	static void start(Minecraft mc, String name) {
		if (writer != null) {
			Chat.LOG.info("[H] already recording to {}", output);
			return;
		}
		String exe = Recorder.findFfmpeg();
		if (exe == null) {
			Chat.LOG.warn("[H] no ffmpeg found: can't record");
			return;
		}
		width = mc.gameRenderer.mainRenderTarget().width;
		height = mc.gameRenderer.mainRenderTarget().height;
		Path dir = mc.gameDirectory.toPath().resolve("recordings");
		try {
			Files.createDirectories(dir);
			output = dir.resolve(name + ".mp4");
			List<String> cmd = List.of(exe, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-r",
					String.valueOf(FPS), "-i", "-", "-vf", "crop=trunc(iw/2)*2:trunc(ih/2)*2", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
					"-pix_fmt", "yuv420p", "-movflags", "+faststart", output.toString());
			Process p = new ProcessBuilder(cmd).redirectError(dir.resolve(name + ".log").toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			ffmpeg = p;
			pipe = Channels.newChannel(p.getOutputStream());
		} catch (IOException e) {
			Chat.LOG.warn("[H] couldn't start recording: {}", e.getMessage());
			return;
		}
		writer = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "autocraft-dev-recorder");
			t.setDaemon(true);
			return t;
		});
		frames = 0;
		dropped = 0;
		failure = null;
		pending.set(0);
		Chat.LOG.info("[H] recording {}x{} at {} fps to {}", width, height, FPS, output);
	}

	/** Every client tick: read back the last frame drawn. */
	static void tick(Minecraft mc) {
		if (writer == null) return;
		if (failure == null && (mc.gameRenderer.mainRenderTarget().width != width || mc.gameRenderer.mainRenderTarget().height != height)) {
			failure = "the window was resized";
		}
		if (failure != null) {
			Chat.LOG.warn("[H] recording stopped: {}", failure);
			stop();
			return;
		}
		if (pending.get() >= MAX_PENDING) {
			dropped++;
			return;
		}
		pending.incrementAndGet();
		// the read-back lands later on this (render) thread, as does stop(): no writer can be shut down under it
		Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
			ExecutorService w = writer;
			if (w == null) {
				image.close();
				pending.decrementAndGet();
			} else {
				w.execute(() -> save(image));
			}
		});
	}

	private static void save(NativeImage image) {
		try (image) {
			WritableByteChannel p = pipe;
			if (p == null || failure != null) return;
			ByteBuffer bytes = image.getPixelBytes();
			while (bytes.hasRemaining()) p.write(bytes);
			frames++;
		} catch (IOException e) {
			failure = "couldn't write a frame (" + e.getMessage() + ")";
		} finally {
			pending.decrementAndGet();
		}
	}

	/** Write out what's queued, close ffmpeg's input and wait for it to finish the MP4. */
	static void stop() {
		ExecutorService w = writer;
		if (w == null) return;
		writer = null;
		w.shutdown();
		try {
			w.awaitTermination(30, TimeUnit.SECONDS);
			WritableByteChannel p = pipe;
			if (p != null) p.close();
			Process proc = ffmpeg;
			boolean ok = proc != null && proc.waitFor(60, TimeUnit.SECONDS) && proc.exitValue() == 0;
			Chat.LOG.info("[H] recording saved: {} ({} frames, {} dropped){}", output, frames, dropped, ok ? "" : " — ffmpeg failed, see the .log");
		} catch (IOException | InterruptedException e) {
			Chat.LOG.warn("[H] recording didn't finish cleanly: {}", e.getMessage());
		} finally {
			pipe = null;
			ffmpeg = null;
		}
	}
}
