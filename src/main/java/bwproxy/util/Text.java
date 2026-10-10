package bwproxy.util;

import java.util.List;
import java.util.Map;

/** Chat component flattening and colour-code helpers. */
public final class Text {
	private Text() {
	}

	/** Removes {@code §x} pairs. Same behaviour as OpenUtils' TextUtil.stripColorCodes. */
	public static String stripColorCodes(final String text) {
		final StringBuilder sb = new StringBuilder(text.length());
		boolean skip = false;
		for (int i = 0; i < text.length(); i++) {
			final char c = text.charAt(i);
			if (skip) {
				skip = false;
				continue;
			}
			if (c == '§') {
				skip = true;
				continue;
			}
			sb.append(c);
		}
		return sb.toString();
	}

	/**
	 * Drops characters the game font cannot draw (control characters, emoji surrogates). The mod asks
	 * the font renderer; a proxy has none, so this is an approximation. Hypixel pads sidebar lines
	 * with such characters to keep them unique.
	 */
	public static String stripAliens(final String text) {
		final StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			final char c = text.charAt(i);
			if (c < 0x20 || c == 0x7f || (c >= 0xD800 && c <= 0xDFFF) || (c >= 0xFE00 && c <= 0xFE0F)
					|| c == 0x200B || c == 0x200D) {
				continue;
			}
			sb.append(c);
		}
		return sb.toString();
	}

	/** Flattens a 1.8 chat component (JSON) to a string, keeping {@code §} formatting codes. */
	public static String flatten(final String json) {
		try {
			final StringBuilder sb = new StringBuilder();
			flatten(Json.parse(json), sb);
			return sb.toString();
		} catch (final RuntimeException e) {
			return json;
		}
	}

	private static void flatten(final Object o, final StringBuilder sb) {
		if (o == null) {
			return;
		}
		if (o instanceof String) {
			sb.append((String) o);
			return;
		}
		if (o instanceof Number || o instanceof Boolean) {
			sb.append(o);
			return;
		}
		if (o instanceof List) {
			for (final Object e : (List<?>) o) {
				flatten(e, sb);
			}
			return;
		}
		if (o instanceof Map) {
			final Map<String, Object> m = Json.obj(o);
			final Object color = m.get("color");
			if (color instanceof String) {
				final String code = colorCode((String) color);
				if (code != null) {
					sb.append(code);
				}
			}
			if (Boolean.TRUE.equals(m.get("bold"))) {
				sb.append("§l");
			}
			if (Boolean.TRUE.equals(m.get("italic"))) {
				sb.append("§o");
			}
			if (Boolean.TRUE.equals(m.get("underlined"))) {
				sb.append("§n");
			}
			if (Boolean.TRUE.equals(m.get("strikethrough"))) {
				sb.append("§m");
			}
			if (Boolean.TRUE.equals(m.get("obfuscated"))) {
				sb.append("§k");
			}
			flatten(m.get("text"), sb);
			// Unknown translation keys render as the key itself; we have no language file.
			flatten(m.get("translate"), sb);
			final List<Object> with = Json.list(m.get("with"));
			if (with != null) {
				for (final Object w : with) {
					flatten(w, sb);
				}
			}
			flatten(m.get("extra"), sb);
		}
	}

	private static String colorCode(final String name) {
		switch (name) {
			case "black" :
				return "§0";
			case "dark_blue" :
				return "§1";
			case "dark_green" :
				return "§2";
			case "dark_aqua" :
				return "§3";
			case "dark_red" :
				return "§4";
			case "dark_purple" :
				return "§5";
			case "gold" :
				return "§6";
			case "gray" :
				return "§7";
			case "dark_gray" :
				return "§8";
			case "blue" :
				return "§9";
			case "green" :
				return "§a";
			case "aqua" :
				return "§b";
			case "red" :
				return "§c";
			case "light_purple" :
				return "§d";
			case "yellow" :
				return "§e";
			case "white" :
				return "§f";
			default :
				return null;
		}
	}
}
