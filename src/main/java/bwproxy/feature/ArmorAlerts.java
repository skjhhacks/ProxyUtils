package bwproxy.feature;

import bwproxy.game.BedWars;
import bwproxy.game.GameState;
import bwproxy.game.ItemStack;
import bwproxy.game.Items;
import java.util.HashMap;
import java.util.Map;

/** Calls out enemy armor upgrades (port of OpenUtils ArmorAlertsFeature). */
public final class ArmorAlerts extends Feature {
	private static final int NONE = 0;
	private static final int LEATHER = 1;
	private static final int CHAINMAIL = 2;
	private static final int IRON = 3;
	private static final int DIAMOND = 4;
	private final Map<String, Integer> playerArmor = new HashMap<>();

	public ArmorAlerts(final FeatureContext ctx) {
		super(ctx);
	}

	@Override
	public String id() {
		return "armor";
	}

	@Override
	public String title() {
		return "Armor Alerts";
	}

	@Override
	public void onEnemyEquipment(final GameState.Entity entity, final String name, final int slot,
			final ItemStack stack) {
		if (slot != 2) {
			return;
		}
		final int type = typeOf(stack);
		if (type == NONE || type == LEATHER || !typeEnabled(type)) {
			return;
		}
		final Integer previous = playerArmor.get(name);
		if (previous != null && type <= previous) {
			return;
		}
		playerArmor.put(name, type);
		final String message;
		switch (type) {
			case CHAINMAIL :
				message = "§fChainmail Armor";
				break;
			case IRON :
				message = "§fIron Armor";
				break;
			default :
				message = "§bDiamond Armor";
		}
		ctx.chat(ctx.state().formatName(name) + " §7bought " + message + " §7(§b"
				+ BedWars.distance(ctx.state(), entity) + "§7)");
		final String mode = ctx.config().get("armor.ping");
		if ("None".equalsIgnoreCase(mode)) {
			return;
		}
		if ("Diamond Only".equalsIgnoreCase(mode) && type != DIAMOND) {
			return;
		}
		ctx.ping();
	}

	private static int typeOf(final ItemStack stack) {
		if (!Items.isArmor(stack.id)) {
			return NONE;
		}
		switch (Items.armorMaterial(stack.id)) {
			case 0 :
				return LEATHER;
			case 1 :
				return CHAINMAIL;
			case 2 :
				return IRON;
			case 3 :
				return DIAMOND;
			default :
				return NONE;
		}
	}

	private boolean typeEnabled(final int type) {
		switch (type) {
			case CHAINMAIL :
				return ctx.config().bool("armor.chainmail");
			case IRON :
				return ctx.config().bool("armor.iron");
			case DIAMOND :
				return ctx.config().bool("armor.diamond");
			default :
				return false;
		}
	}

	@Override
	public void onWorldLoad() {
		playerArmor.clear();
	}
}
