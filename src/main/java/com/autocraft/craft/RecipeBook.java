package com.autocraft.craft;

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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Every vanilla crafting (shaped / shapeless) and furnace recipe, read from the game jar's own data files
 * ({@code data/minecraft/recipe/*.json}) the first time it's needed. The client doesn't receive recipes from
 * the server any more, so this is how the bot knows that a piston is planks + cobblestone + iron + redstone.
 * Tag ingredients ({@code #minecraft:planks}) resolve against the server-synced tags when first used.
 */
public final class RecipeBook {
	/** One ingredient slot: a list of acceptable items, written as ids and/or {@code #tags}. */
	public static final class Ingredient {
		private final List<String> refs;
		private @Nullable List<Item> options;

		Ingredient(List<String> refs) {
			this.refs = refs;
		}

		/** Acceptable items, tags expanded (needs a joined world for tags). */
		public List<Item> options() {
			if (options == null) {
				List<Item> out = new ArrayList<>();
				for (String ref : refs) {
					if (ref.startsWith("#")) {
						TagKey<Item> tag = TagKey.create(Registries.ITEM, Identifier.parse(ref.substring(1)));
						for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) out.add(h.value());
					} else {
						item(ref).ifPresent(out::add);
					}
				}
				options = List.copyOf(out);
			}
			return options;
		}

		public boolean test(ItemStack s) {
			return !s.isEmpty() && options().contains(s.getItem());
		}

		@Override
		public String toString() {
			return String.join("|", refs);
		}
	}

	/**
	 * A crafting recipe as a grid pattern (shapeless ones are laid out row by row), or a smelting recipe
	 * ({@code smeltInput} set, pattern empty).
	 */
	public record Recipe(Item output, int count, List<String> pattern, Map<Character, Ingredient> key, @Nullable Ingredient smeltInput) {
		public boolean isSmelting() {
			return smeltInput != null;
		}

		public boolean fits2x2() {
			return pattern.size() <= 2 && pattern.stream().allMatch(row -> row.length() <= 2);
		}

		/** How many items one craft takes from {@code ingredient}'s slots. */
		public int amountOf(Ingredient ingredient) {
			int n = 0;
			for (String row : pattern) {
				for (char c : row.toCharArray()) {
					if (key.get(c) == ingredient) n++;
				}
			}
			return n;
		}

		public Predicate<ItemStack> matcher(Ingredient ingredient) {
			return ingredient::test;
		}
	}

	private static @Nullable RecipeBook instance;

	private final Map<Item, List<Recipe>> crafting = new HashMap<>();
	private final Map<Item, List<Recipe>> smelting = new HashMap<>();

	public static RecipeBook get() {
		if (instance == null) instance = load();
		return instance;
	}

	public List<Recipe> crafting(Item output) {
		return crafting.getOrDefault(output, List.of());
	}

	public List<Recipe> smelting(Item output) {
		return smelting.getOrDefault(output, List.of());
	}

	/** Some recipe makes this item (so it isn't a "natural" block to go mining for). */
	public boolean isMade(Item item) {
		return crafting.containsKey(item) || smelting.containsKey(item);
	}

	/**
	 * Everything that goes into {@code products} up to {@code depth} recipe levels down (all options of every
	 * ingredient), e.g. piston → planks, cobblestone, iron ingot, redstone → logs, raw iron.
	 */
	public Set<Item> ingredientTree(Collection<Item> products, int depth) {
		Set<Item> out = new HashSet<>();
		List<Item> frontier = new ArrayList<>(products);
		for (int d = 0; d < depth && !frontier.isEmpty(); d++) {
			List<Item> next = new ArrayList<>();
			for (Item item : frontier) {
				for (Recipe r : crafting(item)) {
					for (Ingredient ing : r.key().values()) addOptions(ing, out, next);
				}
				for (Recipe r : smelting(item)) addOptions(r.smeltInput(), out, next);
			}
			frontier = next;
		}
		return out;
	}

	private static void addOptions(Ingredient ing, Set<Item> seen, List<Item> next) {
		for (Item o : ing.options()) {
			if (seen.add(o)) next.add(o);
		}
	}

	// ---- loading --------------------------------------------------------------------------------

	private static RecipeBook load() {
		RecipeBook book = new RecipeBook();
		Optional<Path> dir = FabricLoader.getInstance().getModContainer("minecraft").flatMap(c -> recipeDir(c));
		if (dir.isEmpty()) {
			Chat.LOG.error("Vanilla recipe data not found in the game jar; crafting will be limited");
			return book;
		}
		int n = 0;
		try (Stream<Path> files = Files.list(dir.get())) {
			for (Path f : (Iterable<Path>) files::iterator) {
				if (!f.toString().endsWith(".json")) continue;
				try (Reader r = Files.newBufferedReader(f)) {
					if (book.add(JsonParser.parseReader(r).getAsJsonObject())) n++;
				} catch (RuntimeException | IOException e) {
					Chat.LOG.debug("Skipping recipe {}: {}", f, e.toString());
				}
			}
		} catch (IOException e) {
			Chat.LOG.error("Could not list vanilla recipes", e);
		}
		// special recipe type: plain flight-1 rocket = paper + gunpowder
		book.put(book.crafting, new Recipe(Items.FIREWORK_ROCKET, 3, List.of("ab"),
				Map.of('a', new Ingredient(List.of("minecraft:paper")), 'b', new Ingredient(List.of("minecraft:gunpowder"))), null));
		Chat.LOG.info("Loaded {} vanilla recipes", n);
		return book;
	}

	private static Optional<Path> recipeDir(ModContainer minecraft) {
		return minecraft.findPath("data/minecraft/recipe");
	}

	private boolean add(JsonObject json) {
		String type = json.has("type") ? json.get("type").getAsString() : "";
		switch (type) {
			case "minecraft:crafting_shaped" -> {
				Map<Character, Ingredient> key = new LinkedHashMap<>();
				for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("key").entrySet()) {
					key.put(e.getKey().charAt(0), ingredient(e.getValue()));
				}
				List<String> pattern = new ArrayList<>();
				for (JsonElement row : json.getAsJsonArray("pattern")) pattern.add(row.getAsString());
				return result(json, (out, count) -> put(crafting, new Recipe(out, count, trim(pattern), key, null)));
			}
			case "minecraft:crafting_shapeless" -> {
				JsonArray ings = json.getAsJsonArray("ingredients");
				Map<Character, Ingredient> key = new LinkedHashMap<>();
				int width = ings.size() <= 4 ? 2 : 3;
				List<String> pattern = new ArrayList<>();
				StringBuilder row = new StringBuilder();
				char c = 'a';
				for (JsonElement ing : ings) {
					key.put(c, ingredient(ing));
					row.append(c++);
					if (row.length() == width) {
						pattern.add(row.toString());
						row.setLength(0);
					}
				}
				if (!row.isEmpty()) pattern.add(row.toString());
				return result(json, (out, count) -> put(crafting, new Recipe(out, count, pattern, key, null)));
			}
			case "minecraft:smelting" -> {
				Ingredient input = ingredient(json.get("ingredient"));
				return result(json, (out, count) -> put(smelting, new Recipe(out, count, List.of(), Map.of(), input)));
			}
			default -> {
				return false; // stonecutting, smithing, special and transmute recipes need other stations or rules
			}
		}
	}

	private interface ResultSink {
		void accept(Item output, int count);
	}

	private static boolean result(JsonObject json, ResultSink sink) {
		JsonObject result = json.getAsJsonObject("result");
		Optional<Item> out = item(result.get("id").getAsString());
		if (out.isEmpty()) return false;
		sink.accept(out.get(), result.has("count") ? result.get("count").getAsInt() : 1);
		return true;
	}

	private void put(Map<Item, List<Recipe>> map, Recipe r) {
		map.computeIfAbsent(r.output(), k -> new ArrayList<>()).add(r);
	}

	/** Drop empty leading/trailing columns so 2x2-sized shapes fit the inventory grid. */
	private static List<String> trim(List<String> pattern) {
		int left = Integer.MAX_VALUE, right = 0;
		for (String row : pattern) {
			for (int i = 0; i < row.length(); i++) {
				if (row.charAt(i) != ' ') {
					left = Math.min(left, i);
					right = Math.max(right, i + 1);
				}
			}
		}
		if (left == Integer.MAX_VALUE) return pattern;
		List<String> out = new ArrayList<>();
		for (String row : pattern) {
			String padded = String.format("%-" + right + "s", row);
			String cut = padded.substring(left, right);
			if (!cut.isBlank() || !out.isEmpty()) out.add(cut);
		}
		while (!out.isEmpty() && out.getLast().isBlank()) out.removeLast();
		return out;
	}

	private static Ingredient ingredient(JsonElement e) {
		List<String> refs = new ArrayList<>();
		if (e.isJsonArray()) {
			for (JsonElement x : e.getAsJsonArray()) refs.add(ref(x));
		} else {
			refs.add(ref(e));
		}
		return new Ingredient(refs);
	}

	/** "minecraft:stick", "#minecraft:planks", or the older {"item": ...} / {"tag": ...} objects. */
	private static String ref(JsonElement e) {
		if (e.isJsonPrimitive()) return e.getAsString();
		JsonObject o = e.getAsJsonObject();
		return o.has("tag") ? "#" + o.get("tag").getAsString() : o.get("item").getAsString();
	}

	private static Optional<Item> item(String id) {
		Identifier rl = Identifier.tryParse(id);
		if (rl == null) return Optional.empty();
		return BuiltInRegistries.ITEM.getOptional(rl).filter(i -> i != Items.AIR);
	}
}
