package bwproxy.game;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;

/** Bed Wars helpers ported from OpenUtils' BedWarsUtil and GameModeUtil. */
public final class BedWars {
	private BedWars() {
	}

	public enum TeamColor {
		RED("Red", "§c"), BLUE("Blue", "§9"), GREEN("Green", "§a"), YELLOW("Yellow",
				"§e"), AQUA("Aqua", "§b"), WHITE("White", "§f"), PINK("Pink",
						"§d"), GRAY("Gray", "§8");
		public final String displayName;
		public final String colorCode;

		TeamColor(final String displayName, final String colorCode) {
			this.displayName = displayName;
			this.colorCode = colorCode;
		}

		public static TeamColor fromFormattedName(final String formatted) {
			if (formatted == null) {
				return null;
			}
			for (final TeamColor c : values()) {
				if (formatted.contains(c.colorCode)) {
					return c;
				}
			}
			return null;
		}
	}

	public enum Status {
		NOT_BEDWARS, LOBBY, PREGAME, IN_GAME
	}

	public static Status status(final GameState state) {
		final List<String> sb = state.sidebar();
		if (sb.size() < 7) {
			return Status.NOT_BEDWARS;
		}
		final String title = sb.get(0);
		if (!title.contains("BED") || !title.contains("WARS")) {
			return Status.NOT_BEDWARS;
		}
		if (sb.get(1).contains("§8L")) {
			return Status.LOBBY;
		}
		if (sb.get(4).contains("/") && sb.get(4).contains("§a")) {
			return Status.PREGAME;
		}
		if (sb.get(5).contains("§c") && sb.get(6).contains("§9")) {
			return Status.IN_GAME;
		}
		return Status.NOT_BEDWARS;
	}

	public static boolean inGame(final GameState state) {
		return status(state) == Status.IN_GAME;
	}

	public static TeamColor teamColor(final GameState state, final String playerName) {
		return playerName == null ? null
				: TeamColor.fromFormattedName(state.formatName(playerName));
	}

	public static boolean isTeammate(final GameState state, final String playerName) {
		final TeamColor target = teamColor(state, playerName);
		final TeamColor self = teamColor(state, state.selfName);
		return target != null && target == self;
	}

	public static String distance(final GameState state, final GameState.Entity e) {
		return new DecimalFormat("#", DecimalFormatSymbols.getInstance(Locale.ROOT))
				.format(state.distanceTo(e)) + "m";
	}
}
