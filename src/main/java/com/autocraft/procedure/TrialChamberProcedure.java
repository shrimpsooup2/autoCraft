package com.autocraft.procedure;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.combat.Threats;
import com.autocraft.control.InvUtil;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.jspecify.annotations.Nullable;

/**
 * Trial chamber expeditions: find a chamber (tunnelling at chamber depth, or a remembered one), beat its trial
 * spawners for trial keys, open vaults with them (loot includes ominous bottles), and for the heavy core go
 * ominous: drink an ominous bottle (Bad Omen), walk in to get Trial Omen, beat the now-ominous spawners for
 * ominous keys and open ominous vaults until one gives a heavy core. Each vault opens once per player, so used
 * vaults are remembered and the bot moves on to another chamber when one is spent.
 */
final class TrialChamberProcedure implements Procedure {
	private static final int CHAMBER_Y = -30;
	private static final int TUNNEL_LEG = 32;
	private static final int SCAN_CHUNKS = 4;
	private static final int SPAWNER_STAND = 6;
	private static final int LOOT_TICKS = 20 * 6;
	private static final int MAX_LEGS = 60;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static @Nullable Set<Long> usedVaults;

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

	private static Set<Long> used() {
		if (usedVaults == null) usedVaults = load();
		return usedVaults;
	}

	private final class Run implements Task {
		private final Item item;
		private final int total;
		private @Nullable Direction heading;
		private @Nullable Goal leg;
		private int legs;
		private @Nullable BlockPos target;
		private int lootTicks = -1;
		private @Nullable BlockPos lootAt;
		private int drinkTicks;
		private int wait;

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

			List<BlockPos> spawners = bot.scanner().findBlocks(s -> s.is(Blocks.TRIAL_SPAWNER), SCAN_CHUNKS, 32);
			List<BlockPos> vaults = bot.scanner().findBlocks(s -> s.is(Blocks.VAULT), SCAN_CHUNKS, 32);
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
				if (p.hasEffect(MobEffects.BAD_OMEN)) return walkIn(bot, spawners); // Bad Omen turns into Trial Omen inside
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

		/** Stand by a spawner that isn't cooling down until it's beaten, then collect what it ejects. */
		private Status beatSpawner(Bot bot, List<BlockPos> spawners, boolean ominous) {
			BlockPos best = null;
			double bestDist = Double.MAX_VALUE;
			for (BlockPos s : spawners) {
				BlockState st = bot.level().getBlockState(s);
				if (!st.is(Blocks.TRIAL_SPAWNER)) continue;
				TrialSpawnerState state = st.getValue(TrialSpawnerBlock.STATE);
				if (state == TrialSpawnerState.COOLDOWN) continue;
				if (ominous && !st.getValue(TrialSpawnerBlock.OMINOUS) && state != TrialSpawnerState.WAITING_FOR_PLAYERS) continue;
				double d = s.distSqr(bot.player().blockPosition());
				if (d < bestDist) {
					bestDist = d;
					best = s;
				}
			}
			if (best == null) return spent(bot, "every trial spawner here is cooling down");
			target = best;
			BlockState st = bot.level().getBlockState(best);
			TrialSpawnerState state = st.getValue(TrialSpawnerBlock.STATE);
			if (state == TrialSpawnerState.EJECTING_REWARD || state == TrialSpawnerState.WAITING_FOR_REWARD_EJECTION) {
				lootAt = best;
				lootTicks = 0;
				return Status.RUNNING;
			}
			bot.setStatus((ominous ? "ominous " : "") + "trial spawner at " + best.toShortString() + ": " + state.getSerializedName().replace('_', ' '));
			if (best.distSqr(bot.player().blockPosition()) > SPAWNER_STAND * SPAWNER_STAND) {
				bot.nav().setGoal(new Goal.Near(best, SPAWNER_STAND - 2));
				if (bot.nav().tick() == Navigator.Status.FAILED) {
					bot.nav().stop();
					return spent(bot, "can't reach the trial spawner");
				}
			} else {
				bot.nav().stop(); // in range: mobs come to us; combat handles them
			}
			return Status.RUNNING;
		}

		private @Nullable BlockPos freeVault(Bot bot, List<BlockPos> vaults, boolean ominous) {
			for (BlockPos v : vaults) {
				BlockState st = bot.level().getBlockState(v);
				if (!st.is(Blocks.VAULT) || st.getValue(VaultBlock.OMINOUS) != ominous || used().contains(v.asLong())) continue;
				if (st.getValue(VaultBlock.STATE) == VaultState.INACTIVE) continue; // already opened by us (or not ready)
				return v;
			}
			return null;
		}

		private Status openVault(Bot bot, BlockPos vault, Item key) {
			bot.setStatus("opening the vault at " + vault.toShortString());
			switch (Actions.reach(bot, vault)) {
				case WORKING -> {
					return Status.RUNNING;
				}
				case FAILED -> {
					used().add(vault.asLong());
					save();
					return Status.RUNNING;
				}
				case DONE -> {
				}
			}
			Actions.useOn(bot, vault, s -> s.is(key));
			used().add(vault.asLong());
			save();
			lootAt = vault;
			lootTicks = 0;
			return Status.RUNNING;
		}

		/** Pick up everything ejected near the spawner/vault for a few seconds. */
		private Status loot(Bot bot) {
			if (++lootTicks > LOOT_TICKS) {
				lootTicks = -1;
				return Status.RUNNING;
			}
			bot.setStatus("collecting rewards");
			if (!Actions.pickUp(bot, s -> !s.isEmpty(), 8)) bot.nav().stop();
			return Status.RUNNING;
		}

		private Status walkIn(Bot bot, List<BlockPos> spawners) {
			if (spawners.isEmpty()) return explore(bot);
			BlockPos s = spawners.getFirst();
			bot.setStatus("walking into the chamber with Bad Omen");
			bot.nav().setGoal(new Goal.Near(s, 4));
			if (bot.nav().tick() == Navigator.Status.FAILED) return spent(bot, "can't get into the chamber");
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
			for (BlockPos v : bot.scanner().findBlocks(s -> s.is(Blocks.VAULT), SCAN_CHUNKS, 32)) used().add(v.asLong());
			save();
			leg = null;
			heading = heading == null ? Direction.fromYRot(bot.player().getYRot()) : heading.getClockWise();
			// walk well away so the same chamber isn't found again straight away
			BlockPos feet = Navigator.feet(bot.player());
			leg = new Goal.XYZ(feet.getX() + heading.getStepX() * 160, CHAMBER_Y, feet.getZ() + heading.getStepZ() * 160);
			return explore(bot);
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

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve("trial_chambers.json");
	}

	private static Set<Long> load() {
		Set<Long> out = new HashSet<>();
		try {
			if (Files.exists(file())) {
				long[] loaded = GSON.fromJson(Files.readString(file()), long[].class);
				if (loaded != null) for (long v : loaded) out.add(v);
			}
		} catch (IOException | JsonParseException e) {
			Chat.LOG.warn("Could not read trial_chambers.json", e);
		}
		return out;
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(used().stream().mapToLong(Long::longValue).toArray()));
		} catch (IOException e) {
			Chat.LOG.warn("Could not save trial_chambers.json", e);
		}
	}
}
