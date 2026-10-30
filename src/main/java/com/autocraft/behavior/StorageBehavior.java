package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.Settings;
import com.autocraft.control.ContainerOpener;
import com.autocraft.control.InvUtil;
import com.autocraft.control.Placement;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.world.BlockUtil;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Keeps room in the inventory. When nearly full: drop junk, stuff job items into bundles, empty them into
 * a carried shulker box, or haul them to the base chests (and walk back). Last resort: drop the least
 * valuable stacks; if everything is job loot with nowhere to go, stop.
 */
public final class StorageBehavior implements Behavior {
	private static final int THROWAWAY_KEEP = 128;
	private static final int RETRY_COOLDOWN = 20 * 60;
	/** Thrown a little upward the stack flies three or four blocks: past the block a player picks things up from. */
	private static final float THROW_PITCH = -30.0F;
	/** What the bot works with: fuel, sticks, stations, light, escapes and cures, arrows, rockets. */
	private static final Set<Item> SUPPLIES = Set.of(
			Items.STICK, Items.COAL, Items.CHARCOAL, Items.CRAFTING_TABLE, Items.FURNACE, Items.TORCH, Items.MILK_BUCKET,
			Items.TOTEM_OF_UNDYING, Items.ENDER_PEARL, Items.ARROW, Items.HONEY_BOTTLE, Items.FIREWORK_ROCKET);
	/** Materials kept for making gear, portals and rockets (worth carrying, but stored at a base like any loot). */
	private static final Set<Item> CRAFT_STOCK = Set.of(
			Items.RAW_IRON, Items.IRON_INGOT, Items.RAW_GOLD, Items.GOLD_INGOT, Items.DIAMOND, Items.FLINT, Items.OBSIDIAN,
			Items.PAPER, Items.GUNPOWDER, Items.SUGAR_CANE);
	private static final List<String> TOOL_TIERS = List.of("wooden_", "golden_", "stone_", "iron_", "diamond_", "netherite_");
	private static final List<String> TOOL_KINDS = List.of("_pickaxe", "_axe", "_shovel", "_hoe", "_sword");

	private enum Mode { IDLE, SHULKER, BASE, RETURN }

	private enum ShulkerPhase { PLACE, OPEN, FILL, PICKUP }

	private Mode mode = Mode.IDLE;
	private ShulkerPhase shulkerPhase = ShulkerPhase.PLACE;
	private @Nullable BlockPos shulkerPos;
	private @Nullable ContainerOpener opener;
	private @Nullable Harvest pickup;
	private @Nullable BaseDeposit deposit;
	private @Nullable BlockPos returnPos;
	private int modeTicks;
	private long tick;
	private long shulkerRetryAt;
	private long baseRetryAt;
	private boolean bundlesFull;

	@Override
	public String name() {
		return "storage";
	}

	@Override
	public void reset() {
		mode = Mode.IDLE;
		deposit = null;
		pickup = null;
		opener = null;
		bundlesFull = false;
	}

	@Override
	public boolean tick(Bot bot) {
		tick++;
		if (!bot.hasWork()) return false;
		Job job = bot.job();
		if (job == null) mode = Mode.IDLE; // bundles, shulkers and base trips are for job loot
		LocalPlayer p = bot.player();
		switch (mode) {
			case SHULKER -> {
				return tickShulker(bot, job);
			}
			case BASE -> {
				BaseDeposit.Status s = deposit.tick(bot);
				if (s == BaseDeposit.Status.RUNNING) return true;
				if (s == BaseDeposit.Status.FAILED) baseRetryAt = tick + RETRY_COOLDOWN;
				mode = s == BaseDeposit.Status.DONE && returnPos != null ? Mode.RETURN : Mode.IDLE;
				bot.nav().stop();
				return true;
			}
			case RETURN -> {
				bot.setStatus("walking back to work");
				if (bot.startFlight(returnPos)) return true;
				bot.nav().setGoal(new Goal.Near(returnPos, 2));
				if (bot.nav().tick() != Navigator.Status.MOVING) {
					mode = Mode.IDLE;
					bot.nav().stop();
				}
				return true;
			}
			case IDLE -> {
			}
		}

		if (InvUtil.freeSlots(p) > 1) return false;
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		int junk = findJunk(bot);
		if (junk >= 0) {
			bot.setStatus("dropping " + p.getInventory().getItem(junk).getHoverName().getString());
			return throwAway(bot, p, junk, null);
		}
		if (job != null && stuffBundle(p, job)) return true;
		if (job != null && tick >= shulkerRetryAt && InvUtil.find(p, StorageBehavior::shulkerWithSpace) >= 0 && hasJobItems(p, job)) {
			mode = Mode.SHULKER;
			shulkerPhase = ShulkerPhase.PLACE;
			modeTicks = 0;
			return true;
		}
		if (job != null && bot.base() != null && bot.baseDimension() == bot.dimension() && tick >= baseRetryAt) {
			deposit = new BaseDeposit(bot.base(), BaseDeposit.Chests.NEAREST, BaseDeposit.JOB_ITEMS);
			returnPos = Navigator.feet(p);
			mode = Mode.BASE;
			Chat.info("Inventory full, heading to base.");
			return true;
		}
		int cheap = leastValuable(bot);
		if (cheap >= 0) return throwAway(bot, p, cheap, null);
		Chat.warn("Inventory is full of job items and there is nowhere to store them.");
		bot.stop("inventory full");
		return true;
	}

	/**
	 * Throw a stack up and out toward the most open ground, once facing that way — away from {@code keepClear} (where
	 * we're headed) when given, or we'd walk over it. Dropped where it stood, it came straight back: a player picks up
	 * what lies within a block of it once two seconds have passed — waiting by a trial spawner, the bot threw the same
	 * four stacks out every two seconds for a quarter of an hour.
	 */
	static boolean throwAway(Bot bot, LocalPlayer p, int slot, @Nullable Vec3 keepClear) {
		bot.controls().face(openYaw(p, keepClear), THROW_PITCH);
		if (bot.controls().aimed()) InvUtil.dropStack(p, slot);
		return true;
	}

	/**
	 * The horizontal direction (of eight) with the longest run of open space for the feet and head, up to 4 blocks;
	 * one toward {@code keepClear} only when nothing else is open.
	 */
	private static float openYaw(LocalPlayer p, @Nullable Vec3 keepClear) {
		net.minecraft.world.level.Level level = p.level();
		BlockPos feet = Navigator.feet(p);
		float best = p.getYRot();
		int bestScore = Integer.MIN_VALUE;
		for (int i = 0; i < 8; i++) {
			float yaw = i * 45.0F;
			double dx = -Math.sin(Math.toRadians(yaw)), dz = Math.cos(Math.toRadians(yaw));
			int run = 0;
			for (int d = 1; d <= 4; d++) {
				BlockPos at = BlockPos.containing(feet.getX() + 0.5 + dx * d, feet.getY(), feet.getZ() + 0.5 + dz * d);
				if (!BlockUtil.isPassable(level, at, level.getBlockState(at)) || !BlockUtil.isPassable(level, at.above(), level.getBlockState(at.above()))) {
					break;
				}
				run++;
			}
			int score = keepClear != null && dx * (keepClear.x - p.getX()) + dz * (keepClear.z - p.getZ()) > 0 ? run - 5 : run;
			if (score > bestScore) {
				bestScore = score;
				best = yaw;
			}
		}
		return best;
	}

	// ---- junk -----------------------------------------------------------------------------------

	/** Worth carrying: job/need items and their ingredients, the bot's kit, crafting and portal materials. */
	public static boolean isKeeper(Bot bot, ItemStack s) {
		if (bot.isJobItem(s)) return true;
		for (Bot.NeedOwner owner : Bot.NeedOwner.values()) {
			Requirement need = bot.need(owner);
			if (need != null && need.counts().test(s)) return true;
		}
		if (isKit(s)) return true;
		return s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS) || CRAFT_STOCK.contains(s.getItem());
	}

	/**
	 * The bot's own kit: gear (anything with durability: tools, weapons, armor, shield), food, containers, working supplies,
	 * and with respawn beds on, a bed to put down (made from wool that may be a long way off).
	 */
	private static boolean isKit(ItemStack s) {
		Item item = s.getItem();
		return s.isDamageableItem() || s.has(DataComponents.FOOD) || item instanceof BoatItem || item instanceof BundleItem
				|| item instanceof BucketItem || isShulker(s) || SUPPLIES.contains(item) || s.is(ItemTags.BEDS) && Settings.get().respawnBeds;
	}

	/**
	 * Goes into the base chests when a task ends there with a deposit: what the task was for, shulker boxes and
	 * bundles it was packed into on the way, and everything else carried but the bot's kit ({@link #isKit}) and the
	 * stack of building blocks it pillars and bridges with ({@link #spareBlock}: only what's beyond
	 * {@link #THROWAWAY_KEEP}). Worn armor and the offhand are never touched.
	 */
	public static boolean storesAtBase(Bot bot, LocalPlayer p, ItemStack s) {
		if (s.isEmpty()) return false;
		if (bot.isJobTarget(s)) return true;
		if (isShulker(s)) return !shulkerEmpty(s);
		if (s.getItem() instanceof BundleItem) return BundleItem.getFullnessDisplay(s) > 0;
		if (isKit(s)) return false;
		if (spareBlock(bot, s)) return InvUtil.count(p, x -> spareBlock(bot, x)) - s.getCount() >= THROWAWAY_KEEP;
		return true;
	}

	private static boolean shulkerEmpty(ItemStack s) {
		ItemContainerContents contents = s.get(DataComponents.CONTAINER);
		return contents == null || contents.nonEmptyItemCopyStream().findAny().isEmpty();
	}

	/**
	 * Plain blocks (cobblestone, netherrack...) the bot keeps a stack of for pillaring and bridging — unless the job
	 * itself asks for them, or a build or a current need uses them. Being somewhere in the job's recipes doesn't count:
	 * netherrack is in the template-copying recipe, and kept as "job material" every broken block had the bot walking
	 * after the drop and carrying hundreds.
	 */
	private static boolean spareBlock(Bot bot, ItemStack s) {
		if (!InvUtil.isThrowaway(s) || bot.isJobTarget(s) || bot.isBuildMaterial(s)) return false;
		for (Bot.NeedOwner owner : Bot.NeedOwner.values()) {
			Requirement need = bot.need(owner);
			if (need != null && need.counts().test(s)) return false;
		}
		return true;
	}

	/**
	 * Worth picking up off the ground: anything not junk — keepers (but not a tool a carried one beats), and spare
	 * blocks while short of the stack kept ({@link #findJunk} drops the rest, so nothing it throws away comes back).
	 */
	public static boolean worthCarrying(Bot bot, LocalPlayer p, ItemStack s) {
		if (spareBlock(bot, s)) return InvUtil.count(p, x -> spareBlock(bot, x)) < THROWAWAY_KEEP;
		return isKeeper(bot, s) && (bot.isJobItem(s) || !outclassed(p, s, -1));
	}

	/**
	 * A stack to drop: surplus spare blocks first, then non-keepers and outclassed tools. Plain stone past the stack
	 * kept is worth nothing, while an item we don't know may be worth a lot: checked the other way round, breeze rods
	 * went over the side while the bot carried a thousand blocks of andesite. Job loot is never junk.
	 */
	static int findJunk(Bot bot) {
		LocalPlayer p = bot.player();
		int spare = InvUtil.count(p, s -> spareBlock(bot, s));
		int smallestSpare = -1;
		int junk = -1;
		// per kind of spare block: {stacks, items, slot of its fullest stack}
		Map<Item, int[]> kinds = new HashMap<>();
		for (int i = 0; i < InvUtil.MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.isEmpty()) continue;
			if (spareBlock(bot, s)) {
				if (smallestSpare < 0 || s.getCount() < p.getInventory().getItem(smallestSpare).getCount()) smallestSpare = i;
				int[] k = kinds.computeIfAbsent(s.getItem(), x -> new int[3]);
				if (k[0]++ == 0 || s.getCount() > p.getInventory().getItem(k[2]).getCount()) k[2] = i;
				k[1] += s.getCount();
			} else if (junk < 0 && (!isKeeper(bot, s) || !bot.isJobItem(s) && outclassed(p, s, i))) {
				junk = i;
			}
		}
		// odd small stacks go first, but a kind in several stacks loses a full one: throwing its part stack only made
		// room for the next block dug, which started another — netherrack went over the side every second for
		// minutes, slowing every walk, while six full stacks of it stayed
		int drop = smallestSpare;
		int[] most = kinds.values().stream().max(Comparator.comparingInt(k -> k[1])).orElse(null);
		if (most != null && most[0] > 1) drop = most[2];
		if (spare > THROWAWAY_KEEP && drop >= 0 && spare - p.getInventory().getItem(drop).getCount() >= THROWAWAY_KEEP / 2) return drop;
		return junk;
	}

	/**
	 * A plain (unenchanted) tool or sword that a carried, usable one of the same kind beats: a higher tier, or the same
	 * tier in an earlier slot ({@code slot} -1: lying on the ground, so any equal one carried beats it). Dead weight:
	 * three stone swords, two wooden pickaxes next to a diamond one.
	 */
	private static boolean outclassed(LocalPlayer p, ItemStack s, int slot) {
		String kind = toolKind(s);
		if (kind == null || s.isEnchanted()) return false;
		int tier = toolTier(s);
		for (int i = 0; i < InvUtil.MAIN_SIZE; i++) {
			ItemStack o = p.getInventory().getItem(i);
			if (i == slot || !kind.equals(toolKind(o)) || InvUtil.nearlyBroken(o)) continue;
			int t = toolTier(o);
			if (t > tier || t == tier && (slot < 0 || i < slot)) return true;
		}
		return false;
	}

	private static @Nullable String toolKind(ItemStack s) {
		if (s.isEmpty()) return null;
		String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		if (TOOL_TIERS.stream().noneMatch(path::startsWith)) return null;
		return TOOL_KINDS.stream().filter(path::endsWith).findFirst().orElse(null);
	}

	private static int toolTier(ItemStack s) {
		String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		for (int i = 0; i < TOOL_TIERS.size(); i++) {
			if (path.startsWith(TOOL_TIERS.get(i))) return i;
		}
		return -1;
	}

	/** Last resort before giving up: throwaway blocks, then plain crafting materials. Never gear, food or job loot. */
	private static int leastValuable(Bot bot) {
		LocalPlayer p = bot.player();
		Job job = bot.job();
		int fallback = -1;
		for (int i = 0; i < InvUtil.MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.isEmpty() || job != null && !job.matching(s).isEmpty()) continue;
			if (InvUtil.isThrowaway(s)) return i;
			if (s.isDamageableItem() || s.has(DataComponents.FOOD) || isShulker(s) || s.getItem() instanceof BundleItem) continue;
			if (fallback < 0) fallback = i;
		}
		return fallback;
	}

	private static boolean hasJobItems(LocalPlayer p, Job job) {
		for (Requirement r : job.requirements()) {
			if (InvUtil.count(p, r.counts()) > 0) return true;
		}
		return false;
	}

	// ---- bundles --------------------------------------------------------------------------------

	/** Carry a bundle onto a job stack (left click) so it absorbs as much as fits. */
	private boolean stuffBundle(LocalPlayer p, Job job) {
		if (bundlesFull) return false;
		int bundle = InvUtil.find(p, s -> s.getItem() instanceof BundleItem && BundleItem.getFullnessDisplay(s) < 1.0F);
		if (bundle < 0) return false;
		int loot = InvUtil.find(p, s -> !(s.getItem() instanceof BundleItem) && !isShulker(s) && !job.matching(s).isEmpty());
		if (loot < 0) return false;
		int bundleSlot = InvUtil.menuSlot(p.inventoryMenu, p, bundle);
		int lootSlot = InvUtil.menuSlot(p.inventoryMenu, p, loot);
		float before = BundleItem.getFullnessDisplay(p.getInventory().getItem(bundle));
		InvUtil.click(p, bundleSlot, 0, ContainerInput.PICKUP);
		InvUtil.click(p, lootSlot, 0, ContainerInput.PICKUP);
		InvUtil.click(p, bundleSlot, 0, ContainerInput.PICKUP);
		float after = BundleItem.getFullnessDisplay(p.getInventory().getItem(bundle));
		if (after <= before) bundlesFull = true; // nothing fit; don't loop on it
		return true;
	}

	// ---- shulker boxes --------------------------------------------------------------------------

	static boolean isShulker(ItemStack s) {
		return Block.byItem(s.getItem()) instanceof ShulkerBoxBlock;
	}

	private static boolean shulkerWithSpace(ItemStack s) {
		if (!isShulker(s)) return false;
		ItemContainerContents contents = s.get(DataComponents.CONTAINER);
		return contents == null || contents.nonEmptyItemCopyStream().count() < 27;
	}

	private boolean tickShulker(Bot bot, Job job) {
		LocalPlayer p = bot.player();
		modeTicks++;
		bot.setStatus("filling shulker box");
		switch (shulkerPhase) {
			case PLACE -> {
				if (shulkerPos != null && bot.mc().level.getBlockState(shulkerPos).getBlock() instanceof ShulkerBoxBlock) {
					opener = new ContainerOpener(shulkerPos, ShulkerBoxMenu.class);
					shulkerPhase = ShulkerPhase.OPEN;
					return true;
				}
				if (shulkerPos == null) {
					shulkerPos = Placement.besideFeet(bot, StorageBehavior::shulkerWithSpace, true);
					if (shulkerPos == null) return abandonShulker();
				} else if (modeTicks > 20) {
					return abandonShulker(); // placement never showed up
				}
			}
			case OPEN -> {
				ContainerOpener.Status s = opener.tick(bot);
				if (s == ContainerOpener.Status.FAILED) shulkerPhase = ShulkerPhase.PICKUP;
				else if (s == ContainerOpener.Status.OPEN) shulkerPhase = ShulkerPhase.FILL;
			}
			case FILL -> {
				if (!(p.containerMenu instanceof ShulkerBoxMenu menu) || !moveOneInto(p, menu, job)) {
					if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
					pickup = new Harvest(shulkerPos, StorageBehavior::isShulker);
					shulkerPhase = ShulkerPhase.PICKUP;
				}
			}
			case PICKUP -> {
				if (pickup == null) pickup = new Harvest(shulkerPos, StorageBehavior::isShulker);
				if (pickup.tick(bot) == Harvest.Status.RUNNING) return true;
				mode = Mode.IDLE;
				pickup = null;
				shulkerPos = null;
			}
		}
		return true;
	}

	private boolean abandonShulker() {
		shulkerRetryAt = tick + RETRY_COOLDOWN;
		mode = Mode.IDLE;
		shulkerPos = null;
		return true;
	}

	private static boolean moveOneInto(LocalPlayer p, ShulkerBoxMenu menu, Job job) {
		for (int inv = 0; inv < InvUtil.MAIN_SIZE; inv++) {
			ItemStack s = p.getInventory().getItem(inv);
			if (s.isEmpty() || isShulker(s) || job.matching(s).isEmpty()) continue;
			int slot = InvUtil.menuSlot(menu, p, inv);
			if (slot < 0) continue;
			int before = s.getCount();
			InvUtil.quickMove(p, slot);
			return p.getInventory().getItem(inv).getCount() < before;
		}
		return false;
	}
}
