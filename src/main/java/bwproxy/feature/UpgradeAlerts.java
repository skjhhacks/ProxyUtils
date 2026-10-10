package bwproxy.feature;

import bwproxy.game.BedWars;
import bwproxy.game.GameState;
import bwproxy.game.ItemStack;
import bwproxy.game.Items;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Announces when an enemy team gets sharpness or protection (port of UpgradeAlertsFeature). */
public final class UpgradeAlerts extends Feature {
	private enum Upgrade {
		SHARPENED_SWORDS("Sharpened Swords"), REINFORCED_ARMOR("Reinforced Armor");
		final String label;

		Upgrade(final String label) {
			this.label = label;
		}
	}
	private final Map<String, Set<Upgrade>> teamUpgrades = new HashMap<>();

	public UpgradeAlerts(final FeatureContext ctx) {
		super(ctx);
	}

	@Override
	public String id() {
		return "upgradealerts";
	}

	@Override
	public String title() {
		return "Upgrade Alerts";
	}

	@Override
	public void onEnemyEquipment(final GameState.Entity entity, final String name, final int slot,
			final ItemStack stack) {
		if (!stack.isEnchanted()) {
			return;
		}
		final BedWars.TeamColor color = BedWars.teamColor(ctx.state(), name);
		if (color == null) {
			return;
		}
		final String teamKey = color.colorCode + color.displayName;
		final Set<Upgrade> upgrades = teamUpgrades.computeIfAbsent(teamKey,
				k -> EnumSet.noneOf(Upgrade.class));
		if (slot == 0 && Items.isSword(stack.id)) {
			if (upgrades.add(Upgrade.SHARPENED_SWORDS)) {
				notifyUpgrade(teamKey, Upgrade.SHARPENED_SWORDS.label);
			}
		} else if (slot == 3 && Items.isArmor(stack.id)) {
			if (upgrades.add(Upgrade.REINFORCED_ARMOR)) {
				notifyUpgrade(teamKey, Upgrade.REINFORCED_ARMOR.label);
			}
		}
	}

	private void notifyUpgrade(final String teamKey, final String upgrade) {
		ctx.chat(teamKey + " Team §7purchased §b" + upgrade);
		if (ctx.config().bool("upgradealerts.ping")) {
			ctx.ping();
		}
	}

	@Override
	public void onWorldLoad() {
		teamUpgrades.clear();
	}
}
