package com.autocraft.loot;

import com.autocraft.Chat;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * What mining blocks, killing mobs and opening structure chests gives, read from the game jar's own loot tables
 * ({@code data/minecraft/loot_table/blocks|entities|chests}): flint from gravel, string from cobwebs (not with
 * shears), leaves only with shears, gunpowder from creepers, echo shards in ancient city chests... Drops behind
 * special conditions (silk touch, being on fire, killed by a skeleton) are kept out or marked so planners only rely
 * on what the bot can actually do.
 */
public final class LootBook {
	/** Which tool a drop needs. */
	public enum ToolUse {
		/** Whatever mines it best. */
		ANY,
		/** Only with shears (leaves, vines, cobweb as a block). */
		SHEARS,
		/** Never with shears or silk touch (string from cobwebs, seeds from grass). */
		NO_SHEARS,
		/** Silk touch only: not something the bot can make. */
		SILK
	}

	public record BlockDrop(Block block, ToolUse tool, double chance) {}

	public record MobDrop(EntityType<?> type, double chance) {}

	/** A structure chest table ({@code ancient_city}, {@code village/village_weaponsmith}...) and the chance one chest has the item. */
	public record ChestDrop(String table, double chance) {}

	private static @Nullable LootBook instance;

	private final Map<Item, List<BlockDrop>> fromBlocks = new HashMap<>();
	private final Map<Item, List<MobDrop>> fromMobs = new HashMap<>();
	private final Map<Item, List<ChestDrop>> fromChests = new HashMap<>();
	/** Items some block drops only to a Silk Touch tool. */
	private final java.util.Set<Item> silkOnly = new java.util.HashSet<>();

	public static LootBook get() {
		if (instance == null) instance = load();
		return instance;
	}

	/** Blocks whose loot includes {@code item}, with the tool each needs. */
	public List<BlockDrop> blockSources(Item item) {
		return fromBlocks.getOrDefault(item, List.of());
	}

	/** Only a Silk Touch tool gets it from a block (turtle eggs, ice): the bot doesn't enchant. */
	public boolean silkTouchOnly(Item item) {
		return silkOnly.contains(item) && blockSources(item).isEmpty() && mobSources(item).isEmpty();
	}

	/** Mobs whose loot includes {@code item} when killed by a player. */
	public List<MobDrop> mobSources(Item item) {
		return fromMobs.getOrDefault(item, List.of());
	}

	/** Structure loot tables (chests, and {@code archaeology/...} for suspicious sand/gravel) that can hold {@code item}. */
	public List<ChestDrop> chestSources(Item item) {
		return fromChests.getOrDefault(item, List.of());
	}

	// ---- loading --------------------------------------------------------------------------------

	private static LootBook load() {
		LootBook book = new LootBook();
		Optional<Path> root = FabricLoader.getInstance().getModContainer("minecraft").flatMap(c -> c.findPath("data/minecraft/loot_table"));
		if (root.isEmpty()) {
			Chat.LOG.error("Vanilla loot tables not found in the game jar; mob drops and block loot are unknown");
			return book;
		}
		int blocks = book.readAll(root.get().resolve("blocks"), true);
		int mobs = book.readAll(root.get().resolve("entities"), false);
		int chests = book.readChests(root.get().resolve("chests"), "");
		chests += book.readChests(root.get().resolve("archaeology"), "archaeology/");
		Chat.LOG.info("Loaded {} block, {} entity and {} chest/archaeology loot tables", blocks, mobs, chests);
		return book;
	}

	private int readAll(Path dir, boolean blocks) {
		int n = 0;
		if (!Files.isDirectory(dir)) return 0;
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path f : (Iterable<Path>) files::iterator) {
				if (!f.toString().endsWith(".json")) continue;
				// entities/sheep/white.json belongs to the sheep
				Path rel = dir.relativize(f);
				String id = rel.getNameCount() > 1 ? rel.getName(0).toString() : rel.toString().replace(".json", "");
				try (Reader r = Files.newBufferedReader(f)) {
					JsonObject json = JsonParser.parseReader(r).getAsJsonObject();
					if (blocks) readBlock(id, json);
					else readEntity(id, json);
					n++;
				} catch (RuntimeException | IOException e) {
					Chat.LOG.debug("Skipping loot table {}: {}", f, e.toString());
				}
			}
		} catch (IOException e) {
			Chat.LOG.error("Could not list loot tables in {}", dir, e);
		}
		return n;
	}

	/** Chest tables keep their folder in the id ({@code village/village_armorer}); suspicious blocks get {@code archaeology/}. */
	private int readChests(Path dir, String prefix) {
		int n = 0;
		if (!Files.isDirectory(dir)) return 0;
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path f : (Iterable<Path>) files::iterator) {
				if (!f.toString().endsWith(".json")) continue;
				String id = prefix + dir.relativize(f).toString().replace('\\', '/').replace(".json", "");
				try (Reader r = Files.newBufferedReader(f)) {
					readChest(id, JsonParser.parseReader(r).getAsJsonObject());
					n++;
				} catch (RuntimeException | IOException e) {
					Chat.LOG.debug("Skipping loot table {}: {}", f, e.toString());
				}
			}
		} catch (IOException e) {
			Chat.LOG.error("Could not list loot tables in {}", dir, e);
		}
		return n;
	}

	/** Per pool: weighted picks, average rolls; the item's chance per chest combines the pools. */
	private void readChest(String id, JsonObject json) {
		if (!json.has("pools")) return;
		Map<Item, Double> missChance = new HashMap<>();
		for (JsonElement poolEl : json.getAsJsonArray("pools")) {
			JsonObject pool = poolEl.getAsJsonObject();
			if (conditions(pool).impossible) continue;
			double rolls = rolls(pool.get("rolls"));
			double total = 0;
			Map<Item, Double> weights = new HashMap<>();
			for (JsonElement entryEl : pool.getAsJsonArray("entries")) {
				JsonObject entry = entryEl.getAsJsonObject();
				double w = entry.has("weight") ? entry.get("weight").getAsDouble() : 1;
				total += w;
				if (!"minecraft:item".equals(entry.has("type") ? entry.get("type").getAsString() : "")) continue;
				Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(entry.get("name").getAsString())).orElse(Items.AIR);
				// a plain book that gets enchanted on the way out is an enchanted book in the chest
				if (item == Items.BOOK && entry.has("functions") && entry.get("functions").toString().contains("enchant")) item = Items.ENCHANTED_BOOK;
				if (item != Items.AIR) weights.merge(item, w, Double::sum);
			}
			if (total <= 0) continue;
			for (Map.Entry<Item, Double> e : weights.entrySet()) {
				double perPool = Math.pow(1 - e.getValue() / total, rolls);
				missChance.merge(e.getKey(), perPool, (a, b) -> a * b);
			}
		}
		missChance.forEach((item, miss) -> fromChests.computeIfAbsent(item, k -> new ArrayList<>()).add(new ChestDrop(id, 1 - miss)));
	}

	private static double rolls(@Nullable JsonElement e) {
		if (e == null) return 1;
		if (e.isJsonPrimitive()) return e.getAsDouble();
		JsonObject o = e.getAsJsonObject();
		if (o.has("min") && o.has("max")) return (o.get("min").getAsDouble() + o.get("max").getAsDouble()) / 2;
		if (o.has("value")) return o.get("value").getAsDouble();
		return 1;
	}

	private void readBlock(String id, JsonObject json) {
		Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(id)).orElse(Blocks.AIR);
		if (block == Blocks.AIR || !json.has("pools")) return; // powder snow, fire, portals: no drops at all
		for (JsonElement pool : json.getAsJsonArray("pools")) {
			Cond poolCond = conditions(pool.getAsJsonObject());
			if (poolCond.impossible) continue;
			for (JsonElement entry : pool.getAsJsonObject().getAsJsonArray("entries")) {
				walk(entry.getAsJsonObject(), poolCond, (item, c) -> {
					if (c.tool == ToolUse.SILK) silkOnly.add(item);
					if (c.special || c.tool == ToolUse.SILK) return;
					addBlock(item, new BlockDrop(block, c.tool, c.chance));
				});
			}
		}
	}

	private void readEntity(String id, JsonObject json) {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.withDefaultNamespace(id)).orElse(null);
		if (type == null || !json.has("pools")) return;
		for (JsonElement pool : json.getAsJsonArray("pools")) {
			Cond poolCond = conditions(pool.getAsJsonObject());
			if (poolCond.impossible || poolCond.special) continue;
			for (JsonElement entry : pool.getAsJsonObject().getAsJsonArray("entries")) {
				walk(entry.getAsJsonObject(), poolCond, (item, c) -> {
					if (c.special) return;
					List<MobDrop> list = fromMobs.computeIfAbsent(item, k -> new ArrayList<>());
					if (list.stream().noneMatch(d -> d.type() == type)) list.add(new MobDrop(type, c.chance));
				});
			}
		}
	}

	private void addBlock(Item item, BlockDrop drop) {
		List<BlockDrop> list = fromBlocks.computeIfAbsent(item, k -> new ArrayList<>());
		for (int i = 0; i < list.size(); i++) {
			BlockDrop d = list.get(i);
			if (d.block() != drop.block()) continue;
			// keep the easiest way to get it from this block
			if (rank(drop.tool()) < rank(d.tool()) || drop.chance() > d.chance()) list.set(i, drop);
			return;
		}
		list.add(drop);
	}

	private static int rank(ToolUse t) {
		return switch (t) {
			case ANY -> 0;
			case NO_SHEARS -> 1;
			case SHEARS -> 2;
			case SILK -> 3;
		};
	}

	private interface Sink {
		void accept(Item item, Cond c);
	}

	/** Conditions collected on the way down to an item entry. */
	private static final class Cond {
		ToolUse tool = ToolUse.ANY;
		double chance = 1;
		boolean special;
		boolean impossible;

		Cond copy() {
			Cond c = new Cond();
			c.tool = tool;
			c.chance = chance;
			c.special = special;
			c.impossible = impossible;
			return c;
		}

		Cond and(Cond o) {
			Cond c = copy();
			if (o.tool != ToolUse.ANY) c.tool = o.tool;
			c.chance *= o.chance;
			c.special |= o.special;
			c.impossible |= o.impossible;
			return c;
		}
	}

	private void walk(JsonObject entry, Cond inherited, Sink sink) {
		Cond here = inherited.and(conditions(entry));
		if (here.impossible) return;
		String type = entry.has("type") ? entry.get("type").getAsString() : "";
		switch (type) {
			case "minecraft:item" -> {
				Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(entry.get("name").getAsString())).orElse(Items.AIR);
				if (item != Items.AIR) sink.accept(item, here);
			}
			case "minecraft:alternatives" -> {
				// the first child whose conditions hold wins: later ones imply "not the earlier tools"
				boolean earlierNeedsShearsOrSilk = false;
				for (JsonElement child : entry.getAsJsonArray("children")) {
					Cond c = here.copy();
					if (earlierNeedsShearsOrSilk && c.tool == ToolUse.ANY) c.tool = ToolUse.NO_SHEARS;
					walk(child.getAsJsonObject(), c, sink);
					ToolUse own = conditions(child.getAsJsonObject()).tool;
					if (own == ToolUse.SHEARS || own == ToolUse.SILK) earlierNeedsShearsOrSilk = true;
				}
			}
			case "minecraft:sequence", "minecraft:group" -> {
				for (JsonElement child : entry.getAsJsonArray("children")) walk(child.getAsJsonObject(), here, sink);
			}
			default -> {
			}
		}
	}

	private static Cond conditions(JsonObject o) {
		Cond c = new Cond();
		if (o.has("condition")) apply(c, o.get("condition"));
		if (o.has("conditions")) {
			for (JsonElement e : o.getAsJsonArray("conditions")) apply(c, e);
		}
		return c;
	}

	private static void apply(Cond c, JsonElement cond) {
		if (cond.isJsonPrimitive()) {
			String ref = cond.getAsString();
			if (ref.endsWith("tool/can_shear")) c.tool = ToolUse.SHEARS;
			else if (ref.endsWith("tool/can_silk_touch")) c.tool = ToolUse.SILK;
			return;
		}
		if (!cond.isJsonObject()) return;
		JsonObject o = cond.getAsJsonObject();
		String type = o.has("type") ? o.get("type").getAsString() : o.has("condition") ? o.get("condition").getAsString() : "";
		switch (type) {
			case "minecraft:any_of", "minecraft:alternative" -> {
				// "shears or silk touch": shears are craftable, so that's the requirement
				JsonArray terms = o.getAsJsonArray("terms");
				boolean shears = false, silk = false;
				for (JsonElement t : terms) {
					Cond sub = new Cond();
					apply(sub, t);
					shears |= sub.tool == ToolUse.SHEARS;
					silk |= sub.tool == ToolUse.SILK;
				}
				if (shears) c.tool = ToolUse.SHEARS;
				else if (silk) c.tool = ToolUse.SILK;
			}
			case "minecraft:all_of" -> {
				// every term holds: their chances multiply (zombified piglin gold ingots: killed by a player and 2.5%;
				// read as a sure drop, the planner sent the bot hunting piglins for ingots)
				for (JsonElement t : o.getAsJsonArray("terms")) apply(c, t);
			}
			case "minecraft:inverted" -> {
				Cond sub = new Cond();
				apply(sub, o.get("term"));
				if (sub.tool == ToolUse.SHEARS || sub.tool == ToolUse.SILK) c.tool = ToolUse.NO_SHEARS;
			}
			case "minecraft:match_tool" -> {
				String text = o.toString();
				if (text.contains("minecraft:shears")) c.tool = ToolUse.SHEARS;
				else if (text.contains("silk_touch")) c.tool = ToolUse.SILK;
				else c.special = true; // a specific enchanted/other tool
			}
			case "minecraft:table_bonus" -> {
				JsonArray chances = o.getAsJsonArray("chances");
				if (!chances.isEmpty()) c.chance *= chances.get(0).getAsDouble();
			}
			case "minecraft:random_chance" -> {
				JsonElement ch = o.get("chance");
				if (ch != null && ch.isJsonPrimitive()) c.chance *= ch.getAsDouble();
			}
			case "minecraft:random_chance_with_enchanted_bonus" -> {
				JsonElement ch = o.get("unenchanted_chance");
				if (ch != null && ch.isJsonPrimitive()) c.chance *= ch.getAsDouble();
			}
			case "minecraft:entity_properties" -> c.special |= !o.toString().contains("cube_mob"); // small slimes/magma cubes: kill them all
			case "minecraft:damage_source_properties", "minecraft:weather_check", "minecraft:time_check",
					"minecraft:location_check", "minecraft:enchantment_active_check", "minecraft:value_check" -> c.special = true;
			case "minecraft:killed_by_player", "minecraft:survives_explosion", "minecraft:block_state_property" -> {
				// the bot kills by hand; explosions aren't ours; crop ages etc. are met by waiting for ripe blocks
			}
			default -> {
			}
		}
	}
}
