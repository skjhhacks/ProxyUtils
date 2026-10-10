package bwproxy.game;

import bwproxy.net.PacketReader;
import bwproxy.util.Text;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The slice of the client's world the features need: entities, tab names, teams, the sidebar and
 * open windows. A proxy has no world, so this is rebuilt from the packet stream. Not thread-safe;
 * the owning session serialises access.
 */
public final class GameState {
	public static final class Entity {
		public final int id;
		public final UUID uuid;
		public double x;
		public double y;
		public double z;

		Entity(final int id, final UUID uuid) {
			this.id = id;
			this.uuid = uuid;
		}
	}

	public static final class Team {
		String prefix = "";
		String suffix = "";
		final Set<String> players = new HashSet<>();
	}

	public static final class Window {
		public final int id;
		public final String title;
		public final int slotCount;
		public final byte[][] slots;

		Window(final int id, final String title, final int slotCount) {
			this.id = id;
			this.title = title;
			this.slotCount = slotCount;
			this.slots = new byte[Math.max(slotCount, 0)][];
		}
	}

	public String selfName = "";
	public int selfEntityId = Integer.MIN_VALUE;
	public double selfX;
	public double selfY;
	public double selfZ;
	public String tabHeaderJson;
	public String tabFooterJson;
	private final Map<Integer, Entity> entities = new HashMap<>();
	private final Map<UUID, String> names = new HashMap<>();
	private final Map<String, Team> teams = new HashMap<>();
	private final Map<String, String> teamOfPlayer = new HashMap<>();
	private final Map<String, String> objectives = new HashMap<>();
	private final Map<String, Map<String, Integer>> scores = new HashMap<>();
	private String sidebarObjective;
	private final Map<Integer, Window> windows = new HashMap<>();

	/** Join Game: the client builds a brand new world (and scoreboard). */
	public void resetAll() {
		resetWorld();
		names.clear();
		teams.clear();
		teamOfPlayer.clear();
		objectives.clear();
		scores.clear();
		sidebarObjective = null;
		tabHeaderJson = null;
		tabFooterJson = null;
	}

	/** Respawn: entities go, the scoreboard is carried over by the vanilla client. */
	public void resetWorld() {
		entities.clear();
		windows.clear();
	}

	// ---- entities ----

	public void onSpawnPlayer(final PacketReader r) {
		final int id = r.readVarInt();
		final UUID uuid = r.readUuid();
		final Entity e = new Entity(id, uuid);
		e.x = r.readInt() / 32.0;
		e.y = r.readInt() / 32.0;
		e.z = r.readInt() / 32.0;
		entities.put(id, e);
	}

	public void onRelMove(final PacketReader r) {
		final Entity e = entities.get(r.readVarInt());
		if (e != null) {
			e.x += r.readByte() / 32.0;
			e.y += r.readByte() / 32.0;
			e.z += r.readByte() / 32.0;
		}
	}

	public void onTeleport(final PacketReader r) {
		final Entity e = entities.get(r.readVarInt());
		if (e != null) {
			e.x = r.readInt() / 32.0;
			e.y = r.readInt() / 32.0;
			e.z = r.readInt() / 32.0;
		}
	}

	public void onDestroy(final PacketReader r) {
		final int n = r.readVarInt();
		for (int i = 0; i < n; i++) {
			entities.remove(r.readVarInt());
		}
	}

	public Entity entity(final int id) {
		return entities.get(id);
	}

	public String nameOf(final Entity e) {
		return e == null ? null : names.get(e.uuid);
	}

	public double distanceTo(final Entity e) {
		final double dx = e.x - selfX;
		final double dy = e.y - selfY;
		final double dz = e.z - selfZ;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	public void onServerPosition(final PacketReader r) {
		final double x = r.readDouble();
		final double y = r.readDouble();
		final double z = r.readDouble();
		r.readFloat();
		r.readFloat();
		final int flags = r.readByte();
		selfX = (flags & 0x01) != 0 ? selfX + x : x;
		selfY = (flags & 0x02) != 0 ? selfY + y : y;
		selfZ = (flags & 0x04) != 0 ? selfZ + z : z;
	}

	public void onClientPosition(final PacketReader r) {
		selfX = r.readDouble();
		selfY = r.readDouble();
		selfZ = r.readDouble();
	}

	// ---- tab list ----

	public void onPlayerInfo(final PacketReader r) {
		final int action = r.readVarInt();
		final int n = r.readVarInt();
		for (int i = 0; i < n; i++) {
			final UUID uuid = r.readUuid();
			switch (action) {
				case 0 : {
					final String name = r.readString();
					final int props = r.readVarInt();
					for (int p = 0; p < props; p++) {
						r.readString();
						r.readString();
						if (r.readBoolean()) {
							r.readString();
						}
					}
					r.readVarInt();
					r.readVarInt();
					if (r.readBoolean()) {
						r.readString();
					}
					names.put(uuid, name);
					break;
				}
				case 1 :
				case 2 :
					r.readVarInt();
					break;
				case 3 :
					if (r.readBoolean()) {
						r.readString();
					}
					break;
				default :
					break;
			}
		}
	}

	// ---- teams ----

	public void onTeam(final PacketReader r) {
		final String name = r.readString();
		final int mode = r.readByte();
		if (mode == 1) {
			final Team t = teams.remove(name);
			if (t != null) {
				for (final String p : t.players) {
					teamOfPlayer.remove(p, name);
				}
			}
			return;
		}
		Team t = teams.get(name);
		if (t == null) {
			t = new Team();
			teams.put(name, t);
		}
		if (mode == 0 || mode == 2) {
			r.readString(); // display name
			t.prefix = r.readString();
			t.suffix = r.readString();
			r.readByte(); // friendly fire
			r.readString(); // name tag visibility
			r.readByte(); // colour
		}
		if (mode == 0 || mode == 3 || mode == 4) {
			final int n = r.readVarInt();
			for (int i = 0; i < n; i++) {
				final String p = r.readString();
				if (mode == 4) {
					t.players.remove(p);
					teamOfPlayer.remove(p, name);
				} else {
					t.players.add(p);
					teamOfPlayer.put(p, name);
				}
			}
		}
	}

	/** Scoreboard.formatPlayerName: team prefix + name + team suffix. */
	public String formatName(final String name) {
		final String teamName = teamOfPlayer.get(name);
		final Team t = teamName == null ? null : teams.get(teamName);
		return t == null ? name : t.prefix + name + t.suffix;
	}

	// ---- scoreboard ----

	public void onObjective(final PacketReader r) {
		final String name = r.readString();
		final int action = r.readByte();
		if (action == 1) {
			objectives.remove(name);
			scores.remove(name);
			if (name.equals(sidebarObjective)) {
				sidebarObjective = null;
			}
		} else {
			objectives.put(name, r.readString());
		}
	}

	public void onScore(final PacketReader r) {
		final String entry = r.readString();
		final int action = r.readByte();
		final String objective = r.readString();
		if (action == 1) {
			if (objective.isEmpty()) {
				for (final Map<String, Integer> m : scores.values()) {
					m.remove(entry);
				}
			} else {
				final Map<String, Integer> m = scores.get(objective);
				if (m != null) {
					m.remove(entry);
				}
			}
			return;
		}
		final int value = r.readVarInt();
		Map<String, Integer> m = scores.get(objective);
		if (m == null) {
			m = new HashMap<>();
			scores.put(objective, m);
		}
		m.put(entry, value);
	}

	public void onDisplayObjective(final PacketReader r) {
		final int slot = r.readByte();
		final String name = r.readString();
		if (slot == 1) {
			sidebarObjective = name.isEmpty() ? null : name;
		}
	}

	/** Title first, then lines top to bottom, as OpenUtils' WorldUtil.getScoreboard returns them. */
	public List<String> sidebar() {
		if (sidebarObjective == null || !objectives.containsKey(sidebarObjective)) {
			return Collections.emptyList();
		}
		final List<String> result = new ArrayList<>();
		result.add(Text.stripAliens(objectives.get(sidebarObjective)));
		final Map<String, Integer> m = scores.get(sidebarObjective);
		if (m == null) {
			return result;
		}
		final List<Map.Entry<String, Integer>> sorted = new ArrayList<>(m.entrySet());
		Collections.sort(sorted, (a, b) -> {
			final int cmp = Integer.compare(a.getValue(), b.getValue());
			return cmp != 0 ? cmp : b.getKey().compareToIgnoreCase(a.getKey());
		});
		final int limit = Math.min(15, sorted.size());
		final List<String> lines = new ArrayList<>();
		for (int i = 0; i < limit; i++) {
			lines.add(formatName(sorted.get(i).getKey()));
		}
		Collections.reverse(lines);
		for (final String l : lines) {
			result.add(Text.stripAliens(l));
		}
		return result;
	}

	/** Number of lines the server has put on the sidebar. */
	public int sidebarEntryCount() {
		final Map<String, Integer> m = sidebarObjective == null ? null : scores.get(sidebarObjective);
		return m == null ? 0 : m.size();
	}

	public int lowestSidebarScore() {
		final Map<String, Integer> m = sidebarObjective == null ? null : scores.get(sidebarObjective);
		if (m == null || m.isEmpty()) {
			return 0;
		}
		int min = Integer.MAX_VALUE;
		for (final int v : m.values()) {
			min = Math.min(min, v);
		}
		return min;
	}

	public String sidebarObjectiveName() {
		return sidebarObjective;
	}

	// ---- windows ----

	public void onOpenWindow(final int id, final String titleJson, final int slotCount) {
		windows.put(id, new Window(id, Text.flatten(titleJson), slotCount));
	}

	public void onCloseWindow(final int id) {
		windows.remove(id);
	}

	public Window window(final int id) {
		return windows.get(id);
	}

	/** Keeps the raw bytes of each slot so a clicked shop slot can be put back after a rewrite. */
	public void onSetSlot(final PacketReader r) {
		final int windowId = r.readByte();
		final int slot = r.readShort();
		final int start = r.position();
		ItemStack.read(r);
		final Window w = windows.get(windowId & 0xFF);
		if (w != null && slot >= 0 && slot < w.slots.length) {
			w.slots[slot] = copy(r, start);
		}
	}

	public void onWindowItems(final PacketReader r) {
		final int windowId = r.readUnsignedByte();
		final int n = r.readShort();
		final Window w = windows.get(windowId);
		for (int i = 0; i < n; i++) {
			final int start = r.position();
			ItemStack.read(r);
			if (w != null && i < w.slots.length) {
				w.slots[i] = copy(r, start);
			}
		}
	}

	private static byte[] copy(final PacketReader r, final int start) {
		final int end = r.position();
		final byte[] out = new byte[end - start];
		System.arraycopy(r.data(), start, out, 0, out.length);
		return out;
	}
}
