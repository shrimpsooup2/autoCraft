package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.control.ContainerOpener;
import com.autocraft.control.InvUtil;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.world.ChestMemory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jspecify.annotations.Nullable;

/**
 * Walk to a base, then shift-click stacks into the chests and barrels within {@link #BASE_RADIUS} of it, crediting the
 * job with what was stored. What goes in is the caller's {@link Selection}; which chest each stack goes into, the
 * {@link Chests} rule. DONE when nothing to store is left or every chest is full.
 */
public final class BaseDeposit {
	public enum Status { RUNNING, DONE, FAILED }

	/** Which chest a stack goes into. */
	public enum Chests {
		/** The nearest one with room: a quick unload in the middle of a job. */
		NEAREST,
		/** The one with the most free slots, filled before the next. */
		EMPTIEST,
		/**
		 * Per stack: the chest already holding the most of that item, else the one holding the most of its kind
		 * ({@link #kind}), else the emptiest. A full chest passes the stack on to the next choice, so different
		 * materials end up in different chests.
		 */
		SIMILAR
	}

	/** Whether a carried stack goes in. */
	@FunctionalInterface
	public interface Selection {
		boolean stores(Bot bot, LocalPlayer p, ItemStack s);
	}

	/** The job's own items: an inventory-full trip in the middle of the job. */
	public static final Selection JOB_ITEMS = (bot, p, s) -> bot.isJobTarget(s);

	private enum Phase { WALK, FIND, CHOOSE, APPROACH, OPEN, FILL }

	private enum Move { MOVED, NOTHING_HERE, FULL }

	private static final int BASE_RADIUS = 8;
	private static final int SINGLE_SLOTS = 27;

	private final BlockPos base;
	private final Chests rule;
	private final Selection what;
	private Phase phase = Phase.WALK;
	/** The base's containers, nearest first (one position per double chest). */
	private final List<BlockPos> chests = new ArrayList<>();
	/** What each holds, as last seen (on this trip, or remembered by {@link ChestMemory}); missing = never looked into. */
	private final Map<BlockPos, Map<Item, Integer>> contents = new HashMap<>();
	/** Free slots per container, as last seen (estimated from remembered counts until opened). */
	private final Map<BlockPos, Integer> free = new HashMap<>();
	/** Full, or out of reach, or not opening. */
	private final Set<BlockPos> closed = new HashSet<>();
	/** The chest being filled when nothing better is known (EMPTIEST, and SIMILAR's fallback): filled until full. */
	private @Nullable BlockPos emptiest;
	private @Nullable BlockPos target;
	private @Nullable ContainerOpener opener;
	private int deposited;
	private int visits;

	public BaseDeposit(BlockPos base, Chests rule, Selection what) {
		this.base = base.immutable();
		this.rule = rule;
		this.what = what;
	}

	public Status tick(Bot bot) {
		LocalPlayer p = bot.player();
		switch (phase) {
			case WALK -> {
				bot.setStatus("going to the base at " + base.toShortString() + " to store things");
				if (bot.startFlight(base)) return Status.RUNNING;
				bot.nav().setGoal(new Goal.Near(base, 3));
				Navigator.Status s = bot.nav().tick();
				if (s == Navigator.Status.FAILED) return Status.FAILED;
				if (s == Navigator.Status.ARRIVED) {
					bot.nav().stop();
					enter(Phase.FIND);
				}
			}
			case FIND -> {
				find(bot);
				if (chests.isEmpty()) {
					Chat.warn("No chests or barrels within " + BASE_RADIUS + " blocks of the base.");
					return Status.FAILED;
				}
				enter(Phase.CHOOSE);
			}
			case CHOOSE -> {
				int slot = nextToStore(bot, p);
				// contents changing under us (someone else at the chests) could have it walk between them forever
				if (slot < 0 || ++visits > chests.size() * 4 + 8) {
					Chat.LOG.debug("deposit: done (next slot {}, visits {}, chests {}, surveyed {}, closed {})", slot, visits, chests.size(),
							contents.size(), closed.size());
					return finish(bot, p);
				}
				target = choose(p.getInventory().getItem(slot));
				if (target == null) {
					Chat.LOG.debug("deposit: every chest full or shut (closed {}, surveyed {})", closed, contents.keySet());
					return finish(bot, p);
				}
				enter(Phase.APPROACH);
			}
			case APPROACH -> {
				bot.setStatus("storing things in the chest at " + target.toShortString());
				if (p.isWithinBlockInteractionRange(target, -0.5)) {
					bot.nav().stop();
					opener = new ContainerOpener(target, AbstractContainerMenu.class);
					enter(Phase.OPEN);
				} else {
					bot.nav().setGoal(new Goal.Adjacent(target));
					if (bot.nav().tick() == Navigator.Status.FAILED) shut(bot, p);
				}
			}
			case OPEN -> {
				ContainerOpener.Status s = opener.tick(bot);
				if (s == ContainerOpener.Status.FAILED) {
					Chat.LOG.debug("deposit: {} didn't open", target);
					shut(bot, p);
				} else if (s == ContainerOpener.Status.OPEN && p.containerMenu instanceof ChestMenu) {
					enter(Phase.FILL);
				} else if (s == ContainerOpener.Status.OPEN) {
					Chat.LOG.debug("deposit: {} opened as {}", target, p.containerMenu.getClass().getSimpleName());
					shut(bot, p); // not a storage menu after all
				}
			}
			case FILL -> {
				if (!(p.containerMenu instanceof ChestMenu menu)) return enter(Phase.CHOOSE);
				look(target, menu);
				Move m = moveOne(bot, p, menu);
				if (m == Move.FULL) {
					shut(bot, p);
				} else if (m == Move.NOTHING_HERE) {
					p.closeContainer();
					enter(Phase.CHOOSE);
				}
			}
		}
		return Status.RUNNING;
	}

	/** The base's containers, with what they're remembered to hold. */
	private void find(Bot bot) {
		ClientLevel level = bot.level();
		BlockPos feet = Navigator.feet(bot.player());
		chests.clear();
		for (BlockPos c : bot.scanner().findBlocks(s -> s.getBlock() instanceof ChestBlock || s.getBlock() instanceof BarrelBlock, 1, 64)) {
			if (c.distSqr(base) > BASE_RADIUS * BASE_RADIUS) continue;
			BlockPos key = ChestMemory.key(level, c);
			if (key == null || chests.contains(key)) continue;
			chests.add(key);
			Map<Item, Integer> known = ChestMemory.contents(level, key);
			if (known == null) continue;
			contents.put(key, new HashMap<>(known));
			int used = 0;
			for (Map.Entry<Item, Integer> e : known.entrySet()) used += (e.getValue() + e.getKey().getDefaultMaxStackSize() - 1) / e.getKey().getDefaultMaxStackSize();
			free.put(key, Math.max(0, slots(level, key) - used));
		}
		chests.sort(Comparator.comparingDouble(c -> c.distSqr(feet)));
	}

	private static int slots(ClientLevel level, BlockPos container) {
		BlockState s = level.getBlockState(container);
		return s.getBlock() instanceof ChestBlock && s.getValue(ChestBlock.TYPE) != ChestType.SINGLE ? 2 * SINGLE_SLOTS : SINGLE_SLOTS;
	}

	/** Read the open container: the choices go by what's really in it. */
	private void look(BlockPos container, ChestMenu menu) {
		Map<Item, Integer> items = new HashMap<>();
		int empty = 0;
		int size = menu.getRowCount() * 9;
		for (int slot = 0; slot < size; slot++) {
			ItemStack s = menu.getSlot(slot).getItem();
			if (s.isEmpty()) empty++;
			else items.merge(s.getItem(), s.getCount(), Integer::sum);
		}
		contents.put(container, items);
		free.put(container, empty);
	}

	/** First inventory slot holding something to store, or -1. */
	private int nextToStore(Bot bot, LocalPlayer p) {
		for (int i = 0; i < InvUtil.MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty() && what.stores(bot, p, s)) return i;
		}
		return -1;
	}

	/** Where to go next: a container not looked into yet (the choice needs to know them all), else the best one for {@code stack}. */
	private @Nullable BlockPos choose(ItemStack stack) {
		if (rule != Chests.NEAREST) {
			for (BlockPos c : chests) {
				if (!closed.contains(c) && !contents.containsKey(c)) return c;
			}
		}
		return best(stack);
	}

	/** The container {@code stack} goes into by the rule, or null when all are full. */
	private @Nullable BlockPos best(ItemStack stack) {
		return switch (rule) {
			case NEAREST -> chests.stream().filter(c -> !closed.contains(c)).findFirst().orElse(null);
			case EMPTIEST -> emptiest();
			case SIMILAR -> {
				Item item = stack.getItem();
				BlockPos same = most(m -> m.getOrDefault(item, 0));
				if (same != null) yield same;
				String kind = kind(item);
				BlockPos alike = kind == null ? null : most(m -> {
					int n = 0;
					for (Map.Entry<Item, Integer> e : m.entrySet()) {
						if (kind.equals(kind(e.getKey()))) n += e.getValue();
					}
					return n;
				});
				yield alike != null ? alike : emptiest();
			}
		};
	}

	/** The open, known container scoring highest (above 0), nearest on ties; null when none scores. */
	private @Nullable BlockPos most(ToIntFunction<Map<Item, Integer>> score) {
		BlockPos best = null;
		int bestScore = 0;
		for (BlockPos c : chests) {
			Map<Item, Integer> m = contents.get(c);
			if (m == null || closed.contains(c)) continue;
			int s = score.applyAsInt(m);
			if (s > bestScore) {
				bestScore = s;
				best = c;
			}
		}
		return best;
	}

	/** The container with the most free slots — kept until it's full, so stacks don't hop between equally empty chests. */
	private @Nullable BlockPos emptiest() {
		if (emptiest != null && !closed.contains(emptiest)) return emptiest;
		emptiest = null;
		int most = -1;
		for (BlockPos c : chests) {
			if (closed.contains(c)) continue;
			int f = free.getOrDefault(c, 0);
			if (f > most) {
				most = f;
				emptiest = c;
			}
		}
		return emptiest;
	}

	/** Shift-click one stack that belongs in the open container (by the rule, on what it holds now). */
	private Move moveOne(Bot bot, LocalPlayer p, ChestMenu menu) {
		if (rule != Chests.NEAREST && chests.stream().anyMatch(c -> !closed.contains(c) && !contents.containsKey(c))) {
			return Move.NOTHING_HERE; // still surveying: nothing goes in before every chest is known
		}
		for (int inv = 0; inv < InvUtil.MAIN_SIZE; inv++) {
			ItemStack stack = p.getInventory().getItem(inv);
			if (stack.isEmpty() || !what.stores(bot, p, stack) || !target.equals(best(stack))) continue;
			int slot = InvUtil.menuSlot(menu, p, inv);
			if (slot < 0) {
				Chat.LOG.debug("deposit: no menu slot for inventory slot {} ({})", inv, stack);
				continue;
			}
			ItemStack before = stack.copy();
			InvUtil.quickMove(p, slot);
			int moved = before.getCount() - p.getInventory().getItem(inv).getCount();
			if (moved <= 0) return Move.FULL;
			Job job = bot.job();
			List<Requirement> reqs = job == null ? List.of() : job.matching(before);
			if (!reqs.isEmpty()) job.addStored(reqs.getFirst(), moved);
			deposited += moved;
			return Move.MOVED;
		}
		return Move.NOTHING_HERE;
	}

	/** This container is full, out of reach or won't open: the next choice. */
	private void shut(Bot bot, LocalPlayer p) {
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		bot.nav().stop();
		closed.add(target);
		enter(Phase.CHOOSE);
	}

	private Status finish(Bot bot, LocalPlayer p) {
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		Job job = bot.job();
		Chat.info("Stored " + deposited + " items at the base." + (job == null ? "" : " " + job.progress(p)));
		return deposited > 0 || nextToStore(bot, p) < 0 ? Status.DONE : Status.FAILED;
	}

	private Status enter(Phase next) {
		phase = next;
		return Status.RUNNING;
	}

	/**
	 * What an item is alike with, for {@link Chests#SIMILAR}; null = only the same item matches. Checked in this order
	 * (rotten flesh is a drop, not food; redstone an ore, not stone):
	 * <ul>
	 * <li>"wood": logs, wood, stems, planks, saplings, leaves, sticks;</li>
	 * <li>"ore": ores, raw metals and their blocks, ingots, nuggets, gems, coal, redstone, lapis, quartz, amethyst
	 * shards, ancient debris, netherite scrap, metal and gem blocks;</li>
	 * <li>"drop": mob drops — wool, bones, bone meal, string, rotten flesh, spider eyes, gunpowder, pearls, blaze rods
	 * and powder, slime, leather, feathers, ink sacs, membranes, hide, ghast tears, magma cream, breeze rods, shells,
	 * prismarine;</li>
	 * <li>"farm": food, seeds and crops (wheat, sugar cane, pumpkins, melons, cactus, bamboo, kelp, cocoa, nether wart,
	 * mushrooms, eggs, hay);</li>
	 * <li>"stone": stone kinds and what's made of them — cobblestone, stone, deepslate, andesite, diorite, granite,
	 * tuff, calcite, dripstone, basalt, blackstone, netherrack, end stone, sandstone, obsidian (bricks, slabs, stairs
	 * too);</li>
	 * <li>"earth": dirt, grass blocks, podzol, mycelium, mud, clay, sand, gravel, flint, soul sand/soil, snow, ice.</li>
	 * </ul>
	 */
	static @Nullable String kind(Item item) {
		String k = KINDS.computeIfAbsent(item, BaseDeposit::kindOf);
		return k.isEmpty() ? null : k;
	}

	/** Looked up once per item: the chest choice asks for every stack against every item in every chest. */
	private static final Map<Item, String> KINDS = new HashMap<>();

	private static String kindOf(Item item) {
		ItemStack stack = item.getDefaultInstance();
		String path = BuiltInRegistries.ITEM.getKey(item).getPath();
		if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS) || path.endsWith("_sapling") || path.endsWith("_leaves") || path.equals("stick")) {
			return "wood";
		}
		if (path.endsWith("_ore") || path.startsWith("raw_") || path.endsWith("_ingot") || path.endsWith("_nugget") || ORES.contains(path)) return "ore";
		if (stack.is(ItemTags.WOOL) || DROPS.contains(path)) return "drop";
		if (stack.has(DataComponents.FOOD) || path.endsWith("_seeds") || CROPS.contains(path)) return "farm";
		// by name: "stone" is in glowstone and in the stonecutter, neither of them stone
		if (!path.contains("glowstone") && !path.equals("stonecutter") && STONES.stream().anyMatch(path::contains)) return "stone";
		if (EARTH.contains(path)) return "earth";
		return "";
	}

	private static final Set<String> ORES = Set.of("coal", "charcoal", "diamond", "emerald", "lapis_lazuli", "redstone", "quartz", "amethyst_shard",
			"ancient_debris", "netherite_scrap", "iron_block", "gold_block", "copper_block", "diamond_block", "emerald_block", "lapis_block",
			"redstone_block", "coal_block", "netherite_block");
	private static final Set<String> DROPS = Set.of("bone", "bone_meal", "string", "rotten_flesh", "spider_eye", "gunpowder", "ender_pearl",
			"blaze_rod", "blaze_powder", "slime_ball", "leather", "feather", "ink_sac", "glow_ink_sac", "phantom_membrane", "rabbit_hide",
			"rabbit_foot", "ghast_tear", "magma_cream", "breeze_rod", "shulker_shell", "prismarine_shard", "prismarine_crystals");
	private static final Set<String> CROPS = Set.of("wheat", "sugar_cane", "sugar", "pumpkin", "carved_pumpkin", "melon", "cactus", "bamboo", "kelp",
			"cocoa_beans", "nether_wart", "brown_mushroom", "red_mushroom", "egg", "hay_block");
	private static final List<String> STONES = List.of("cobblestone", "stone", "deepslate", "andesite", "diorite", "granite", "tuff", "calcite",
			"dripstone", "basalt", "blackstone", "netherrack", "obsidian");
	private static final Set<String> EARTH = Set.of("dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium", "mud", "packed_mud",
			"clay", "clay_ball", "sand", "red_sand", "gravel", "flint", "soul_sand", "soul_soil", "snowball", "snow_block", "ice", "packed_ice", "blue_ice");
}
