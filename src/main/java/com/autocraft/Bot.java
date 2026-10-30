package com.autocraft;

import com.autocraft.ai.ThreatNet;
import com.autocraft.behavior.Behavior;
import com.autocraft.behavior.BuildBehavior;
import com.autocraft.behavior.CollectBehavior;
import com.autocraft.behavior.CombatBehavior;
import com.autocraft.behavior.DefenseReflex;
import com.autocraft.behavior.DrowningReflex;
import com.autocraft.behavior.EatReflex;
import com.autocraft.behavior.EffectReflex;
import com.autocraft.behavior.FallReflex;
import com.autocraft.behavior.FarmBehavior;
import com.autocraft.behavior.FetchBehavior;
import com.autocraft.behavior.FinishBehavior;
import com.autocraft.behavior.FoodBehavior;
import com.autocraft.behavior.GearBehavior;
import com.autocraft.behavior.GotoBehavior;
import com.autocraft.behavior.LavaReflex;
import com.autocraft.behavior.MakeBehavior;
import com.autocraft.behavior.MendingBehavior;
import com.autocraft.behavior.PickupBehavior;
import com.autocraft.behavior.PortalBehavior;
import com.autocraft.behavior.RecoverBehavior;
import com.autocraft.behavior.RespawnBedBehavior;
import com.autocraft.behavior.StorageBehavior;
import com.autocraft.build.BuildOrder;
import com.autocraft.build.BuildPlan;
import com.autocraft.control.Controls;
import com.autocraft.control.InvUtil;
import com.autocraft.craft.RecipeBook;
import com.autocraft.craft.Stations;
import com.autocraft.goal.Destination;
import com.autocraft.goal.Ending;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.loot.LootSites;
import com.autocraft.procedure.Procedures;
import com.autocraft.tasks.TaskTree;
import com.autocraft.team.Team;
import com.autocraft.team.TeamRole;
import com.autocraft.path.DigTime;
import com.autocraft.path.Navigator;
import com.autocraft.travel.BoatTravel;
import com.autocraft.travel.ElytraTravel;
import com.autocraft.world.WorldScanner;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import org.jspecify.annotations.Nullable;

/**
 * Owns the work (a resource job, a build order, or both) and runs the behavior priority stack once per
 * client tick. Order matters: survival reflexes first, the actual job last.
 */
public final class Bot {
	/** Planners that can ask the collector for prerequisite materials, highest priority first. */
	public enum NeedOwner {
		/** Tools, weapons, armor, nether kit: quick to make, and nothing goes well without them (a pickaxe above all). */
		GEAR,
		/** Food to take along: no animals live in the Nether. */
		FOOD,
		/** A bed to put down near dangerous work far from the spawn point (setting "respawn beds"). */
		BED,
		/** Materials for the structure being built. */
		BUILD,
		/** Raw materials for crafted/smelted job items. */
		MAKE,
		/** Portal materials (obsidian). */
		TRAVEL
	}

	/** After a failed elytra takeoff, walk for a while before trying again. */
	private static final int FLIGHT_RETRY_TICKS = 20 * 60 * 2;

	private static @Nullable Bot instance;

	private final Minecraft mc;
	private final Controls controls;
	private final WorldScanner scanner;
	private final Navigator nav;
	private final ThreatNet threatNet;
	private final List<Behavior> behaviors;
	private final Map<NeedOwner, Requirement> needs = new EnumMap<>(NeedOwner.class);
	private @Nullable Requirement toolRequest;
	private String gearNote = "nothing yet";
	private final Map<String, Integer> fruitlessLegs = new HashMap<>();
	private final Set<Item> buildMaterials = new HashSet<>();
	/** Ingredients (a few recipe levels deep) of the job's crafted items: kept, not junked or used as scaffolding. */
	private final Set<Item> jobMaterials = new HashSet<>();
	private @Nullable BuildPlan buildPlan;
	private int buildPlaced;
	private int buildTotal;
	private int @Nullable [] buildDig;
	private TeamRole teamRole = TeamRole.SOLO;
	private @Nullable BlockPos depot;
	private Map<Item, Integer> teamNeeds = Map.of();
	/** Mobs the collector is hunting right now (combat fights these instead of avoiding them). */
	private Set<EntityType<?>> huntTargets = Set.of();
	/** Mobs combat ran from as too strong even for full health (by id), and the tick it last did (see {@link #dreaded}). */
	private final Map<Integer, Long> dreaded = new HashMap<>();
	/** Where to go back for our dropped items after a death; null when not recovering. */
	private @Nullable Recovery recovery;
	/**
	 * Ticks the recovery spot's chunk has been loaded: the items there despawn after five minutes of that. Counted
	 * every tick, whatever behavior runs — counted by the recovery behavior itself, combat and eating took most ticks
	 * of a death spiral and each new death restarted it, so the bot was still walking back at 3 hp to items gone for
	 * ten minutes.
	 */
	private int recoveryAge;
	private boolean respawnRequested;
	/** What was carried, as of a moment ago, is worth going back for after a death (see onDeath). */
	private boolean carryingValuables;
	private int airScares;
	/** Deaths to mobs (a damage source with an attacker) this session: armor becomes a must, a piece per death. */
	private int mobDeaths;
	/** Deaths since the current job or build began (a respawn bed is worth it once it has died out here). */
	private int jobDeaths;

	/** Death spot to return to. */
	public record Recovery(BlockPos pos, Dim dim) {}

	/** Take {@code amount} of {@code item} out of the chests at the base at {@code base} (a task-tree step). */
	public record FetchOrder(BlockPos base, Dim dim, Item item, int amount) {}

	private @Nullable FetchOrder fetchOrder;
	private @Nullable Dim procedureDimension;
	/** The guarded structure a running procedure works in (a trial chamber's vaults): the gear planner kits up for it. */
	private LootSites.@Nullable Site guardedWork;

	/** Progress is saved this often while running (and whenever work starts or stops). */
	private static final int SAVE_EVERY = 20 * 5;
	/** How often the inventory is checked for things worth recovering after a death. */
	private static final int VALUABLES_EVERY = 10;
	/** How long work keeps clear of a mob combat ran from as unbeatable ({@link #dreaded}). */
	private static final long DREAD_TICKS = 20 * 60 * 10;
	/** Open-water swimming this long (an ocean, not a river: ~100 blocks sprint-swum) asks for a boat. */
	private static final int SWIM_TICKS_FOR_BOAT = 20 * 20;
	private static final int BOAT_WANT_TICKS = 20 * 120;
	/** A trip pickaxe not made in this long is given up on: the trip goes on without it. */
	private static final int TRIP_PICKAXE_GIVE_UP = 20 * 60;
	/** After giving up on one, no trip pickaxe for this long. */
	private static final int TRIP_PICKAXE_QUIET = 20 * 300;
	/** A trip pickaxe must save this much digging beyond what making it takes. */
	private static final int TRIP_PICKAXE_MARGIN = 20 * 10;
	/**
	 * Behaviors that walk somewhere on the bot's own errand (back to its items, a go-to task, a fetch, the trip to the
	 * base a task ends at), where gear plans tools for the work, not for digging the way there.
	 */
	private static final Set<String> TRIP_BEHAVIORS = Set.of("recover", "goto", "fetch", "finish");
	/** Stop reasons for finished work (the task tree moves on after these). */
	public static final String JOB_COMPLETE = "job complete";
	public static final String BUILD_COMPLETE = "build complete";
	public static final String FETCH_COMPLETE = "fetch complete";
	public static final String ARRIVED = "arrived";
	/** A task-tree group whose subtasks are done, ending at a base: the trip there was its own last step. */
	public static final String GROUP_COMPLETE = "group complete";
	private static final Set<String> COMPLETE = Set.of(JOB_COMPLETE, BUILD_COMPLETE, FETCH_COMPLETE, ARRIVED, GROUP_COMPLETE);

	private String lastStop = "";

	private @Nullable Job job;
	private @Nullable BuildOrder build;
	/** A go-to task's destination; null when the work isn't one. */
	private @Nullable Destination destination;
	/** The base the current task ends at, and what it does there; null = it ends where it is. */
	private @Nullable Ending ending;
	/**
	 * Set once the task's own goal is met (one of the {@link #COMPLETE} reasons): the end-of-job flow — stations picked
	 * up, the trip to the ending base, the deposit — runs, then the bot stops with this reason. Null before that.
	 */
	private @Nullable String finishing;
	private boolean running;
	private boolean paused;
	private String activeBehavior = "idle";
	private static final int TRACE_SIZE = 40;
	private final java.util.ArrayDeque<String> trace = new java.util.ArrayDeque<>();
	private String lastTrace = "";
	private String status = "";
	/** Tick of the last {@link #setStatus}: an idle tick keeps a reason a behavior gave while declining. */
	private long statusTick = -1;
	private @Nullable ElytraTravel flight;
	private long ticks;
	private int swimTicks;
	private long boatWantedUntil = -1;
	/** A pickaxe to make before the trip goes on ({@link #considerTripPickaxe}), or null. */
	private @Nullable Item tripPickaxe;
	private long tripPickaxeSince;
	private long tripPickaxeQuietUntil;
	/** The planned path last weighed for a trip pickaxe: each path once. */
	private @Nullable DigTime tripWeighed;
	private long flightRetryAt;

	private Bot(Minecraft mc) {
		this.mc = mc;
		this.controls = new Controls(mc);
		this.scanner = new WorldScanner(mc);
		this.nav = new Navigator(mc, controls, this::isJobItem);
		this.threatNet = ThreatNet.loadOrTrain();
		this.behaviors = List.of(
				new LavaReflex(),
				new DrowningReflex(),
				new FallReflex(),
				new DefenseReflex(),
				new EatReflex(),
				new EffectReflex(),
				new CombatBehavior(),
				new PickupBehavior(),
				new RecoverBehavior(),
				new MendingBehavior(),
				new FetchBehavior(),
				new FinishBehavior(),
				new GearBehavior(),
				new FoodBehavior(),
				new RespawnBedBehavior(),
				new StorageBehavior(),
				new BuildBehavior(),
				new FarmBehavior(),
				new MakeBehavior(),
				new PortalBehavior(),
				new CollectBehavior(),
				new GotoBehavior());
	}

	public static Bot get() {
		if (instance == null) instance = new Bot(Minecraft.getInstance());
		return instance;
	}

	// ---- loop -----------------------------------------------------------------------------------

	public void tick() {
		controls.beginTick();
		if (!running || paused || mc.player == null || mc.level == null || mc.gameMode == null) {
			controls.releaseAll();
			return;
		}
		if (!mc.player.isAlive()) {
			onDeath();
			return;
		}
		respawnRequested = false;
		if (playerAlert()) return;
		scanner.tick();
		ticks++;
		if (recovery != null && recovery.dim() == dimension() && mc.level.hasChunkAt(recovery.pos())) recoveryAge++;
		if (ticks % SAVE_EVERY == 0) TaskSave.save(this, true);
		if (ticks % VALUABLES_EVERY == 0) carryingValuables = carriesValuables(mc.player);
		noteSwimming();
		considerTripPickaxe(); // before the behaviors: activeBehavior is still last tick's
		activeBehavior = "idle";
		for (Behavior b : behaviors) {
			// mid-flight only the lava/fall reflexes may interrupt; everything else waits for landing
			if (flight != null && !(b instanceof LavaReflex || b instanceof FallReflex)) {
				tickFlight();
				break;
			}
			if (b.tick(this)) {
				activeBehavior = b.name();
				break;
			}
		}
		if (activeBehavior.equals("idle") && statusTick != ticks) status = "nothing to do right now"; // not the last thing it did
		trace();
		if (running) controls.apply();
	}

	/** In open water (not standing on anything) for a while: crossing water this way is slow; a boat is wanted. */
	private void noteSwimming() {
		LocalPlayer p = mc.player;
		if (p.isInWater() && !p.onGround() && p.getVehicle() == null) {
			if (++swimTicks > SWIM_TICKS_FOR_BOAT) wantBoat();
		} else {
			swimTicks = 0;
		}
	}

	/** Water (or ice) to cross: the gear planner makes a boat for the next couple of minutes' travel. */
	public void wantBoat() {
		boatWantedUntil = ticks + BOAT_WANT_TICKS;
	}

	/** A boat would help right now and none is carried. */
	public boolean boatWanted() {
		return ticks < boatWantedUntil && mc.player != null && !BoatTravel.hasBoat(mc.player);
	}

	/**
	 * On a trip that digs, is a pickaxe made from what's carried worth the digging time it saves? The trip behaviors
	 * (back to the items after a death, goto, fetch) run before gear, so gear never had a say there: walking back to
	 * its things after a death, the bot dug through stone by hand. Each planned path is weighed once.
	 */
	private void considerTripPickaxe() {
		LocalPlayer p = mc.player;
		if (tripPickaxe != null) {
			if (carriesPickaxeAsGood(p, tripPickaxe)) dropTripPickaxe(null);
			else if (ticks - tripPickaxeSince > TRIP_PICKAXE_GIVE_UP) dropTripPickaxe("it took too long");
			return;
		}
		if (!TRIP_BEHAVIORS.contains(activeBehavior) || ticks < tripPickaxeQuietUntil) return;
		DigTime dig = nav.digTime();
		if (dig == null || dig == tripWeighed) return;
		tripWeighed = dig;
		Item pick = makeablePickaxe(p);
		if (pick == null) return;
		int saved = dig.savedBy(pick), make = pickaxeMakeTicks(p);
		Chat.LOG.debug("trip pickaxe: {} saves {} ticks of digging (path share {}), making it ~{}", pick, saved, dig.share(), make);
		if (saved <= make + TRIP_PICKAXE_MARGIN) return;
		tripPickaxe = pick;
		tripPickaxeSince = ticks;
		Chat.info("Making a " + pick.getDefaultInstance().getHoverName().getString().toLowerCase() + " first: it saves about "
				+ saved / 20 + "s of digging on the way.");
	}

	/** The pickaxe gear makes before the trip goes on (the trip behaviors wait for it), or null. */
	public @Nullable Item tripPickaxe() {
		return tripPickaxe;
	}

	/**
	 * A trip found no way on with what's carried — by hand, stone costs so much that a plan through it never comes up
	 * within the search — and giving up costs the whole trip: a pickaxe made from what's carried may open the way.
	 * True when one is being made now (the trip behaviors wait, then plan again).
	 */
	public boolean pickaxeForStuckTrip() {
		if (tripPickaxe != null || ticks < tripPickaxeQuietUntil) return false;
		Item pick = makeablePickaxe(mc.player);
		if (pick == null) return false;
		tripPickaxe = pick;
		tripPickaxeSince = ticks;
		Chat.info("No way through with what's carried; making a " + pick.getDefaultInstance().getHoverName().getString().toLowerCase()
				+ " to dig one.");
		return true;
	}

	/** Stop waiting for the trip pickaxe: made ({@code why} null), or given up on for a while. */
	public void dropTripPickaxe(@Nullable String why) {
		if (tripPickaxe == null) return;
		if (why != null) {
			Chat.info("Going on without the " + tripPickaxe.getDefaultInstance().getHoverName().getString().toLowerCase() + " (" + why + ").");
			tripPickaxeQuietUntil = ticks + TRIP_PICKAXE_QUIET;
		}
		tripPickaxe = null;
	}

	/** Stone if three stone-type blocks and the wood are carried, else wood if the wood is; null if neither beats what's carried. */
	private static @Nullable Item makeablePickaxe(LocalPlayer p) {
		int planks = InvUtil.count(p, s -> s.is(ItemTags.PLANKS)) + 4 * InvUtil.count(p, s -> s.is(ItemTags.LOGS));
		int forStation = (InvUtil.count(p, s -> s.is(Items.STICK)) >= 2 ? 0 : 2) + (InvUtil.count(p, s -> s.is(Items.CRAFTING_TABLE)) > 0 ? 0 : 4);
		Item pick = InvUtil.count(p, s -> s.is(ItemTags.STONE_TOOL_MATERIALS)) >= 3 && planks >= forStation ? Items.STONE_PICKAXE
				: planks >= forStation + 3 ? Items.WOODEN_PICKAXE : null;
		return pick == null || carriesPickaxeAsGood(p, pick) ? null : pick;
	}

	/** A carried pickaxe (not about to break) mines stone at least as fast as {@code pick}. */
	private static boolean carriesPickaxeAsGood(LocalPlayer p, Item pick) {
		float speed = pick.getDefaultInstance().getDestroySpeed(Blocks.STONE.defaultBlockState());
		return InvUtil.find(p, s -> s.is(ItemTags.PICKAXES) && !InvUtil.nearlyBroken(s) && s.getDestroySpeed(Blocks.STONE.defaultBlockState()) >= speed) >= 0;
	}

	/** Rough ticks to make a pickaxe from carried materials: crafting, setting a table down and picking it up again. */
	private static int pickaxeMakeTicks(LocalPlayer p) {
		return InvUtil.count(p, s -> s.is(Items.CRAFTING_TABLE)) > 0 ? 160 : 200;
	}

	/** {@code r} is a prerequisite need (gear, build, make, travel), not a job requirement. */
	public boolean isNeed(Requirement r) {
		return needs.containsValue(r);
	}

	/** Record behavior/status/need changes (the last {@link #TRACE_SIZE}), for {@code !ac why}. */
	private void trace() {
		Requirement need = activeNeed();
		String line = activeBehavior + ": " + status + (need == null ? "" : " [need " + need + "]");
		if (line.equals(lastTrace)) return;
		lastTrace = line;
		String stamped = String.format("%d:%02d %s", ticks / 1200, ticks / 20 % 60, line);
		trace.addLast(stamped);
		while (trace.size() > TRACE_SIZE) trace.removeFirst();
		Chat.LOG.debug("trace {}", stamped);
	}

	/** Recent decisions, oldest first (minutes:seconds of bot time). */
	public List<String> recentTrace() {
		return List.copyOf(trace);
	}

	/** "Log off near players": another player (not a teammate) within the radius ends the session. */
	private boolean playerAlert() {
		Settings s = Settings.get();
		if (!s.logoffNearPlayers) return false;
		Set<String> team = new HashSet<>();
		for (Team.MemberView m : Team.roster()) team.add(m.name());
		for (Player other : mc.level.players()) {
			if (other == mc.player || team.contains(other.getName().getString())) continue;
			if (other.distanceTo(mc.player) > s.playerAlertRadius) continue;
			Chat.warn(other.getName().getString() + " came within " + (int) other.distanceTo(mc.player) + " blocks; logging off.");
			stop("player nearby");
			mc.schedule(() -> mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)); // outside the tick
			return true;
		}
		return false;
	}

	/** Log where we died; then respawn and go back for the items (setting), or stop. */
	private void onDeath() {
		if (respawnRequested) return; // the server hasn't respawned us yet
		LocalPlayer p = mc.player;
		BlockPos pos = p.blockPosition();
		Dim dim = dimension();
		String cause = p.getLastDamageSource() == null ? "unknown" : p.getLastDamageSource().getMsgId();
		if (p.getLastDamageSource() != null && p.getLastDamageSource().getEntity() != null) mobDeaths++;
		jobDeaths++;
		DeathLog.add(pos, dim, cause);
		Chat.warn("Died at " + pos.toShortString() + " in the " + dim.name().toLowerCase() + " (" + cause + ").");
		if (!Settings.get().recoverAfterDeath || mc.level.getLevelData().isHardcore()) {
			stop("died");
			return;
		}
		boolean burnt = p.isInLava() || cause.equals("lava") || cause.equals("onFire") || cause.equals("inFire");
		// cobblestone and a stone pickaxe aren't worth a walk back into what just killed us (or a Nether trip). By the
		// time we notice the death the server has often emptied the inventory already (a diamond pickaxe and the
		// netherite ingot were written off as "nothing worth going back for"): what was carried a moment ago counts
		boolean worth = carryingValuables || carriesValuables(p);
		carryingValuables = false;
		resetBehaviors();
		controls.releaseAll();
		needs.clear();
		// a pending trip back (the netherite lying where we died before) outlives a death that left nothing worth one
		if (!burnt && worth) setRecovery(new Recovery(pos, dim), 0);
		Chat.info(burnt ? "The items burnt; respawning and carrying on" + (recovery != null ? " (the earlier items are still waiting)." : " with the job.")
				: !worth ? "Nothing worth going back for here; respawning and carrying on" + (recovery != null ? " (going back for the earlier items)." : " with the job.")
				: "Respawning; going back for the items, then carrying on.");
		p.respawn();
		mc.gui.setScreen(null);
		respawnRequested = true;
	}

	/**
	 * Lost on death, worth going back for: a valuable ({@link #worthRecovering}), or iron worth a long mine — at least
	 * {@link #IRON_WORTH_A_TRIP} ingots in armor, tools, buckets and iron carried. Full iron armor with an iron sword
	 * and pickaxe was written off at a trial chamber, and the bot spent the next hour remaking it from wood and stone.
	 */
	private boolean carriesValuables(LocalPlayer p) {
		int iron = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (worthRecovering(s)) return true;
			iron += ironIn(s);
		}
		return iron >= IRON_WORTH_A_TRIP;
	}

	/** Lost on death, this alone is worth going back for: job loot, diamonds and up, enchanted gear, rare finds. */
	private boolean worthRecovering(ItemStack s) {
		if (s.isEmpty()) return false;
		if (isJobTarget(s) || s.isEnchanted() || s.is(ItemTags.SHULKER_BOXES)) return true;
		String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		return path.startsWith("diamond") || path.startsWith("netherite") || path.contains("smithing_template") || s.is(Items.ANCIENT_DEBRIS)
				|| s.is(Items.EMERALD) || s.is(Items.ELYTRA) || s.is(Items.TOTEM_OF_UNDYING) || s.is(Items.ENCHANTED_GOLDEN_APPLE);
	}

	/** Iron ingots that went into this stack (armor, tools, buckets) or that it is (ingots, raw iron, their blocks). */
	private static int ironIn(ItemStack s) {
		if (s.isEmpty()) return 0;
		Item item = s.getItem();
		if (item == Items.IRON_INGOT || item == Items.RAW_IRON) return s.getCount();
		if (item == Items.IRON_BLOCK || item == Items.RAW_IRON_BLOCK) return 9 * s.getCount();
		return IRON_COST.getOrDefault(item, 0) * s.getCount();
	}

	private static final Map<Item, Integer> IRON_COST = Map.ofEntries(Map.entry(Items.IRON_HELMET, 5), Map.entry(Items.IRON_CHESTPLATE, 8),
			Map.entry(Items.IRON_LEGGINGS, 7), Map.entry(Items.IRON_BOOTS, 4), Map.entry(Items.IRON_SWORD, 2), Map.entry(Items.IRON_PICKAXE, 3),
			Map.entry(Items.IRON_AXE, 3), Map.entry(Items.IRON_SHOVEL, 1), Map.entry(Items.IRON_HOE, 2), Map.entry(Items.BUCKET, 3),
			Map.entry(Items.WATER_BUCKET, 3), Map.entry(Items.LAVA_BUCKET, 3), Map.entry(Items.POWDER_SNOW_BUCKET, 3), Map.entry(Items.SHEARS, 2),
			Map.entry(Items.SHIELD, 1));
	/** Iron (in ingots) a death has to leave behind before it's worth the walk back. */
	private static final int IRON_WORTH_A_TRIP = 8;

	public @Nullable Recovery recovery() {
		return recovery;
	}

	public void setRecovery(@Nullable Recovery r) {
		setRecovery(r, 0);
	}

	/** A death spot whose items have already been loaded for {@code age} ticks (restored from a save). */
	public void setRecovery(@Nullable Recovery r, int age) {
		recovery = r;
		recoveryAge = age;
	}

	/** See {@link #recoveryAge}. */
	public int recoveryAge() {
		return recoveryAge;
	}

	private void tickFlight() {
		ElytraTravel.Status s = flight.tick(this);
		activeBehavior = "elytra";
		if (s == ElytraTravel.Status.RUNNING) return;
		if (s == ElytraTravel.Status.FAILED) flightRetryAt = ticks + FLIGHT_RETRY_TICKS;
		flight = null;
		nav.stop();
	}

	/**
	 * Fly to {@code target} instead of walking when an elytra trip is possible and worthwhile.
	 * Returns true if a flight started (the caller should stop navigating this tick).
	 */
	public boolean startFlight(BlockPos target) {
		return startFlight(target, null);
	}

	/** As {@link #startFlight(BlockPos)}; {@code lookout} ends the flight early when it reports true (exploring). */
	public boolean startFlight(BlockPos target, @Nullable BooleanSupplier lookout) {
		if (flight != null || ticks < flightRetryAt || !ElytraTravel.worthIt(this, target)) return false;
		flight = new ElytraTravel(target, lookout);
		nav.stop();
		Chat.info("Flying to " + target.toShortString() + ".");
		return true;
	}

	// ---- work control ---------------------------------------------------------------------------

	public void start(Job newJob) {
		Job mine = Team.shareJob(newJob); // with teammates, they take an even share
		begin(mine);
		List<Item> products = mine.requirements().stream().map(Requirement::product).filter(Objects::nonNull).toList();
		if (!products.isEmpty()) jobMaterials.addAll(RecipeBook.get().ingredientTree(products, 3));
		Chat.info("Job started: " + mine.requirements());
		TaskSave.save(this, true);
	}

	/**
	 * A go-to task: walk (through a portal when it's in the other dimension) to {@code to}, looking for it first when
	 * it's a structure. Runs like a job — gear, food, storage and recovery keep going — and ends like one.
	 */
	public void startGoto(Destination to) {
		begin(null);
		destination = to;
		Chat.info("Going to " + to.describe() + ".");
		TaskSave.save(this, true);
	}

	/** Utility mode: get items from a base's chests (through a portal if the base is in the other dimension). */
	public void startFetch(FetchOrder order) {
		begin(null);
		fetchOrder = order;
		Chat.info("Getting " + order.amount() + " " + order.item().getDefaultInstance().getHoverName().getString() + " from the base at "
				+ order.base().toShortString() + ".");
	}

	public @Nullable FetchOrder fetchOrder() {
		return fetchOrder;
	}

	/**
	 * Build something. With a resource job running (an auto farm) the job continues afterwards; otherwise
	 * the build is the whole task. A team leader shares user builds with its members.
	 */
	public void startBuild(BuildOrder order) {
		Team.Assignment share = order.purpose() == BuildOrder.Purpose.USER ? Team.shareBuild(order) : null;
		if (share != null) {
			startTeamBuild(order, share.role(), share.depot());
			return;
		}
		if (!running || job == null) begin(null);
		build = order;
		Chat.info("Building " + order.schematic().name() + " at " + order.origin().toShortString()
				+ (order.rotation().ordinal() == 0 ? "" : " rotated " + order.rotation().ordinal() * 90 + "°")
				+ (order.mirror() == Mirror.NONE ? "" : ", mirrored") + ".");
		TaskSave.save(this, true);
	}

	/** One part of a shared build (placer, digger or gatherer); {@code depot} is where materials are exchanged. */
	public void startTeamBuild(BuildOrder order, TeamRole role, BlockPos depot) {
		if (!running || job == null) begin(null);
		build = order;
		teamRole = role;
		this.depot = depot;
	}

	/** The current build order is finished or abandoned. */
	public void finishBuild() {
		clearBuild();
		needs.remove(NeedOwner.BUILD);
		nav.stop();
	}

	private void clearBuild() {
		build = null;
		buildPlan = null;
		buildMaterials.clear();
		buildPlaced = buildTotal = 0;
		buildDig = null;
		teamRole = TeamRole.SOLO;
		depot = null;
		teamNeeds = Map.of();
	}

	private void begin(@Nullable Job newJob) {
		job = newJob;
		destination = null;
		ending = null;
		finishing = null;
		clearBuild();
		jobMaterials.clear();
		needs.clear();
		fruitlessLegs.clear();
		Procedures.reset();
		resetBehaviors();
		recovery = null;
		jobDeaths = 0;
		fetchOrder = null;
		running = true;
		paused = false;
		mc.options.pauseOnLostFocus = false; // keep ticking while the window is in the background
	}

	public void stop(String reason) {
		if (!running) {
			// stopped in the few seconds before the saved task came back: it stays stopped — the stop was dropped
			// and the task resumed five seconds later anyway
			if (TaskSave.cancelRestore()) Chat.info("Stopped: the saved task won't resume.");
			return;
		}
		lastStop = reason;
		if (teamRole != TeamRole.SOLO || job != null) Team.stopAll();
		running = false;
		paused = false;
		destination = null;
		ending = null;
		finishing = null;
		clearBuild();
		jobMaterials.clear();
		needs.clear();
		resetBehaviors();
		recovery = null;
		fetchOrder = null;
		controls.releaseAll();
		threatNet.save();
		TaskSave.save(this, false);
		Chat.info("Stopped: " + reason);
		if (COMPLETE.contains(reason)) TaskTree.completed();
	}

	/** Why the bot last stopped ({@link #JOB_COMPLETE}, {@link #ARRIVED}, ... or something else). */
	public String lastStop() {
		return lastStop;
	}

	public void setPaused(boolean value) {
		paused = value;
		if (paused) {
			nav.stop();
			controls.releaseAll();
		}
	}

	public void onDisconnect() {
		running = false;
		paused = false;
		job = null;
		clearBuild();
		destination = null;
		ending = null;
		finishing = null;
		needs.clear();
		resetBehaviors();
		scanner.clear();
		recovery = null; // saved per world by TaskSave; restored when rejoining that world
		Stations.forgetWorld();
		threatNet.save();
	}

	private void resetBehaviors() {
		nav.stop();
		tripPickaxe = null;
		tripWeighed = null;
		flight = null;
		huntTargets = Set.of();
		setQuiet(false);
		procedureDimension = null;
		guardedWork = null;
		for (Behavior b : behaviors) b.reset();
	}

	// ---- prerequisites --------------------------------------------------------------------------

	/** Ask the collector for materials before the job ({@code null} clears the owner's need). */
	public void setNeed(NeedOwner owner, @Nullable Requirement r) {
		if (r == null) needs.remove(owner);
		else needs.put(owner, r);
	}

	public @Nullable Requirement need(NeedOwner owner) {
		return needs.get(owner);
	}

	/** The collector can't mine {@code r} with anything carried: the gear behavior answers on its next tick. */
	public void requestToolFor(Requirement r) {
		toolRequest = r;
	}

	/** The pending tool request, cleared (the gear behavior takes it). */
	public @Nullable Requirement takeToolRequest() {
		Requirement r = toolRequest;
		toolRequest = null;
		return r;
	}

	/** What the gear planner last decided ("tool for iron: gather 16 cobblestone"), for {@code !ac why}. */
	public void noteGear(String note) {
		gearNote = note;
	}

	public String gearNote() {
		return gearNote;
	}

	/** Highest-priority outstanding need, or null. */
	public @Nullable Requirement activeNeed() {
		for (NeedOwner o : NeedOwner.values()) {
			Requirement r = needs.get(o);
			if (r != null) return r;
		}
		return null;
	}

	/**
	 * What the collector works on: the highest-priority need that can be gathered in this dimension (else the
	 * highest-priority need, which the portal behavior travels for), else the job's next unmet requirement.
	 */
	public @Nullable Requirement currentTarget() {
		Requirement need = needHereFirst();
		if (need != null) return need;
		return job == null || mc.player == null ? null : job.nextUnmet(mc.player);
	}

	/** What the gear planner must equip for: build/make/travel needs first, else the job's next unmet requirement. */
	public @Nullable Requirement targetForGear() {
		Requirement need = needHereFirst(NeedOwner.GEAR);
		if (need != null) return need;
		return job == null || mc.player == null ? null : job.nextUnmet(mc.player);
	}

	/** Highest-priority need gatherable in this dimension, else the highest-priority need; {@code skip} owners ignored. */
	private @Nullable Requirement needHereFirst(NeedOwner... skip) {
		Dim here = dimension();
		Requirement elsewhere = null;
		for (NeedOwner o : NeedOwner.values()) {
			Requirement r = needs.get(o);
			if (r == null || List.of(skip).contains(o)) continue;
			if (r.dimension() == here) return r;
			if (elsewhere == null) elsewhere = r;
		}
		return elsewhere;
	}

	/** Gear/build/make need (materials a planner waits for; portal obsidian excluded), this dimension first. */
	public @Nullable Requirement materialNeed() {
		return needHereFirst(NeedOwner.TRAVEL);
	}

	/** Dimension the next gathering happens in (a material need, else the job's next mined requirement), or null. */
	public @Nullable Dim nextDimension() {
		return dimensionOf(materialNeed());
	}

	/**
	 * Like {@link #nextDimension()} but ignoring gear needs: what the gear planner prepares for (asking where its
	 * own materials are would make it chase its tail). With nothing to gather, where the task itself is headed.
	 */
	public @Nullable Dim workDimension() {
		if (procedureDimension != null) return procedureDimension; // a fortress or bastion chest: kit up for the Nether
		Dim work = dimensionOf(needHereFirst(NeedOwner.GEAR, NeedOwner.TRAVEL));
		return work != null ? work : tripDimension();
	}

	/**
	 * Where the task itself is headed: once its goal is met, the dimension of the base it ends at; before that, a
	 * go-to task's destination. Null when it isn't headed anywhere (the portal behavior then follows the gathering).
	 */
	public @Nullable Dim tripDimension() {
		if (finishing != null) {
			Bases.Base b = endBase();
			return b == null ? null : b.dim();
		}
		return destination == null ? null : destination.dim();
	}

	private @Nullable Dim dimensionOf(@Nullable Requirement need) {
		if (need != null) return need.dimension();
		Requirement next = job == null || mc.player == null ? null : job.nextUnmet(mc.player);
		return next == null || next.isCrafted() ? null : next.dimension();
	}

	/** An exploration leg for {@code requirement} ended without finding any source. */
	public void noteFruitlessLeg(String requirement) {
		fruitlessLegs.merge(requirement, 1, Integer::sum);
	}

	public void noteFound(String requirement) {
		fruitlessLegs.remove(requirement);
	}

	public int fruitlessLegs(String requirement) {
		return fruitlessLegs.getOrDefault(requirement, 0);
	}

	// ---- accessors ------------------------------------------------------------------------------

	public Minecraft mc() { return mc; }
	public LocalPlayer player() { return mc.player; }
	public ClientLevel level() { return mc.level; }
	public Controls controls() { return controls; }
	public WorldScanner scanner() { return scanner; }
	public Navigator nav() { return nav; }
	public ThreatNet threatNet() { return threatNet; }

	public boolean isRunning() { return running; }
	public boolean isPaused() { return paused; }
	public @Nullable Job job() { return job; }
	public @Nullable BuildOrder buildOrder() { return build; }
	public @Nullable Destination destination() { return destination; }
	public @Nullable Ending ending() { return ending; }

	/** Finish the current task at a base (null: where it ends); set right after starting it, kept for resuming. */
	public void setEnding(@Nullable Ending e) {
		ending = e;
		if (e != null) Chat.info("Then: " + e.describe() + ".");
		if (running) TaskSave.save(this, true);
	}

	/** The ending's base, or null (no ending, or no base by that name any more). */
	public Bases.@Nullable Base endBase() {
		return ending == null ? null : Bases.named(ending.base());
	}

	/**
	 * The task's own goal is met ({@code reason}: {@link #JOB_COMPLETE}, {@link #BUILD_COMPLETE}, {@link #FETCH_COMPLETE},
	 * {@link #ARRIVED}, {@link #GROUP_COMPLETE}): the end-of-job flow ({@link com.autocraft.behavior.FinishBehavior})
	 * takes over and stops the bot with this reason. Not running (a group's trip to its base, a resumed trip), it
	 * starts with just that flow.
	 */
	public void goalMet(String reason) {
		if (!running) begin(null);
		finishing = reason;
		nav.stop();
		TaskSave.save(this, true);
	}

	/** The reason the task will stop with once the end-of-job flow is through; null while its goal isn't met. */
	public @Nullable String finishing() { return finishing; }

	/** There is work to keep supplies (gear, food, storage) up for: a job, a build, a go-to task, or a task's trip to its base. */
	public boolean hasWork() {
		return job != null || build != null || destination != null || finishing != null;
	}

	/** The drowning reflex took over (once per spell of running out of air). */
	public void noteAirScare() {
		airScares++;
	}

	/** How often the bot has run out of air so far (see {@link #noteAirScare}). */
	public int airScares() {
		return airScares;
	}

	/** Deaths to mobs this session (see {@link #mobDeaths}). */
	public int mobDeaths() {
		return mobDeaths;
	}

	/** Deaths since the current work began (see {@link #jobDeaths}). */
	public int jobDeaths() {
		return jobDeaths;
	}

	/** Counts toward the current job or goes into it (never junk, never used as scaffolding while alternatives exist). */
	public boolean isJobItem(ItemStack s) {
		return job != null && !job.matching(s).isEmpty() || buildMaterials.contains(s.getItem()) || jobMaterials.contains(s.getItem());
	}

	/** Counts toward the job itself (the loot side work must never use up). */
	public boolean isJobTarget(ItemStack s) {
		return job != null && !job.matching(s).isEmpty();
	}

	/** Items the current build still needs (kept out of the junk pile). */
	public void setBuildMaterials(Set<Item> items) {
		buildMaterials.clear();
		buildMaterials.addAll(items);
	}

	/** The current build still needs this. */
	public boolean isBuildMaterial(ItemStack s) {
		return buildMaterials.contains(s.getItem());
	}

	public Dim dimension() { return Dim.of(mc.level); }

	/** The nearest base in this dimension (else home); null when no base is set. */
	public @Nullable BlockPos base() {
		Bases.Base b = nearestBase();
		return b == null ? null : b.pos();
	}

	public Dim baseDimension() {
		Bases.Base b = nearestBase();
		return b == null ? Dim.OVERWORLD : b.dim();
	}

	private Bases.@Nullable Base nearestBase() {
		return mc.level == null ? null : Bases.nearest(dimension(), mc.player == null ? null : mc.player.blockPosition());
	}

	/** Add a base here (or move the one at this spot); returns its name. */
	public String setBase(BlockPos pos) {
		return Bases.set(null, pos, dimension());
	}

	/** The plan being built (rotated, wood-swapped world positions), for the ghost overlay; null when not building. */
	public @Nullable BuildPlan buildPlan() { return buildPlan; }
	public void setBuildPlan(@Nullable BuildPlan plan) { buildPlan = plan; }

	/** Blocks placed / total of the current build (0/0 when not building). */
	public int buildPlaced() { return buildPlaced; }
	public int buildTotal() { return buildTotal; }
	public void setBuildProgress(int placed, int total) {
		buildPlaced = placed;
		buildTotal = total;
	}

	/** Blocks the current build must dig out per tool (pickaxe, shovel, axe); null when not building. */
	public int @Nullable [] buildDig() { return buildDig; }
	public void setBuildDig(int @Nullable [] dig) { buildDig = dig; }

	/** This bot's part of a shared build ({@link TeamRole#SOLO} when working alone). */
	public TeamRole teamRole() { return teamRole; }
	public void setTeamRole(TeamRole role) { teamRole = role; }
	/** Chest spot where a team exchanges materials; null when solo. */
	public @Nullable BlockPos depot() { return depot; }
	/** Gatherer: items to have on hand before the next delivery (sent by the leader). */
	public Map<Item, Integer> teamNeeds() { return teamNeeds; }
	public void setTeamNeeds(Map<Item, Integer> needs) { teamNeeds = Map.copyOf(needs); }
	public Set<EntityType<?>> huntTargets() { return huntTargets; }
	public void setHuntTargets(Set<EntityType<?>> mobs) { huntTargets = mobs; }

	/** Combat ran from this one as too strong even at full health: work keeps clear of it for a while. */
	public void dread(Mob m) { dreaded.put(m.getId(), ticks); }

	/**
	 * A mob combat ran from as unbeatable is within {@code radius} of {@code pos}, alive, and was run from in the last
	 * {@link #DREAD_TICKS}. A piglin brute guarding a bastion chest won every fight it was given; once it went back to
	 * its post the job walked straight back to that chest — six trips in ten minutes until the brute caught the bot.
	 */
	public boolean dreaded(BlockPos pos, double radius) {
		ClientLevel level = mc.level;
		if (level == null || dreaded.isEmpty()) return false;
		dreaded.entrySet().removeIf(e -> {
			Entity m = level.getEntity(e.getKey());
			return ticks - e.getValue() > DREAD_TICKS || m == null || !m.isAlive();
		});
		double r2 = radius * radius;
		for (int id : dreaded.keySet()) {
			Entity m = level.getEntity(id);
			if (m != null && m.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= r2) return true;
		}
		return false;
	}

	/** Ancient city mode: sneak everywhere, no sprinting, no mining or placing (except wool), never step on sculk. */
	public void setQuiet(boolean value) {
		controls.setQuiet(value);
		nav.setQuiet(value);
	}

	public boolean quiet() { return controls.quiet(); }

	/** The dimension a running procedure needs to be in (a fortress chest in the Nether); null = here. */
	public @Nullable Dim procedureDimension() { return procedureDimension; }
	public void setProcedureDimension(@Nullable Dim dim) { procedureDimension = dim; }

	/** The guarded structure the running procedure works in, or null ({@link #guardedWork}). */
	public LootSites.@Nullable Site guardedWork() { return guardedWork; }
	public void setGuardedWork(LootSites.@Nullable Site site) { guardedWork = site; }

	public String activeBehavior() { return activeBehavior; }
	public String status() { return status; }
	/** Short description of what the active behavior is doing, shown by {@code !ac status}. */
	public void setStatus(String s) {
		status = s;
		statusTick = ticks;
	}
}
