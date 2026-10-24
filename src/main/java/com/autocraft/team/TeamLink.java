package com.autocraft.team;

import com.autocraft.Chat;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One TCP connection carrying newline-delimited JSON messages. Reading happens on a daemon thread that queues
 * messages for the client thread; writes go through a single-thread executor so large payloads (schematic files)
 * never stall the game.
 */
final class TeamLink {
	/** A message from {@code from}; {@code msg == null} means the connection closed. */
	record Incoming(TeamLink from, JsonObject msg) {}

	private final Socket socket;
	private final BufferedWriter out;
	private final ExecutorService writer;
	private volatile boolean closed;
	/** Set once the peer said hello (leader side) or was welcomed (member side). */
	String name = "?";
	boolean accepted;

	TeamLink(Socket socket, Queue<Incoming> inbox) throws IOException {
		this.socket = socket;
		socket.setTcpNoDelay(true);
		this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
		this.writer = Executors.newSingleThreadExecutor(r -> daemon(r, "autocraft-team-writer"));
		BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
		daemon(() -> {
			try {
				String line;
				while ((line = in.readLine()) != null) {
					try {
						inbox.add(new Incoming(this, JsonParser.parseString(line).getAsJsonObject()));
					} catch (JsonParseException | IllegalStateException e) {
						Chat.LOG.warn("Bad team message: {}", line.length() > 200 ? line.substring(0, 200) : line);
					}
				}
			} catch (IOException e) {
				// connection dropped
			}
			close();
			inbox.add(new Incoming(this, null));
		}, "autocraft-team-reader").start();
	}

	static Thread daemon(Runnable r, String name) {
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		return t;
	}

	void send(JsonObject msg) {
		if (closed) return;
		String line = msg.toString();
		writer.execute(() -> {
			try {
				out.write(line);
				out.write('\n');
				out.flush();
			} catch (IOException e) {
				close();
			}
		});
	}

	boolean isClosed() {
		return closed;
	}

	void close() {
		if (closed) return;
		closed = true;
		writer.shutdown();
		try {
			socket.close();
		} catch (IOException ignored) {
			// already gone
		}
	}

	String address() {
		return socket.getInetAddress().getHostAddress();
	}
}
