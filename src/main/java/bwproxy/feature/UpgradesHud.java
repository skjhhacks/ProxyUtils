package bwproxy.feature;

import bwproxy.game.BedWars;
import java.util.ArrayList;
import java.util.List;

/** Your team's purchased traps, sharpness and protection, from chat (port of UpgradesHudFeature). */
public final class UpgradesHud extends Feature {
	private final List<String> trapQueue = new ArrayList<>();
	private String prot;
	private String sharp;

	public UpgradesHud(final FeatureContext ctx) {
		super(ctx);
	}

	@Override
	public String id() {
		return "upgradeshud";
	}

	@Override
	public String title() {
		return "Upgrades HUD";
	}

	@Override
	public void onChat(final String message) {
		if (!BedWars.inGame(ctx.state()) || message.contains(":")) {
			return;
		}
		if (message.contains("Trap was set off!") || message.contains("Trap from the queue!")) {
			if (!trapQueue.isEmpty()) {
				trapQueue.remove(0);
			}
			return;
		}
		final int at = message.indexOf("purchased");
		if (at < 0) {
			return;
		}
		final String rest = message.substring(at + "purchased".length());
		if (message.contains("Trap")) {
			trapQueue.add(rest.replace("Trap", "").trim());
		} else if (message.contains("Reinforced Armor")) {
			prot = rest.replace("Reinforced Armor", "").trim();
		} else if (message.contains("Sharpened Swords")) {
			sharp = rest.replace("Sharpened Swords", "").trim();
		}
	}

	@Override
	public void onSecond() {
		if (!BedWars.inGame(ctx.state())) {
			reset();
		}
	}

	@Override
	public void onWorldLoad() {
		reset();
	}

	private void reset() {
		trapQueue.clear();
		prot = null;
		sharp = null;
	}

	@Override
	public List<String> hudCompact() {
		final List<String> lines = new ArrayList<>();
		if (!BedWars.inGame(ctx.state())) {
			return lines;
		}
		if (ctx.config().bool("upgradeshud.traps")) {
			lines.add("§7Trap " + (trapQueue.isEmpty() ? "§cNone" : "§a" + trapQueue.get(0)));
		}
		if (ctx.config().bool("upgradeshud.sharp")) {
			lines.add("§7Sharp " + (sharp == null ? "§cNone" : "§aI"));
		}
		if (ctx.config().bool("upgradeshud.prot")) {
			lines.add("§7Prot " + (prot == null ? "§cNone" : "§a" + prot));
		}
		return lines;
	}

	@Override
	public List<String> hudLines() {
		final List<String> lines = new ArrayList<>();
		if (!BedWars.inGame(ctx.state())) {
			return lines;
		}
		if (ctx.config().bool("upgradeshud.traps")) {
			lines.add("Trap: " + (trapQueue.isEmpty() ? "§cNone" : "§a" + trapQueue.get(0)));
		}
		if (ctx.config().bool("upgradeshud.sharp")) {
			lines.add("Sharp: " + (sharp == null ? "§cNone" : "§aI"));
		}
		if (ctx.config().bool("upgradeshud.prot")) {
			lines.add("Prot: " + (prot == null ? "§cNone" : "§a" + prot));
		}
		return lines;
	}
}
