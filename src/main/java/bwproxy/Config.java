package bwproxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Flat key/value settings backed by a .properties file. Defaults live here, in one place. */
public final class Config {
	private static final Map<String, String[]> DEFAULTS = new LinkedHashMap<>();
	static {
		def("listen.port", "25565", "Local port you add to Minecraft as a server (localhost:25565).");
		def("upstream.host", "mc.hypixel.net", "Server the proxy connects to.");
		def("upstream.port", "25565", "Upstream port.");
		def("hud.output", "sidebar",
				"Where timers/upgrades show: sidebar, tablist, actionbar (comma separate to combine), off.");
		def("hud.refresh_ms", "1000", "How often the HUD refreshes.");
		def("alerts.prefix", "§8§l» §r", "Prefix for chat messages from the proxy.");
		def("alerts.sound", "true",
				"Master switch for alert ping sounds (armor/items/upgrades). false = silent chat alerts.");

		def("armor.enabled", "true", "Armor Alerts: call out enemy armor upgrades.");
		def("armor.ping", "All", "None | Diamond Only | All");
		def("armor.chainmail", "true", "Alert on chainmail.");
		def("armor.iron", "true", "Alert on iron.");
		def("armor.diamond", "true", "Alert on diamond.");

		def("items.enabled", "true", "Item Alerts: warn when enemies reveal important items.");
		def("items.ping", "All", "None | Important | All");
		def("items.iron_sword", "true", "");
		def("items.diamond_sword", "true", "");
		def("items.fireball", "true", "");
		def("items.knockback_stick", "true", "");
		def("items.bow", "true", "");
		def("items.tnt", "true", "");
		def("items.obsidian", "true", "");
		def("items.pearl", "true", "");
		def("items.bridge_egg", "true", "");
		def("items.potions", "true", "Invisibility, jump, speed, milk.");
		def("items.golden_apple", "true", "");
		def("items.water_bucket", "true", "");
		def("items.gold_pickaxe", "true", "");
		def("items.diamond_pickaxe", "true", "");
		def("items.dream_defender", "true", "");
		def("items.bedbug", "true", "");
		def("items.popup_tower", "true", "");
		def("items.rotation", "true", "Rotating Dream/Lucky Block items.");

		def("quickshop.enabled", "true",
				"Quick Shop: left click in the item shop is sent as a middle click (one click, no automation).");

		def("timers.enabled", "true", "Timers HUD: emerald/diamond spawn timers.");
		def("timers.show_game_time", "false", "Also show elapsed game time.");

		def("upgradealerts.enabled", "true", "Upgrade Alerts: enemy sharpness/protection upgrades.");
		def("upgradealerts.ping", "true", "Play a ping.");

		def("upgradeshud.enabled", "true", "Upgrades HUD: your team's traps/sharpness/protection.");
		def("upgradeshud.traps", "true", "");
		def("upgradeshud.sharp", "true", "");
		def("upgradeshud.prot", "true", "");
	}
	private final Properties props = new Properties();
	private final Path file;

	public Config(final Path file) {
		this.file = file;
	}

	private static void def(final String key, final String value, final String doc) {
		DEFAULTS.put(key, new String[]{value, doc});
	}

	public static Map<String, String[]> defaults() {
		return DEFAULTS;
	}

	public void load() throws IOException {
		if (Files.exists(file)) {
			try (InputStream in = Files.newInputStream(file)) {
				props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
			}
		} else {
			save();
		}
	}

	public void save() throws IOException {
		final StringBuilder sb = new StringBuilder("# Hypixel Bed Wars proxy settings\n");
		for (final Map.Entry<String, String[]> e : DEFAULTS.entrySet()) {
			if (!e.getValue()[1].isEmpty()) {
				sb.append("# ").append(e.getValue()[1]).append('\n');
			}
			sb.append(e.getKey()).append('=').append(get(e.getKey())).append("\n");
		}
		try (OutputStream out = Files.newOutputStream(file)) {
			out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
	}

	public String get(final String key) {
		final String v = props.getProperty(key);
		if (v != null) {
			return v;
		}
		final String[] d = DEFAULTS.get(key);
		return d == null ? null : d[0];
	}

	public boolean bool(final String key) {
		return Boolean.parseBoolean(get(key));
	}

	public int integer(final String key) {
		try {
			return Integer.parseInt(get(key).trim());
		} catch (final RuntimeException e) {
			return Integer.parseInt(DEFAULTS.get(key)[0]);
		}
	}

	public boolean is(final String key, final String value) {
		final String v = get(key);
		return v != null && v.trim().toLowerCase(Locale.ROOT).equals(value.toLowerCase(Locale.ROOT));
	}

	public void set(final String key, final String value) {
		props.setProperty(key, value);
	}

	public boolean known(final String key) {
		return DEFAULTS.containsKey(key);
	}
}
