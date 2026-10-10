package bwproxy.game;

import bwproxy.net.PacketReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A 1.8 inventory slot: item id, count, damage and (parsed) NBT. */
public final class ItemStack {
	public final int id;
	public final int count;
	public final int damage;
	public final Map<String, Object> nbt;

	private ItemStack(final int id, final int count, final int damage, final Map<String, Object> nbt) {
		this.id = id;
		this.count = count;
		this.damage = damage;
		this.nbt = nbt;
	}

	public static ItemStack of(final int id) {
		return new ItemStack(id, 1, 0, null);
	}

	public static ItemStack of(final int id, final String customName, final int enchantments) {
		final Map<String, Object> tag = new LinkedHashMap<>();
		if (customName != null) {
			final Map<String, Object> display = new LinkedHashMap<>();
			display.put("Name", customName);
			tag.put("display", display);
		}
		if (enchantments > 0) {
			final List<Object> list = new ArrayList<>();
			for (int i = 0; i < enchantments; i++) {
				list.add(new LinkedHashMap<String, Object>());
			}
			tag.put("ench", list);
		}
		return new ItemStack(id, 1, 0, tag);
	}

	/** Reads a slot; returns null for an empty slot (item id -1). */
	public static ItemStack read(final PacketReader r) {
		final int id = r.readShort();
		if (id == -1) {
			return null;
		}
		final int count = r.readByte();
		final int damage = r.readShort();
		Map<String, Object> nbt = null;
		final int first = r.readUnsignedByte();
		if (first != 0) {
			if (first != 10) {
				throw new IllegalStateException("Unexpected NBT root tag " + first);
			}
			skipName(r);
			nbt = readCompound(r);
		}
		return new ItemStack(id, count, damage, nbt);
	}

	public boolean isEnchanted() {
		return nbt != null && nbt.get("ench") instanceof List;
	}

	public int enchantmentCount() {
		return nbt != null && nbt.get("ench") instanceof List ? ((List<?>) nbt.get("ench")).size() : 0;
	}

	/** Custom display name (colour codes kept), or "" when the item has none. */
	public String customName() {
		if (nbt != null && nbt.get("display") instanceof Map) {
			final Object n = ((Map<?, ?>) nbt.get("display")).get("Name");
			if (n instanceof String) {
				return (String) n;
			}
		}
		return "";
	}

	private static void skipName(final PacketReader r) {
		final int len = r.readShort() & 0xFFFF;
		r.skip(len);
	}

	private static String readNbtString(final PacketReader r) {
		final int len = r.readShort() & 0xFFFF;
		return new String(r.readBytes(len), StandardCharsets.UTF_8);
	}

	private static Map<String, Object> readCompound(final PacketReader r) {
		final Map<String, Object> m = new LinkedHashMap<>();
		while (true) {
			final int type = r.readUnsignedByte();
			if (type == 0) {
				return m;
			}
			final String name = readNbtString(r);
			m.put(name, readPayload(r, type));
		}
	}

	private static Object readPayload(final PacketReader r, final int type) {
		switch (type) {
			case 1 :
				return (byte) r.readByte();
			case 2 :
				return (short) r.readShort();
			case 3 :
				return r.readInt();
			case 4 :
				return r.readLong();
			case 5 :
				return r.readFloat();
			case 6 :
				return r.readDouble();
			case 7 :
				return r.readBytes(r.readInt());
			case 8 :
				return readNbtString(r);
			case 9 : {
				final int elem = r.readUnsignedByte();
				final int n = r.readInt();
				final List<Object> l = new ArrayList<>();
				for (int i = 0; i < n; i++) {
					l.add(readPayload(r, elem));
				}
				return l;
			}
			case 10 :
				return readCompound(r);
			case 11 : {
				final int n = r.readInt();
				final int[] a = new int[n];
				for (int i = 0; i < n; i++) {
					a[i] = r.readInt();
				}
				return a;
			}
			default :
				throw new IllegalStateException("Unknown NBT tag " + type);
		}
	}
}
