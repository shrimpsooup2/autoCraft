package com.autocraft.schematic;

import com.autocraft.Chat;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads the three common schematic formats into a {@link Schematic}:
 * <ul>
 * <li>{@code .litematic} (Litematica): one or more regions, bit-packed palette indices</li>
 * <li>{@code .schem} (Sponge v2/v3, WorldEdit): varint palette indices</li>
 * <li>{@code .nbt} (vanilla structure block): explicit block list (missing cells = structure void)</li>
 * </ul>
 * Air is kept (the builder clears it); blocks unknown to this game version are dropped and counted.
 */
public final class SchematicLoader {
	/** Raw cells in file coordinates plus how many states couldn't be resolved. */
	private record Raw(List<Schematic.Cell> cells, int unknown) {}

	private SchematicLoader() {}

	public static Schematic load(Path file) throws IOException {
		String fileName = file.getFileName().toString();
		String lower = fileName.toLowerCase();
		CompoundTag root = readNbt(file);
		Raw raw;
		if (lower.endsWith(".litematic")) raw = litematic(root);
		else if (lower.endsWith(".schem")) raw = sponge(root);
		else if (lower.endsWith(".nbt")) raw = structure(root);
		else throw new IOException("unsupported schematic type: " + fileName);
		if (raw.cells.isEmpty()) throw new IOException("no blocks in " + fileName);
		String name = fileName.substring(0, fileName.lastIndexOf('.'));
		if (raw.unknown > 0) Chat.LOG.warn("{}: {} block types unknown to this game version were skipped", fileName, raw.unknown);
		List<Schematic.Cell> cells = normalise(raw.cells);
		Vec3i size = size(cells);
		return new Schematic(name, size, cells, FarmNames.farmFor(name, cells), null, false);
	}

	/** Gzipped NBT (the usual) or plain NBT (some sites and tools save it uncompressed): tell them apart by the gzip magic. */
	private static CompoundTag readNbt(Path file) throws IOException {
		byte[] head = new byte[2];
		try (InputStream in = Files.newInputStream(file)) {
			if (in.readNBytes(head, 0, 2) < 2) throw new IOException("empty file: " + file.getFileName());
		}
		if ((head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			return NbtIo.read(in, NbtAccounter.unlimitedHeap());
		}
	}

	/** Move the minimum corner to (0,0,0). */
	private static List<Schematic.Cell> normalise(List<Schematic.Cell> cells) {
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		for (Schematic.Cell c : cells) {
			minX = Math.min(minX, c.pos().getX());
			minY = Math.min(minY, c.pos().getY());
			minZ = Math.min(minZ, c.pos().getZ());
		}
		List<Schematic.Cell> out = new ArrayList<>(cells.size());
		for (Schematic.Cell c : cells) out.add(new Schematic.Cell(c.pos().offset(-minX, -minY, -minZ), c.state()));
		return out;
	}

	static Vec3i size(List<Schematic.Cell> cells) {
		int x = 0, y = 0, z = 0;
		for (Schematic.Cell c : cells) {
			x = Math.max(x, c.pos().getX() + 1);
			y = Math.max(y, c.pos().getY() + 1);
			z = Math.max(z, c.pos().getZ() + 1);
		}
		return new Vec3i(x, y, z);
	}

	// ---- Litematica -----------------------------------------------------------------------------

	private static Raw litematic(CompoundTag root) {
		List<Schematic.Cell> cells = new ArrayList<>();
		int unknown = 0;
		CompoundTag regions = root.getCompoundOrEmpty("Regions");
		for (String regionName : regions.keySet()) {
			CompoundTag region = regions.getCompoundOrEmpty(regionName);
			CompoundTag pos = region.getCompoundOrEmpty("Position"), size = region.getCompoundOrEmpty("Size");
			int sx = size.getIntOr("x", 0), sy = size.getIntOr("y", 0), sz = size.getIntOr("z", 0);
			// regions may extend in the negative direction from their position
			int minX = pos.getIntOr("x", 0) + (sx < 0 ? sx + 1 : 0);
			int minY = pos.getIntOr("y", 0) + (sy < 0 ? sy + 1 : 0);
			int minZ = pos.getIntOr("z", 0) + (sz < 0 ? sz + 1 : 0);
			int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
			ListTag paletteTag = region.getListOrEmpty("BlockStatePalette");
			BlockState[] palette = new BlockState[paletteTag.size()];
			for (int i = 0; i < palette.length; i++) {
				palette[i] = BlockStates.fromTag(paletteTag.getCompoundOrEmpty(i));
				if (palette[i] == null) unknown++;
			}
			long[] data = region.getLongArray("BlockStates").orElse(new long[0]);
			int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(1, palette.length - 1)));
			long mask = (1L << bits) - 1;
			for (int y = 0; y < ay; y++) {
				for (int z = 0; z < az; z++) {
					for (int x = 0; x < ax; x++) {
						long index = (long) y * ax * az + (long) z * ax + x;
						int id = (int) packed(data, index, bits, mask);
						if (id < 0 || id >= palette.length || palette[id] == null) continue;
						cells.add(new Schematic.Cell(new BlockPos(minX + x, minY + y, minZ + z), palette[id]));
					}
				}
			}
		}
		return new Raw(cells, unknown);
	}

	/** Litematica packs entries back to back, spanning long boundaries. */
	private static long packed(long[] data, long index, int bits, long mask) {
		long start = index * bits;
		int startLong = (int) (start >> 6), endLong = (int) (((index + 1) * bits - 1) >> 6);
		int offset = (int) (start & 63);
		if (startLong >= data.length || endLong >= data.length) return -1;
		if (startLong == endLong) return data[startLong] >>> offset & mask;
		return (data[startLong] >>> offset | data[endLong] << (64 - offset)) & mask;
	}

	// ---- Sponge (.schem) ------------------------------------------------------------------------

	private static Raw sponge(CompoundTag root) {
		CompoundTag schem = root.getCompound("Schematic").orElse(root); // v3 nests everything
		int width = schem.getIntOr("Width", 0), height = schem.getIntOr("Height", 0), length = schem.getIntOr("Length", 0);
		CompoundTag blocks = schem.getCompound("Blocks").orElse(schem);
		CompoundTag paletteTag = blocks.getCompound("Palette").orElse(new CompoundTag());
		byte[] data = blocks.getByteArray("Data").or(() -> schem.getByteArray("BlockData")).orElse(new byte[0]);
		BlockState[] palette = new BlockState[paletteTag.size() + 1];
		int unknown = 0;
		for (String key : paletteTag.keySet()) {
			int id = paletteTag.getIntOr(key, -1);
			if (id < 0) continue;
			if (id >= palette.length) palette = Arrays.copyOf(palette, id + 1);
			palette[id] = BlockStates.parse(key);
			if (palette[id] == null) unknown++;
		}
		List<Schematic.Cell> cells = new ArrayList<>();
		int i = 0, cursor = 0;
		while (cursor < data.length) {
			int value = 0, shift = 0;
			byte b;
			do {
				b = data[cursor++];
				value |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0 && cursor < data.length);
			int y = i / (width * length), rem = i % (width * length), z = rem / width, x = rem % width;
			if (value < palette.length && palette[value] != null && y < height) cells.add(new Schematic.Cell(new BlockPos(x, y, z), palette[value]));
			i++;
		}
		return new Raw(cells, unknown);
	}

	// ---- vanilla structure (.nbt) ---------------------------------------------------------------

	private static Raw structure(CompoundTag root) {
		ListTag paletteTag = root.getList("palette").orElseGet(() -> root.getListOrEmpty("palettes").getList(0).orElse(new ListTag()));
		BlockState[] palette = new BlockState[paletteTag.size()];
		int unknown = 0;
		for (int i = 0; i < palette.length; i++) {
			palette[i] = BlockStates.fromTag(paletteTag.getCompoundOrEmpty(i));
			if (palette[i] == null) unknown++;
		}
		List<Schematic.Cell> cells = new ArrayList<>();
		ListTag blocks = root.getListOrEmpty("blocks");
		for (int i = 0; i < blocks.size(); i++) {
			CompoundTag b = blocks.getCompoundOrEmpty(i);
			ListTag pos = b.getListOrEmpty("pos");
			int state = b.getIntOr("state", -1);
			if (state < 0 || state >= palette.length || palette[state] == null) continue;
			cells.add(new Schematic.Cell(new BlockPos(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0)), palette[state]));
		}
		return new Raw(cells, unknown);
	}
}
