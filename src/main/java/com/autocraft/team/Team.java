package com.autocraft.team;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.build.BuildOrder;
import com.autocraft.build.BuildPlan;
import com.autocraft.build.Substitution;
import com.autocraft.control.InvUtil;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.goal.Resources;
import com.autocraft.schematic.Schematic;
import com.autocraft.schematic.SchematicLibrary;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Several bots (separate accounts, same Minecraft world) working together, coordinated over a small TCP server
 * hosted by the mod on the computer that starts the job. Others join with the address and code it shows.
 *
 * <p>A shared build is split by role: the leader places, one member digs the site out and then gathers, the rest
 * gather the materials the placer still needs and drop them in a chest next to the site (the depot). Collect jobs
 * are split by amount.
 */
public final class Team {
	public static final int DEFAULT_PORT = 25590;
	private static final int PROTOCOL = 1;
	private static final int STATUS_TICKS = 20;
	private static final int NEEDS_TICKS = 100;
	private static final int CONNECT_TIMEOUT_MS = 5000;

	/** What the panel shows for one teammate. */
	public record MemberView(String name, String role, String activity, @Nullable BlockPos pos, @Nullable Dim dim) {}

	/** The leader's own part of a shared build. */
	public record Assignment(TeamRole role, BlockPos depot) {}

	private static final class MemberState {
		String name;
		TeamRole role = TeamRole.SOLO;
		@Nullable BlockPos pos;
		@Nullable Dim dim;
		String activity = "";
		Map<Item, Integer> carrying = Map.of();

		MemberState(String name) {
			this.name = name;
		}
	}

	private static final Queue<TeamLink.Incoming> inbox = new ConcurrentLinkedQueue<>();
	private static @Nullable ServerSocket server;
	private static final List<TeamLink> pending = Collections.synchronizedList(new ArrayList<>());
	private static final Map<TeamLink, MemberState> members = new LinkedHashMap<>();
	private static @Nullable TeamLink leaderLink;
	private static volatile boolean joining;
	private static String code = "";
	private static String leaderName = "";
	private static int port;
	private static long ticks;
	private static List<MemberView> roster = List.of();

	// leader-side build coordination
	private static boolean building;
	private static @Nullable BlockPos depot;
	private static final Map<Item, Integer> depotLedger = new HashMap<>();
	private static final Map<Item, TeamLink> itemOwner = new HashMap<>();

	private Team() {}

	// ---- lifecycle ------------------------------------------------------------------------------

	public static boolean isActive() {
		return server != null || leaderLink != null || joining;
	}

	public static boolean isLeader() {
		return server != null;
	}

	public static boolean isMember() {
		return leaderLink != null;
	}

	public static int memberCount() {
		return members.size();
	}

	public static String code() {
		return code;
	}

	/** Start the team server; others join with {@link #shareAddress()} and {@link #code()}. */
	public static void host(int listenPort) {
		if (isActive()) throw new IllegalStateException("already in a team (leave first)");
		try {
			ServerSocket s = new ServerSocket();
			s.setReuseAddress(true);
			s.bind(new InetSocketAddress(listenPort));
			server = s;
		} catch (IOException e) {
			throw new IllegalStateException("can't open port " + listenPort + ": " + e.getMessage());
		}
		port = listenPort;
		code = newCode();
		leaderName = playerName();
		ServerSocket s = server;
		TeamLink.daemon(() -> {
			while (!s.isClosed()) {
				try {
					Socket socket = s.accept();
					pending.add(new TeamLink(socket, inbox));
				} catch (IOException e) {
					if (!s.isClosed()) Chat.LOG.warn("Team accept failed", e);
				}
			}
		}, "autocraft-team-accept").start();
		Chat.info("Team server open. Others run: !ac team join " + shareAddress() + " " + code
				+ " (same PC: 127.0.0.1:" + port + "). Every bot must be in the same world.");
	}

	/** Connect to a leader (in the background; the result is reported in chat). */
	public static void join(String host, int hostPort, String joinCode) {
		if (isActive()) throw new IllegalStateException("already in a team (leave first)");
		joining = true;
		String me = playerName();
		TeamLink.daemon(() -> {
			try {
				Socket socket = new Socket();
				socket.connect(new InetSocketAddress(host, hostPort), CONNECT_TIMEOUT_MS);
				TeamLink link = new TeamLink(socket, inbox);
				JsonObject hello = msg("hello");
				hello.addProperty("name", me);
				hello.addProperty("code", joinCode.toUpperCase());
				hello.addProperty("protocol", PROTOCOL);
				link.send(hello);
				Minecraft.getInstance().execute(() -> {
					joining = false;
					leaderLink = link;
				});
			} catch (IOException e) {
				Minecraft.getInstance().execute(() -> {
					joining = false;
					Chat.error("Couldn't reach a team at " + host + ":" + hostPort + " (" + e.getMessage() + ").");
				});
			}
		}, "autocraft-team-connect").start();
		Chat.info("Joining the team at " + host + ":" + hostPort + "...");
	}

	public static void leave() {
		if (server != null) {
			broadcast(msg("bye"));
			try {
				server.close();
			} catch (IOException ignored) {
				// closing anyway
			}
			server = null;
			for (TeamLink l : members.keySet()) l.close();
			synchronized (pending) {
				pending.forEach(TeamLink::close);
				pending.clear();
			}
			members.clear();
			resetBuild();
			Chat.info("Team closed.");
		}
		if (leaderLink != null) {
			leaderLink.send(msg("bye"));
			leaderLink.close();
			leaderLink = null;
			endTeamBuild("left the team");
			Chat.info("Left the team.");
		}
		roster = List.of();
		inbox.clear();
	}

	// ---- per tick -------------------------------------------------------------------------------

	public static void tick(Minecraft mc) {
		TeamLink.Incoming in;
		while ((in = inbox.poll()) != null) {
			try {
				if (isLeader()) leaderReceive(in.from(), in.msg());
				else if (in.from() == leaderLink) memberReceive(in.msg());
			} catch (RuntimeException e) {
				Chat.LOG.warn("Team message failed: {}", in.msg(), e);
			}
		}
		if (!isActive() || mc.player == null) return;
		ticks++;
		if (ticks % STATUS_TICKS == 0) {
			if (isLeader()) broadcastRoster();
			else if (leaderLink != null && leaderLink.accepted) leaderLink.send(status());
		}
		if (isLeader() && building && ticks % NEEDS_TICKS == 0) distributeNeeds(mc);
	}

	// ---- leader side ----------------------------------------------------------------------------

	private static void leaderReceive(TeamLink from, @Nullable JsonObject m) {
		if (m == null || "bye".equals(type(m))) {
			pending.remove(from);
			MemberState gone = members.remove(from);
			if (gone != null) {
				Chat.warn(gone.name + " left the team.");
				itemOwner.values().removeIf(l -> l == from);
			}
			from.close();
			return;
		}
		if (!from.accepted) {
			if (!"hello".equals(type(m))) return;
			pending.remove(from);
			if (!code.equals(m.get("code").getAsString()) || m.get("protocol").getAsInt() != PROTOCOL) {
				JsonObject reject = msg("reject");
				reject.addProperty("reason", m.get("protocol").getAsInt() != PROTOCOL ? "different autoCraft version" : "wrong team code");
				from.send(reject);
				from.close();
				return;
			}
			from.accepted = true;
			from.name = m.get("name").getAsString();
			members.put(from, new MemberState(from.name));
			JsonObject welcome = msg("welcome");
			welcome.addProperty("leader", leaderName);
			from.send(welcome);
			Chat.info(from.name + " joined the team (" + from.address() + ").");
			if (building && Bot.get().buildOrder() != null) assignBuild(from, Bot.get().buildOrder(), TeamRole.GATHER); // late joiner helps gather
			return;
		}
		MemberState st = members.get(from);
		if (st == null) return;
		switch (type(m)) {
			case "status" -> {
				st.pos = pos(m.getAsJsonArray("pos"));
				st.dim = Dim.valueOf(m.get("dim").getAsString());
				st.activity = m.get("activity").getAsString();
				st.carrying = items(m.getAsJsonObject("carrying"));
			}
			case "delivered" -> {
				Map<Item, Integer> got = items(m.getAsJsonObject("items"));
				got.forEach((item, n) -> depotLedger.merge(item, n, Integer::sum));
				Chat.info(st.name + " delivered " + describe(got) + " to the depot.");
			}
			case "cleared" -> {
				st.role = TeamRole.GATHER;
				Chat.info(st.name + " finished digging out the site and is gathering now.");
			}
			default -> {
			}
		}
	}

	/**
	 * Called on the leader when a user build starts: hands roles to the members and returns the leader's part,
	 * or null when there's nobody to share with.
	 */
	public static @Nullable Assignment shareBuild(BuildOrder order) {
		if (!isLeader() || members.isEmpty()) return null;
		resetBuild();
		BoundingBox box = new BuildPlan(order.schematic(), order.origin(), order.rotation(), order.mirror(), order.substitution()).box();
		depot = new BlockPos(box.minX() - 2, box.minY(), box.minZ() - 2);
		building = true;
		List<TeamLink> links = new ArrayList<>(members.keySet());
		for (int i = 0; i < links.size(); i++) {
			assignBuild(links.get(i), order, i == 0 && links.size() >= 2 ? TeamRole.CLEAR : TeamRole.GATHER);
		}
		Chat.info("Team build: you place" + (links.size() >= 2 ? ", " + links.getFirst().name + " digs the site out" : "") + ", "
				+ (links.size() >= 2 ? "the others gather" : links.getFirst().name + " gathers") + "; materials go to the chest at "
				+ depot.toShortString() + ".");
		return new Assignment(TeamRole.PLACE, depot);
	}

	private static void assignBuild(TeamLink link, BuildOrder order, TeamRole role) {
		Schematic s = order.schematic();
		JsonObject a = msg("assign");
		a.addProperty("kind", "build");
		a.addProperty("schematic", s.name());
		if (!s.builtIn()) {
			Path file = SchematicLibrary.fileOf(s.name());
			if (file != null) {
				try {
					String fileName = file.getFileName().toString();
					a.addProperty("ext", fileName.substring(fileName.lastIndexOf('.')));
					a.addProperty("file", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
				} catch (IOException e) {
					Chat.warn("Couldn't send " + file.getFileName() + " to " + link.name + ": " + e.getMessage());
				}
			}
		}
		a.add("origin", json(order.origin()));
		a.addProperty("rotation", order.rotation().ordinal());
		a.addProperty("mirror", order.mirror().ordinal());
		a.addProperty("materials", order.substitution().encode());
		a.addProperty("role", role.name());
		a.add("depot", json(depot));
		link.send(a);
		MemberState st = members.get(link);
		if (st != null) st.role = role;
	}

	/** Called on the leader when a collect job starts: members get an even share, the leader keeps the rest. */
	public static Job shareJob(Job job) {
		if (!isLeader() || members.isEmpty()) return job;
		int n = members.size() + 1;
		List<Requirement> mine = new ArrayList<>();
		List<String> theirs = new ArrayList<>();
		for (Requirement r : job.requirements()) {
			int each = r.amount() / n;
			mine.add(r.withAmount(r.amount() - each * (n - 1)));
			if (each > 0) theirs.add(r.name() + ":" + each);
		}
		if (theirs.isEmpty()) return job;
		JsonObject a = msg("assign");
		a.addProperty("kind", "collect");
		a.addProperty("reqs", String.join(",", theirs));
		broadcast(a);
		Chat.info("Team job: each of " + members.size() + " teammate(s) collects " + String.join(", ", theirs).replace(':', ' ') + ".");
		return new Job(mine);
	}

	/** Leader: who still needs what (bill minus what's placed, carried, in the depot, or on its way). */
	private static void distributeNeeds(Minecraft mc) {
		Bot bot = Bot.get();
		BuildPlan plan = bot.buildPlan();
		if (plan == null || mc.level == null) return;
		List<TeamLink> gatherers = new ArrayList<>();
		members.forEach((l, st) -> {
			if (st.role == TeamRole.GATHER) gatherers.add(l);
		});
		if (gatherers.isEmpty()) return;
		Map<Item, Integer> remaining = new LinkedHashMap<>(plan.bill(mc.level, Integer.MAX_VALUE));
		remaining.remove(Items.WATER_BUCKET); // the placer fills its own buckets at the site
		remaining.remove(Items.LAVA_BUCKET);
		remaining.replaceAll((item, n) -> n - InvUtil.count(mc.player, s -> s.is(item)) - depotLedger.getOrDefault(item, 0));
		for (MemberState st : members.values()) st.carrying.forEach((item, n) -> remaining.computeIfPresent(item, (k, v) -> v - n));
		Map<TeamLink, Map<Item, Integer>> perGatherer = new HashMap<>();
		for (TeamLink g : gatherers) perGatherer.put(g, new LinkedHashMap<>());
		itemOwner.values().removeIf(l -> !gatherers.contains(l));
		for (Map.Entry<Item, Integer> e : remaining.entrySet()) {
			if (e.getValue() <= 0) continue;
			TeamLink owner = itemOwner.get(e.getKey());
			if (owner == null) {
				owner = gatherers.stream().min((a, b) -> perGatherer.get(a).size() - perGatherer.get(b).size()).orElseThrow();
				itemOwner.put(e.getKey(), owner);
			}
			int carried = members.get(owner).carrying.getOrDefault(e.getKey(), 0);
			perGatherer.get(owner).put(e.getKey(), e.getValue() + carried); // total to have on hand before delivering
		}
		perGatherer.forEach((link, needs) -> {
			JsonObject m = msg("needs");
			m.add("items", json(needs));
			link.send(m);
		});
	}

	/** Leader took items out of the depot. */
	public static void noteWithdrawn(Map<Item, Integer> taken) {
		taken.forEach((item, n) -> depotLedger.computeIfPresent(item, (k, v) -> Math.max(0, v - n)));
	}

	public static boolean clearerActive() {
		return members.values().stream().anyMatch(st -> st.role == TeamRole.CLEAR);
	}

	public static int gathererCount() {
		return (int) members.values().stream().filter(st -> st.role == TeamRole.GATHER).count();
	}

	/** Leader finished (or abandoned) the shared build. */
	public static void buildFinished() {
		if (!isLeader() || !building) return;
		broadcast(msg("done"));
		resetBuild();
	}

	/** Leader stopped: everyone stops. */
	public static void stopAll() {
		if (isLeader() && !members.isEmpty()) broadcast(msg("stop"));
		resetBuild();
	}

	private static void resetBuild() {
		building = false;
		depot = null;
		depotLedger.clear();
		itemOwner.clear();
		for (MemberState st : members.values()) st.role = TeamRole.SOLO;
	}

	private static void broadcast(JsonObject m) {
		for (TeamLink l : members.keySet()) l.send(m);
	}

	private static void broadcastRoster() {
		JsonObject m = msg("roster");
		JsonArray list = new JsonArray();
		for (MemberView v : roster()) {
			JsonObject o = new JsonObject();
			o.addProperty("name", v.name());
			o.addProperty("role", v.role());
			o.addProperty("activity", v.activity());
			list.add(o);
		}
		m.add("members", list);
		broadcast(m);
	}

	// ---- member side ----------------------------------------------------------------------------

	private static void memberReceive(@Nullable JsonObject m) {
		if (m == null || "bye".equals(type(m))) {
			Chat.warn(m == null ? "Lost the connection to the team leader." : "The team leader closed the team.");
			if (leaderLink != null) leaderLink.close();
			leaderLink = null;
			roster = List.of();
			endTeamBuild("team closed");
			return;
		}
		Bot bot = Bot.get();
		switch (type(m)) {
			case "welcome" -> {
				leaderLink.accepted = true;
				leaderName = m.get("leader").getAsString();
				Chat.info("Joined " + leaderName + "'s team. Waiting for orders.");
			}
			case "reject" -> {
				Chat.error("The team refused to let us in: " + m.get("reason").getAsString() + ".");
				leaderLink.close();
				leaderLink = null;
			}
			case "roster" -> {
				List<MemberView> list = new ArrayList<>();
				for (JsonElement e : m.getAsJsonArray("members")) {
					JsonObject o = e.getAsJsonObject();
					list.add(new MemberView(o.get("name").getAsString(), o.get("role").getAsString(), o.get("activity").getAsString(), null, null));
				}
				roster = list;
			}
			case "assign" -> {
				if ("collect".equals(m.get("kind").getAsString())) receiveJob(m.get("reqs").getAsString());
				else receiveBuild(m);
			}
			case "needs" -> bot.setTeamNeeds(items(m.getAsJsonObject("items")));
			case "done" -> {
				Chat.info(leaderName + " finished the team build.");
				endTeamBuild("team build complete");
			}
			case "stop" -> bot.stop("stopped by the team leader");
			default -> {
			}
		}
	}

	private static void receiveBuild(JsonObject m) {
		String name = m.get("schematic").getAsString();
		Schematic s = null;
		if (m.has("file")) {
			Path dir = FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve("team-schematics");
			Path file = dir.resolve(name + m.get("ext").getAsString());
			try {
				Files.createDirectories(dir);
				Files.write(file, Base64.getDecoder().decode(m.get("file").getAsString()));
				s = SchematicLibrary.load(file);
			} catch (IOException | IllegalArgumentException e) {
				Chat.error("Couldn't save the team schematic: " + e.getMessage());
			}
		} else {
			s = SchematicLibrary.get(name);
		}
		if (s == null) {
			Chat.error("Couldn't load the team schematic '" + name + "'.");
			return;
		}
		BuildOrder order = new BuildOrder(s, pos(m.getAsJsonArray("origin")), Rotation.values()[m.get("rotation").getAsInt()],
				Mirror.values()[m.get("mirror").getAsInt()], Substitution.decode(m.get("materials").getAsString()), BuildOrder.Purpose.USER);
		TeamRole role = TeamRole.valueOf(m.get("role").getAsString());
		Chat.info("Team build " + s.name() + ": you're the " + role.label + ".");
		Bot.get().startTeamBuild(order, role, pos(m.getAsJsonArray("depot")));
	}

	private static void receiveJob(String reqs) {
		List<Requirement> list = new ArrayList<>();
		for (String part : reqs.split(",")) {
			int colon = part.lastIndexOf(':');
			Requirement r = Resources.forJob(part.substring(0, colon), Integer.parseInt(part.substring(colon + 1)));
			if (r != null) list.add(r);
		}
		if (!list.isEmpty()) Bot.get().start(new Job(list));
	}

	private static void endTeamBuild(String reason) {
		Bot bot = Bot.get();
		if (bot.teamRole() == TeamRole.SOLO) return;
		bot.finishBuild();
		if (bot.job() == null) bot.stop(reason);
	}

	/** Member: a clearer finished digging and switches to gathering. */
	public static void reportCleared() {
		if (leaderLink != null) leaderLink.send(msg("cleared"));
	}

	/** Member: items put into the depot chest. */
	public static void reportDelivered(Map<Item, Integer> delivered) {
		if (leaderLink == null || delivered.isEmpty()) return;
		JsonObject m = msg("delivered");
		m.add("items", json(delivered));
		leaderLink.send(m);
	}

	private static JsonObject status() {
		Bot bot = Bot.get();
		Minecraft mc = Minecraft.getInstance();
		JsonObject m = msg("status");
		m.add("pos", json(mc.player.blockPosition()));
		m.addProperty("dim", bot.dimension().name());
		m.addProperty("activity", bot.isRunning() ? bot.activeBehavior() + ": " + bot.status() : "idle");
		Map<Item, Integer> carrying = new LinkedHashMap<>();
		for (Item item : bot.teamNeeds().keySet()) {
			int n = InvUtil.count(mc.player, s -> s.is(item));
			if (n > 0) carrying.put(item, n);
		}
		m.add("carrying", json(carrying));
		return m;
	}

	// ---- views ----------------------------------------------------------------------------------

	/** Everyone in the team, leader first. */
	public static List<MemberView> roster() {
		if (!isLeader()) return roster;
		Bot bot = Bot.get();
		List<MemberView> out = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		out.add(new MemberView(leaderName + " (leader)", building ? TeamRole.PLACE.label : "", bot.isRunning() ? bot.activeBehavior() + ": "
				+ bot.status() : "idle", mc.player == null ? null : mc.player.blockPosition(), mc.level == null ? null : bot.dimension()));
		for (MemberState st : members.values()) {
			out.add(new MemberView(st.name, st.role == TeamRole.SOLO ? "" : st.role.label, st.activity, st.pos, st.dim));
		}
		return out;
	}

	public static String leaderName() {
		return leaderName;
	}

	/** This computer's LAN address and the team port, for others to join. */
	public static String shareAddress() {
		return lanAddress() + ":" + port;
	}

	private static String lanAddress() {
		try {
			for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
				for (InetAddress a : Collections.list(ni.getInetAddresses())) {
					if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
				}
			}
		} catch (IOException e) {
			// fall through
		}
		return "127.0.0.1";
	}

	// ---- helpers --------------------------------------------------------------------------------

	private static String playerName() {
		return Minecraft.getInstance().getUser().getName();
	}

	private static String newCode() {
		String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
		SecureRandom r = new SecureRandom();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 5; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
		return sb.toString();
	}

	private static JsonObject msg(String type) {
		JsonObject o = new JsonObject();
		o.addProperty("t", type);
		return o;
	}

	private static String type(JsonObject m) {
		return m.has("t") ? m.get("t").getAsString() : "";
	}

	private static JsonArray json(BlockPos p) {
		JsonArray a = new JsonArray();
		a.add(p.getX());
		a.add(p.getY());
		a.add(p.getZ());
		return a;
	}

	private static BlockPos pos(JsonArray a) {
		return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
	}

	private static JsonObject json(Map<Item, Integer> items) {
		JsonObject o = new JsonObject();
		items.forEach((item, n) -> o.addProperty(BuiltInRegistries.ITEM.getKey(item).toString(), n));
		return o;
	}

	private static Map<Item, Integer> items(@Nullable JsonObject o) {
		Map<Item, Integer> out = new LinkedHashMap<>();
		if (o == null) return out;
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			Identifier id = Identifier.tryParse(e.getKey());
			if (id == null) continue;
			BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> out.put(item, e.getValue().getAsInt()));
		}
		return out;
	}

	private static String describe(Map<Item, Integer> items) {
		List<String> parts = new ArrayList<>();
		items.forEach((item, n) -> parts.add(n + " " + item.getDefaultInstance().getHoverName().getString()));
		return String.join(", ", parts);
	}
}
