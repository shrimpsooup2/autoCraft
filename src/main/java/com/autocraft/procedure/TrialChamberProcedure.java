package com.autocraft.procedure;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.combat.Threats;
import com.autocraft.control.InvUtil;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.loot.LootSites;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.entity.vault.VaultState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Trial chamber expeditions: find a chamber (tunnelling at chamber depth, or a remembered one), beat its trial
 * spawners for trial keys, open vaults with them (loot includes ominous bottles), and for the heavy core go
 * ominous: drink an ominous bottle (Bad Omen), walk in to get Trial Omen, beat the now-ominous spawners for
 * ominous keys and open ominous vaults until one gives a heavy core. Each vault opens once per player, so used
 * vaults are remembered (per world) and the bot moves on to another chamber when one is spent.
 */
final class TrialChamberProcedure implements Procedure {
	private static final int CHAMBER_Y = -30;
	private static final int TUNNEL_LEG = 32;
	private static final int SCAN_CHUNKS = 4;
	private static final int SPAWNER_STAND = 6;
	/** Rewards: picked up for at least this long (the server's state change takes a moment to show)… */
	private static final int LOOT_TICKS = 20;
	/** …and at most this long, however long items keep coming or lie out of reach. */
	private static final int LOOT_LIMIT = 20 * 20;
	/** A trial is only started (a waiting spawner woken) with this much health. */
	private static final float TRIAL_HEALTH = 18;
	private static final int MAX_LEGS = 60;
	/** A chamber left (nothing more for us there) isn't searched again for a trial spawner's cooldown (vanilla: 30 min). */
	private static final long LEFT_TICKS = 20 * 60 * 30;
	/**
	 * Spawners and vaults this close to a known part of a chamber left belong to it (its rooms are a few dozen blocks
	 * apart; two chambers never closer than about 200).
	 */
	private static final int LEFT_RADIUS = 48;
	/** A spawner or vault a path failed to reach is tried again after this long (from another side, other mobs gone). */
	private static final long UNREACHABLE_TICKS = 20 * 60 * 5;
	/** A vault looks for players once a second: still dark this long with us at its side, it's one we opened. */
	private static final int VAULT_WAKE_TICKS = 50;
	/** Our block this close to a vault's: inside its 4-block activation range with a margin. */
	private static final double VAULT_STAND = 3;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static @Nullable Map<String, Set<Long>> usedVaults;

	@Override
	public boolean makes(Item item) {
		return item == Items.HEAVY_CORE || item == Items.TRIAL_KEY || item == Items.OMINOUS_TRIAL_KEY || item == Items.OMINOUS_BOTTLE;
	}

	@Override
	public Map<Item, Integer> needs(Item item, int total) {
		return Map.of(); // the gear planner keeps weapons, armor and food up
	}

	@Override
	public double cost(Item item) {
		if (item == Items.TRIAL_KEY) return 60;
		if (item == Items.OMINOUS_BOTTLE) return 120;
		if (item == Items.OMINOUS_TRIAL_KEY) return 160;
		return 500;
	}

	@Override
	public Task start(Item item, int total) {
		return new Run(item, total);
	}

	@Override
	public LootSites.Site dangerousSite(Item item) {
		return LootSites.trialChamber();
	}

	/** Vaults this player already opened in this world. */
	private static Set<Long> used() {
		if (usedVaults == null) usedVaults = load();
		return usedVaults.computeIfAbsent(WorldKey.current(), k -> new HashSet<>());
	}

	private final class Run implements Task {
		private final Item item;
		private final int total;
		private @Nullable Direction heading;
		private @Nullable Goal leg;
		private int legs;
		private int lootTicks = -1;
		private @Nullable BlockPos lootAt;
		private int drinkTicks;
		private int wait;
		/** Parts of chambers left (spawners and vaults → game time left): out of the scan for {@link #LEFT_TICKS}. */
		private final Map<BlockPos, Long> left = new HashMap<>();
		/** Spawners and vaults a path failed to reach (game time): skipped for {@link #UNREACHABLE_TICKS}, not the chamber. */
		private final Map<BlockPos, Long> unreachable = new HashMap<>();
		/** Ticks at a vault's side waiting for it to light up (see openVault). */
		private int vaultWait;
		/** How far toward the ominous trials: 0 nothing, 1 an ominous bottle carried, 2 Bad or Trial Omen. */
		private int stage;

		Run(Item item, int total) {
			this.item = item;
			this.total = total;
		}

		@Override
		public Status tick(Bot bot) {
			Status s = step(bot);
			if (s != Status.RUNNING) bot.setHuntTargets(Set.of()); // spawner mobs are no longer the job
			return s;
		}

		private Status step(Bot bot) {
			LocalPlayer p = bot.player();
			if (InvUtil.count(p, s -> s.is(item)) >= total) return Status.DONE;
			if (bot.dimension() != Dim.OVERWORLD) return Status.FAILED;
			if (wait > 0) {
				wait--;
				return Status.RUNNING;
			}
			if (lootTicks >= 0) return loot(bot);

			long now = bot.level().getGameTime();
			// a bottle or an omen changes what a chamber holds for us: the ominous vaults of one left for having nothing
			// more without them are worth going back to
			int stageNow = p.hasEffect(MobEffects.TRIAL_OMEN) || p.hasEffect(MobEffects.BAD_OMEN) ? 2
					: InvUtil.count(p, s -> s.is(Items.OMINOUS_BOTTLE)) > 0 ? 1 : 0;
			if (stageNow > stage) left.clear();
			stage = stageNow;
			left.values().removeIf(at -> now - at > LEFT_TICKS);
			unreachable.values().removeIf(at -> now - at > UNREACHABLE_TICKS);
			// a spent chamber is out of the scan: found again every tick, it was "spent" every tick — the message
			// repeated many times a tick and the heading turned each time, so the bot never walked away
			List<BlockPos> spawners = bot.scanner().findBlocks(s -> s.is(Blocks.TRIAL_SPAWNER), SCAN_CHUNKS, 32).stream()
					.filter(s -> !unreachable.containsKey(s) && !leftBehind(s)).toList();
			List<BlockPos> vaults = bot.scanner().findBlocks(s -> s.is(Blocks.VAULT), SCAN_CHUNKS, 32).stream()
					.filter(v -> !used().contains(v.asLong()) && !unreachable.containsKey(v) && !leftBehind(v)).toList();
			if (spawners.isEmpty() && vaults.isEmpty()) return explore(bot);
			huntWhatSpawns(bot);

			boolean ominousGoal = item == Items.HEAVY_CORE || item == Items.OMINOUS_TRIAL_KEY;
			// the vault type that gives what we want, and the key it takes
			Boolean vaultOminous = item == Items.HEAVY_CORE ? Boolean.TRUE : item == Items.OMINOUS_BOTTLE ? Boolean.FALSE : null;
			if (vaultOminous != null) {
				Item key = vaultOminous ? Items.OMINOUS_TRIAL_KEY : Items.TRIAL_KEY;
				if (InvUtil.count(p, s -> s.is(key)) > 0) {
					BlockPos vault = freeVault(bot, vaults, vaultOminous);
					if (vault == null) return spent(bot, "no unopened " + (vaultOminous ? "ominous " : "") + "vaults left here");
					return openVault(bot, vault, key);
				}
			}
			if (ominousGoal && !p.hasEffect(MobEffects.TRIAL_OMEN)) {
				if (p.hasEffect(MobEffects.BAD_OMEN)) return walkIn(bot, spawners); // a normal spawner that sees us turns it into Trial Omen
				if (InvUtil.count(p, s -> s.is(Items.OMINOUS_BOTTLE)) > 0) return drink(bot);
				// no bottle: open normal vaults (they can give one) — needs normal keys
				if (InvUtil.count(p, s -> s.is(Items.TRIAL_KEY)) > 0) {
					BlockPos vault = freeVault(bot, vaults, false);
					if (vault != null) return openVault(bot, vault, Items.TRIAL_KEY);
					return spent(bot, "no ominous bottle and no unopened vaults left here");
				}
				return beatSpawner(bot, spawners, false);
			}
			return beatSpawner(bot, spawners, ominousGoal);
		}

		/** Mobs around the spawners are the job now: combat fights them instead of avoiding them. */
		private void huntWhatSpawns(Bot bot) {
			Set<EntityType<?>> types = new HashSet<>();
			for (Mob m : Threats.collect(bot, 20)) types.add(m.getType());
			bot.setHuntTargets(types);
		}

		/**
		 * Stand by the nearest spawner that has a trial for us until it's beaten, then collect what it ejects. With
		 * Trial Omen ({@code ominous}) a normal spawner turns ominous the moment it sees us, cooling down or not — and
		 * forgets who beat it (vanilla TrialSpawnerStateData#tryDetectPlayers): the ones beaten for keys before the
		 * bottle are fresh ominous trials. Only an ominous one cooling down has nothing.
		 */
		private Status beatSpawner(Bot bot, List<BlockPos> spawners, boolean ominous) {
			BlockPos best = null;
			double bestDist = Double.MAX_VALUE;
			long now = bot.level().getGameTime();
			for (BlockPos s : spawners) {
				BlockState st = bot.level().getBlockState(s);
				if (!st.is(Blocks.TRIAL_SPAWNER)) continue;
				boolean converts = ominous && !st.getValue(TrialSpawnerBlock.OMINOUS);
				if (st.getValue(TrialSpawnerBlock.STATE) == TrialSpawnerState.COOLDOWN && !converts) continue;
				double d = s.distSqr(bot.player().blockPosition());
				if (d < bestDist) {
					bestDist = d;
					best = s;
				}
			}
			if (best == null) return spent(bot, "every trial spawner here is beaten or cooling down");
			BlockState st = bot.level().getBlockState(best);
			TrialSpawnerState state = st.getValue(TrialSpawnerBlock.STATE);
			if (state == TrialSpawnerState.EJECTING_REWARD || state == TrialSpawnerState.WAITING_FOR_REWARD_EJECTION) {
				lootAt = best;
				lootTicks = 0;
				return Status.RUNNING;
			}
			// a trial is waves of mobs until it's beaten: started at 16 health it lost all of it in a minute. Waking one
			// (in its sight) waits for health; carried food (eaten while it waits) or a full bar brings it back
			LocalPlayer p = bot.player();
			if (state == TrialSpawnerState.WAITING_FOR_PLAYERS && p.getHealth() < TRIAL_HEALTH
					&& (p.getFoodData().getFoodLevel() >= 18 || com.autocraft.behavior.EatReflex.hasFood(p))) {
				bot.nav().stop();
				bot.setStatus("healing before the trial at " + best.toShortString());
				return Status.RUNNING;
			}
			bot.setStatus((ominous ? "ominous " : "") + "trial spawner at " + best.toShortString() + ": " + state.getSerializedName().replace('_', ' '));
			boolean seen = seesUs(bot, best);
			if (best.distSqr(bot.player().blockPosition()) > SPAWNER_STAND * SPAWNER_STAND || !seen) {
				// it wakes only for a player it can see: four blocks off through the chamber wall (tunnelled up to it from
				// outside) the bot waited by a spawner that never started — then right beside it
				bot.nav().setGoal(seen ? new Goal.Near(best, SPAWNER_STAND - 2) : new Goal.Adjacent(best));
				if (bot.nav().tick() == Navigator.Status.FAILED) {
					// that spawner, not the chamber: given up as a whole, the chamber's other spawners went unfought
					bot.nav().stop();
					unreachable.put(best.immutable(), now);
					Chat.LOG.info("Trial chamber: can't reach the trial spawner at {}; trying another", best.toShortString());
				}
			} else {
				bot.nav().stop(); // in range: mobs come to us; combat handles them
			}
			return Status.RUNNING;
		}

		/**
		 * The spawner's line of sight reaches our eyes. As vanilla's player detector: a ray from our eyes to its centre
		 * whose first block is the spawner itself. Cast the other way it starts inside the spawner and always "hit" it —
		 * the bot counted itself seen through the chamber wall and waited nine minutes for a spawner that never woke.
		 */
		private boolean seesUs(Bot bot, BlockPos spawner) {
			BlockHitResult hit = bot.level().clip(new ClipContext(bot.player().getEyePosition(), Vec3.atCenterOf(spawner), ClipContext.Block.VISUAL,
					ClipContext.Fluid.NONE, bot.player()));
			return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(spawner);
		}

		/**
		 * The nearest vault of the kind wanted that we haven't opened. Whether it's lit says nothing from afar: a vault
		 * lights up only for a player at its side who hasn't opened it — read as "opened" from across the chamber,
		 * every vault there was written off and the bot left with its keys, after one spawner (see openVault).
		 */
		private @Nullable BlockPos freeVault(Bot bot, List<BlockPos> vaults, boolean ominous) {
			for (BlockPos v : vaults) {
				BlockState st = bot.level().getBlockState(v);
				if (st.is(Blocks.VAULT) && st.getValue(VaultBlock.OMINOUS) == ominous && !used().contains(v.asLong())) return v;
			}
			return null;
		}

		private Status openVault(Bot bot, BlockPos vault, Item key) {
			bot.setStatus("opening the vault at " + vault.toShortString());
			LocalPlayer p = bot.player();
			// it lights up for a player whose block is within 4 of it (vanilla's PlayerDetector), not one in arm's reach:
			// two blocks up and three and a half across, unopened vaults stayed dark and were counted as opened
			if (!Navigator.feet(p).closerThan(vault, VAULT_STAND) || !p.isWithinBlockInteractionRange(vault, -0.5)) {
				bot.nav().setGoal(new Goal.Adjacent(vault));
				if (bot.nav().tick() == Navigator.Status.FAILED) {
					bot.nav().stop();
					unreachable.put(vault.immutable(), bot.level().getGameTime());
				}
				vaultWait = 0;
				return Status.RUNNING;
			}
			bot.nav().stop();
			VaultState state = bot.level().getBlockState(vault).getValue(VaultBlock.STATE);
			if (state == VaultState.INACTIVE) {
				// at its side and still dark after it has looked for players (once a second): opened by us already
				if (++vaultWait < VAULT_WAKE_TICKS) return Status.RUNNING;
				vaultWait = 0;
				used().add(vault.asLong());
				save();
				return Status.RUNNING;
			}
			vaultWait = 0;
			if (state != VaultState.ACTIVE) return Status.RUNNING; // unlocking or ejecting: let it finish
			Actions.useOn(bot, vault, s -> s.is(key));
			used().add(vault.asLong());
			save();
			lootAt = vault;
			lootTicks = 0;
			return Status.RUNNING;
		}

		/**
		 * Pick up what the spawner or vault at {@link #lootAt} turns out: while it's still at it, then until nothing's
		 * left lying around. A vault unlocks for 14 ticks and then ejects an item a second — a full reward takes six
		 * seconds or more: done after a fixed six, the bot walked off before anything came out.
		 */
		private Status loot(Bot bot) {
			lootTicks++;
			BlockState st = bot.level().getBlockState(lootAt);
			boolean ejecting = st.is(Blocks.VAULT) ? st.getValue(VaultBlock.STATE) == VaultState.UNLOCKING || st.getValue(VaultBlock.STATE) == VaultState.EJECTING
					: st.is(Blocks.TRIAL_SPAWNER) && (st.getValue(TrialSpawnerBlock.STATE) == TrialSpawnerState.EJECTING_REWARD
							|| st.getValue(TrialSpawnerBlock.STATE) == TrialSpawnerState.WAITING_FOR_REWARD_EJECTION);
			bot.setStatus("collecting rewards");
			boolean lying = Actions.pickUp(bot, s -> !s.isEmpty(), 8);
			if (!lying) bot.nav().stop();
			// the first ticks may still show it as it was (the server's change takes a moment to arrive)
			if (lootTicks < LOOT_TICKS || lootTicks < LOOT_LIMIT && (ejecting || lying)) return Status.RUNNING;
			lootTicks = -1;
			return Status.RUNNING;
		}

		/**
		 * Bad Omen turns into Trial Omen when a normal (not yet ominous) trial spawner sees us within its range — not by
		 * walking into the chamber: into the sight of the nearest one, then wait a moment for it to look (once a second).
		 */
		private Status walkIn(Bot bot, List<BlockPos> spawners) {
			BlockPos feet = Navigator.feet(bot.player());
			BlockPos s = spawners.stream().filter(x -> {
				BlockState st = bot.level().getBlockState(x);
				return st.is(Blocks.TRIAL_SPAWNER) && !st.getValue(TrialSpawnerBlock.OMINOUS);
			}).min(java.util.Comparator.comparingDouble(x -> x.distSqr(feet))).orElse(null);
			if (s == null) return spent(bot, "no trial spawner here to turn the Bad Omen into Trial Omen");
			bot.setStatus("walking into a trial spawner's sight with Bad Omen");
			boolean seen = seesUs(bot, s);
			if (seen && s.distSqr(feet) <= SPAWNER_STAND * SPAWNER_STAND) {
				bot.nav().stop();
				return Status.RUNNING;
			}
			bot.nav().setGoal(seen ? new Goal.Near(s, SPAWNER_STAND - 2) : new Goal.Adjacent(s));
			if (bot.nav().tick() == Navigator.Status.FAILED) {
				bot.nav().stop();
				unreachable.put(s.immutable(), bot.level().getGameTime());
			}
			return Status.RUNNING;
		}

		private Status drink(Bot bot) {
			LocalPlayer p = bot.player();
			bot.setStatus("drinking an ominous bottle");
			if (!InvUtil.select(p, s -> s.is(Items.OMINOUS_BOTTLE))) return Status.RUNNING;
			bot.controls().face(p.getYRot(), -90.0F);
			if (!p.isUsingItem()) bot.controls().useItem(InteractionHand.MAIN_HAND);
			bot.controls().holdUse();
			if (++drinkTicks > 60) {
				drinkTicks = 0;
				wait = 10;
			}
			return Status.RUNNING;
		}

		/** This chamber has nothing more for us now: look for another one (or stop if we've looked long enough). */
		private Status spent(Bot bot, String why) {
			Chat.info("Trial chamber: " + why + "; looking for another chamber.");
			// the chamber is left behind for a while — not every vault in it written off as opened (25 of 26 never were)
			// nor every spawner as cooling: its parts in view, and those found near them on the way out (leftBehind)
			long now = bot.level().getGameTime();
			BlockPos feet = Navigator.feet(bot.player());
			left.put(feet, now);
			for (BlockPos s : bot.scanner().findBlocks(st -> st.is(Blocks.TRIAL_SPAWNER) || st.is(Blocks.VAULT), SCAN_CHUNKS, 64)) {
				left.put(s.immutable(), now);
			}
			leg = null;
			heading = heading == null ? Direction.fromYRot(bot.player().getYRot()) : heading.getClockWise();
			// walk well away so the same chamber isn't found again straight away
			leg = new Goal.XYZ(feet.getX() + heading.getStepX() * 160, CHAMBER_Y, feet.getZ() + heading.getStepZ() * 160);
			return explore(bot);
		}

		/**
		 * Part of a chamber we left lately: near a spawner or vault of it known then. Taken in itself, so a chamber
		 * bigger than the view when it was left is all left (its far end came into view on the way out, and was "new").
		 */
		private boolean leftBehind(BlockPos pos) {
			Long at = null;
			for (Map.Entry<BlockPos, Long> e : left.entrySet()) {
				if (e.getKey().distSqr(pos) < LEFT_RADIUS * LEFT_RADIUS) {
					at = e.getValue();
					break;
				}
			}
			if (at == null) return false;
			left.putIfAbsent(pos.immutable(), at);
			return true;
		}

		/** Tunnel at chamber depth, scanning for trial spawners and vaults. */
		private Status explore(Bot bot) {
			if (legs > MAX_LEGS) return Status.FAILED;
			BlockPos feet = Navigator.feet(bot.player());
			if (heading == null) heading = Direction.fromYRot(bot.player().getYRot());
			if (leg == null) {
				leg = Math.abs(feet.getY() - CHAMBER_Y) > 3 ? new Goal.YLevel(CHAMBER_Y)
						: new Goal.XYZ(feet.getX() + heading.getStepX() * TUNNEL_LEG, CHAMBER_Y, feet.getZ() + heading.getStepZ() * TUNNEL_LEG);
			}
			bot.setStatus("searching for a trial chamber");
			bot.nav().setGoal(leg);
			Navigator.Status s = bot.nav().tick();
			if (s == Navigator.Status.ARRIVED || s == Navigator.Status.FAILED) {
				if (s == Navigator.Status.FAILED) heading = heading.getClockWise();
				leg = null;
				legs++;
				bot.nav().stop();
			}
			return Status.RUNNING;
		}
	}

	private static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("autocraft");
	}

	/** Vaults opened, per world. */
	private static Path file() {
		return dir().resolve("trial_vaults.json");
	}

	private static Map<String, Set<Long>> load() {
		Map<String, Set<Long>> out = new HashMap<>();
		try {
			// the list before this one also holds every vault in view whenever a chamber was left (25 of 26 in one
			// world never opened), and can't tell which: dropped — an opened vault is known again at its side
			if (Files.deleteIfExists(dir().resolve("trial_chambers.json"))) {
				Chat.LOG.info("Dropped trial_chambers.json: it counted unopened vaults as opened; opened ones are checked at the vault");
			}
			if (Files.exists(file())) {
				Map<String, long[]> loaded = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, long[]>>() {}.getType());
				if (loaded != null) {
					loaded.forEach((world, vaults) -> {
						Set<Long> set = new HashSet<>();
						for (long v : vaults) set.add(v);
						out.put(world, set);
					});
				}
			}
		} catch (IOException | JsonParseException e) {
			Chat.LOG.warn("Could not read trial_vaults.json", e);
		}
		return out;
	}

	private static void save() {
		Map<String, long[]> out = new HashMap<>();
		used(); // make sure it's loaded
		usedVaults.forEach((world, vaults) -> out.put(world, vaults.stream().mapToLong(Long::longValue).toArray()));
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(out));
		} catch (IOException e) {
			Chat.LOG.warn("Could not save trial_vaults.json", e);
		}
	}
}
