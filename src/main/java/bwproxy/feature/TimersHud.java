package bwproxy.feature;

import bwproxy.game.BedWars;
import bwproxy.util.Text;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Emerald/diamond spawn timers read from the sidebar event line (port of TimersHudFeature). */
public final class TimersHud extends Feature {
	private static final int EMERALD_FIRST = 31;
	private static final int DIAMOND_FIRST = 1;
	private static final List<Integer> DIAMOND_UPGRADES = Arrays.asList(6 * 60, 18 * 60);
	private static final List<Integer> EMERALD_UPGRADES = Arrays.asList(12 * 60, 24 * 60);
	private static final Pattern EVENT = Pattern.compile("^(.+?)\\s+in\\s+(\\d{1,2}):(\\d{2})");

	private static final class Stage {
		final String name;
		final int time;

		Stage(final String name, final int time) {
			this.name = name;
			this.time = time;
		}
	}

	private static final List<Stage> STAGES = Arrays.asList(new Stage("Diamond II", 6 * 60),
			new Stage("Emerald II", 12 * 60), new Stage("Diamond III", 18 * 60),
			new Stage("Emerald III", 24 * 60), new Stage("Bed gone", 30 * 60),
			new Stage("Sudden Death", 40 * 60), new Stage("Game End", 50 * 60));

	private static final class Spawns {
		final int count;
		final int next;

		Spawns(final int count, final int next) {
			this.count = count;
			this.next = next;
		}
	}

	private boolean inGame;
	private boolean eightTeams;
	private int elapsed;
	private int emeraldCount;
	private int emeraldNext;
	private int diamondCount;
	private int diamondNext;

	public TimersHud(final FeatureContext ctx) {
		super(ctx);
	}

	@Override
	public String id() {
		return "timers";
	}

	@Override
	public String title() {
		return "Timers HUD";
	}

	@Override
	public void onSecond() {
		if (!BedWars.inGame(ctx.state())) {
			reset();
			return;
		}
		if (!inGame) {
			inGame = true;
			eightTeams = false;
			for (final String line : ctx.state().sidebar()) {
				if (Text.stripColorCodes(line).contains("Pink:")) {
					eightTeams = true;
					break;
				}
			}
		}
		updateFromSidebar();
	}

	@Override
	public void onWorldLoad() {
		reset();
	}

	private void reset() {
		inGame = false;
		elapsed = 0;
		emeraldCount = emeraldNext = diamondCount = diamondNext = 0;
	}

	@Override
	public List<String> hudLines() {
		final List<String> lines = new ArrayList<>();
		if (!inGame) {
			return lines;
		}
		if (ctx.config().bool("timers.show_game_time")) {
			lines.add("§eGame Time: §f" + String.format("%d:%02d", elapsed / 60, elapsed % 60));
		}
		lines.add("§aEmeralds (§7" + emeraldCount + "§a): §f" + emeraldNext);
		lines.add("§bDiamonds (§7" + diamondCount + "§b): §f" + diamondNext);
		return lines;
	}

	@Override
	public List<String> hudCompact() {
		final List<String> lines = new ArrayList<>();
		if (!inGame) {
			return lines;
		}
		if (ctx.config().bool("timers.show_game_time")) {
			lines.add("§eTime §f" + String.format("%d:%02d", elapsed / 60, elapsed % 60));
		}
		lines.add("§aE§7(" + emeraldCount + ") §f" + emeraldNext);
		lines.add("§bD§7(" + diamondCount + ") §f" + diamondNext);
		return lines;
	}

	private int emeraldInterval(final int tier) {
		if (eightTeams) {
			return tier == 2 ? 50 : tier == 3 ? 35 : 65;
		}
		return tier == 2 ? 40 : tier == 3 ? 27 : 55;
	}

	private static int diamondInterval(final int tier) {
		return tier == 2 ? 23 : tier == 3 ? 12 : 30;
	}

	private static int tier(final int time, final List<Integer> upgrades) {
		if (upgrades.size() > 1 && time >= upgrades.get(1)) {
			return 3;
		}
		if (!upgrades.isEmpty() && time >= upgrades.get(0)) {
			return 2;
		}
		return 1;
	}

	private static Spawns spawns(final int time, final int first, final List<Integer> upgrades,
			final IntUnaryOperator interval) {
		if (time < first) {
			return new Spawns(0, first - time);
		}
		int count = 1;
		int last = first;
		int idxUp = 0;
		int nextUp = upgrades.isEmpty() ? Integer.MAX_VALUE : upgrades.get(0);
		while (true) {
			final int next = last + interval.applyAsInt(tier(last, upgrades));
			if (nextUp <= time && nextUp < next) {
				count++;
				last = nextUp;
				idxUp++;
				nextUp = idxUp < upgrades.size() ? upgrades.get(idxUp) : Integer.MAX_VALUE;
				continue;
			}
			if (next > time) {
				return new Spawns(count, Math.max(1, next - time));
			}
			count++;
			last = next;
		}
	}

	private void updateFromSidebar() {
		final List<String> sidebar = ctx.state().sidebar();
		if (sidebar.size() < 4) {
			return;
		}
		final Matcher m = EVENT.matcher(Text.stripColorCodes(sidebar.get(3)).trim());
		if (!m.find()) {
			return;
		}
		final int secondsLeft = Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3));
		Stage scheduled = null;
		for (final Stage s : STAGES) {
			if (s.name.equalsIgnoreCase(m.group(1).trim())) {
				scheduled = s;
				break;
			}
		}
		if (scheduled == null) {
			return;
		}
		elapsed = scheduled.time - secondsLeft;
		final Spawns em = spawns(elapsed + 1, EMERALD_FIRST, EMERALD_UPGRADES, this::emeraldInterval);
		emeraldCount = em.count;
		emeraldNext = em.next;
		final Spawns di = spawns(elapsed + 1, DIAMOND_FIRST, DIAMOND_UPGRADES,
				TimersHud::diamondInterval);
		diamondCount = di.count;
		diamondNext = di.next;
	}
}
