package com.autocraft.world;

import com.autocraft.Chat;
import com.autocraft.goal.Requirement.Dim;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * What's in the chests and barrels we've looked into — by the bot or by you — per world, saved to
 * {@code config/autocraft/chests.json}. Any chest screen that opens is read (a double chest counts once), so
 * the bot knows what the base holds without walking there, and fetches from it when that beats gathering.
 */
public final class ChestMemory {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long OPENING_MS = 3000;
	private static final int RECORD_EVERY = 10;

	/** One container's last seen contents (item id → count). */
	private record Chest(int x, int y, int z, Dim dim, Map<String, Integer> items, long seen) {
		BlockPos pos() {
			return new BlockPos(x, y, z);
		}
	}

	private static @Nullable Map<String, Map<Long, Chest>> worlds;
	private static @Nullable BlockPos opening;
	private static long openingAt;
	private static @Nullable AbstractContainerMenu openMenu;
	private static @Nullable BlockPos openPos;
	private static int ticks;
	private static boolean dirty;

	private ChestMemory() {}

	/** The bot is about to open the container at {@code pos} (so the menu that appears belongs to it). */
	public static void opening(BlockPos pos) {
		opening = pos.immutable();
		openingAt = System.currentTimeMillis();
	}

	/** Every client tick: read the open chest screen, save when it closes. */
	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return;
		AbstractContainerMenu menu = p.containerMenu;
		if (menu instanceof ChestMenu chest) {
			if (menu != openMenu) {
				openMenu = menu;
				openPos = resolve(mc);
				ticks = 0;
			}
			if (openPos != null && ticks++ % RECORD_EVERY == 0) record(mc, openPos, chest);
		} else if (openMenu != null) {
			if (openPos != null && openMenu instanceof ChestMenu chest) record(mc, openPos, chest);
			openMenu = null;
			openPos = null;
			if (dirty) save();
		}
	}

	/** How many items matching {@code what} the containers within {@code radius} of {@code near} held when last seen. */
	public static int count(BlockPos near, int radius, Dim dim, Predicate<ItemStack> what) {
		int n = 0;
		for (Map.Entry<Item, Integer> e : items(near, radius, dim, what).entrySet()) n += e.getValue();
		return n;
	}

	/** Matching items (and counts) in the containers within {@code radius} of {@code near}. */
	public static Map<Item, Integer> items(BlockPos near, int radius, Dim dim, Predicate<ItemStack> what) {
		Map<Item, Integer> out = new HashMap<>();
		for (Chest c : world().values()) {
			if (c.dim != dim || c.pos().distSqr(near) > (double) radius * radius) continue;
			c.items.forEach((id, n) -> {
				Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
				if (item != Items.AIR && what.test(new ItemStack(item))) out.merge(item, n, Integer::sum);
			});
		}
		return out;
	}

	/** True once at least one container near {@code near} has been looked into. */
	public static boolean surveyed(BlockPos near, int radius, Dim dim) {
		return world().values().stream().anyMatch(c -> c.dim == dim && c.pos().distSqr(near) <= (double) radius * radius);
	}

	private static @Nullable BlockPos resolve(Minecraft mc) {
		BlockPos pos = opening != null && System.currentTimeMillis() - openingAt < OPENING_MS ? opening
				: mc.hitResult instanceof BlockHitResult hit ? hit.getBlockPos() : null;
		opening = null;
		return pos == null ? null : key(mc.level, pos);
	}

	/** The position a container is remembered under — one per double chest — or null when {@code pos} isn't a chest or barrel. */
	public static @Nullable BlockPos key(Level level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		if (s.getBlock() instanceof BarrelBlock) return pos.immutable();
		if (!(s.getBlock() instanceof ChestBlock)) return null;
		if (s.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return pos.immutable();
		BlockPos other = pos.relative(ChestBlock.getConnectedDirection(s));
		return pos.asLong() < other.asLong() ? pos.immutable() : other; // one key per double chest
	}

	/** What the container at {@code pos} (either half of a double chest) held when last seen, or null if never looked into. */
	public static @Nullable Map<Item, Integer> contents(Level level, BlockPos pos) {
		BlockPos key = key(level, pos);
		Chest c = key == null ? null : world().get(key.asLong());
		if (c == null || c.dim != Dim.of(level)) return null;
		Map<Item, Integer> out = new HashMap<>();
		c.items.forEach((id, n) -> {
			Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
			if (item != Items.AIR) out.merge(item, n, Integer::sum);
		});
		return out;
	}

	private static void record(Minecraft mc, BlockPos pos, ChestMenu menu) {
		Map<String, Integer> items = new HashMap<>();
		int size = menu.getRowCount() * 9;
		for (int slot = 0; slot < size; slot++) {
			ItemStack s = menu.getSlot(slot).getItem();
			if (!s.isEmpty()) items.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
		}
		Dim dim = Dim.of(mc.level);
		Chest before = world().get(pos.asLong());
		if (before != null && before.items.equals(items) && before.dim == dim) return;
		world().put(pos.asLong(), new Chest(pos.getX(), pos.getY(), pos.getZ(), dim, items, System.currentTimeMillis()));
		dirty = true;
	}

	private static Map<Long, Chest> world() {
		if (worlds == null) worlds = load();
		return worlds.computeIfAbsent(WorldKey.current(), k -> new HashMap<>());
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve("chests.json");
	}

	private static Map<String, Map<Long, Chest>> load() {
		Map<String, Map<Long, Chest>> out = new HashMap<>();
		try {
			if (Files.exists(file())) {
				Map<String, Map<Long, Chest>> loaded = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, Map<Long, Chest>>>() {}.getType());
				if (loaded != null) loaded.forEach((k, v) -> out.put(k, new HashMap<>(v)));
			}
		} catch (IOException | JsonParseException e) {
			Chat.LOG.warn("Could not read chests.json", e);
		}
		return out;
	}

	private static void save() {
		dirty = false;
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(worlds));
		} catch (IOException e) {
			Chat.LOG.warn("Could not save chests.json", e);
		}
	}
}
