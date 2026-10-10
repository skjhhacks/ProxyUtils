package bwproxy.feature;

import bwproxy.game.BedWars;
import bwproxy.game.GameState;
import bwproxy.game.ItemStack;
import bwproxy.game.Items;
import bwproxy.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Warns when enemies hold important items (port of OpenUtils ItemAlertsFeature). */
public final class ItemAlerts extends Feature {
	private static final long COOLDOWN_MS = 10_000L;

	private enum Match {
		EXACT, NAME_SUBSTRING, POTION_SUBSTRING
	}

	private static final class Rule {
		final int key;
		final String message;
		final boolean important;
		final String option;
		final Match match;
		final String text;

		Rule(final int key, final String message, final boolean important, final String option,
				final Match match, final String text) {
			this.key = key;
			this.message = message;
			this.important = important;
			this.option = option;
			this.match = match;
			this.text = text;
		}
	}

	private static final class AlertState {
		long lastTime;
		int lastKey = Integer.MIN_VALUE;
	}

	// Synthetic keys for name-matched items (they have no item id of their own).
	private static final int POTION_JUMP = -1;
	private static final int POTION_SPEED = -2;
	private static final int POTION_INVIS = -3;
	private static final int POPUP_TOWER = -4;
	private static final int DREAM_DEFENDER = -5;
	private static final int ROTATION_BASE = -100;
	private final Map<Integer, Rule> rulesByKey = new HashMap<>();
	private final List<Rule> rules = new ArrayList<>();
	private final Map<String, AlertState> alerts = new HashMap<>();

	public ItemAlerts(final FeatureContext ctx) {
		super(ctx);
		exact(Items.IRON_SWORD, "§fIron Sword", false, "items.iron_sword");
		exact(Items.DIAMOND_SWORD, "§bDiamond Sword", true, "items.diamond_sword");
		exact(Items.FIRE_CHARGE, "§cFireball", false, "items.fireball");
		exact(Items.STICK, "§dKnockback Stick", false, "items.knockback_stick");
		exact(Items.BOW, "§2Bow", false, "items.bow");
		exact(Items.TNT, "§cT§fN§cT", false, "items.tnt");
		exact(Items.GOLDEN_APPLE, "§6Golden Apple", false, "items.golden_apple");
		exact(Items.WATER_BUCKET, "§bWater Bucket", false, "items.water_bucket");
		exact(Items.GOLDEN_PICKAXE, "§6Gold Pickaxe", false, "items.gold_pickaxe");
		exact(Items.DIAMOND_PICKAXE, "§bDiamond Pickaxe", false, "items.diamond_pickaxe");
		exact(Items.SNOWBALL, "§fBedbug", false, "items.bedbug");
		exact(Items.MILK_BUCKET, "§fMilk", false, "items.potions");
		exact(Items.ENDER_PEARL, "§3Ender Pearl", true, "items.pearl");
		exact(Items.EGG, "§eBridge Egg", true, "items.bridge_egg");
		exact(Items.OBSIDIAN, "§5Obsidian", true, "items.obsidian");
		add(POTION_JUMP, "§aJump Boost Potion", true, "items.potions", Match.POTION_SUBSTRING,
				"jump");
		add(POTION_SPEED, "§bSpeed Potion", true, "items.potions", Match.POTION_SUBSTRING,
				"speed");
		add(POTION_INVIS, "§fInvisibility Potion", true, "items.potions",
				Match.POTION_SUBSTRING, "invisibility");
		add(POPUP_TOWER, "§6Pop-up Tower", true, "items.popup_tower", Match.NAME_SUBSTRING,
				"pop-up tower");
		add(DREAM_DEFENDER, "§fDream Defender", true, "items.dream_defender",
				Match.NAME_SUBSTRING, "dream defender");
		int k = ROTATION_BASE;
		rotation(k--, "§4Machine Gun Bow", "machine gun bow");
		rotation(k--, "§dCharlie the Unicorn", "charlie");
		rotation(k--, "§bIce Bridge", "ice bridge");
		rotation(k--, "§cSleeping Dust", "sleeping dust");
		rotation(k--, "§2Devastator Bow", "devastator");
		rotation(k--, "§eMiracle of the Stars", "miracle");
		rotation(k--, "§2Bridge Zapper", "bridge zapper");
		rotation(k--, "§fShuriken", "shuriken");
		rotation(k--, "§8Block Shuffler", "block shuffler");
		rotation(k--, "§cUnstable Teleportation Device", "unstable teleportation device");
		rotation(k--, "§6Snowman", "snowman");
		rotation(k--, "§fMega §cT§fN§cT", "mega tnt");
		rotation(k, "§fWind Charge", "wind charge");
	}

	private void exact(final int itemId, final String message, final boolean important,
			final String option) {
		add(itemId, message, important, option, Match.EXACT, null);
	}

	private void rotation(final int key, final String message, final String text) {
		add(key, message, false, "items.rotation", Match.NAME_SUBSTRING, text);
	}

	private void add(final int key, final String message, final boolean important,
			final String option, final Match match, final String text) {
		final Rule r = new Rule(key, message, important, option, match, text);
		rules.add(r);
		rulesByKey.put(key, r);
	}

	@Override
	public String id() {
		return "items";
	}

	@Override
	public String title() {
		return "Item Alerts";
	}

	@Override
	public void onEnemyEquipment(final GameState.Entity entity, final String name, final int slot,
			final ItemStack stack) {
		if (slot != 0) {
			return;
		}
		final int key = effectiveKey(stack);
		final Rule rule = rulesByKey.get(key);
		if (rule == null || !ctx.config().bool(rule.option)) {
			return;
		}
		final long now = System.currentTimeMillis();
		final AlertState state = alerts.computeIfAbsent(name, n -> new AlertState());
		if (state.lastKey == key && now - state.lastTime < COOLDOWN_MS) {
			return;
		}
		ctx.chat(ctx.state().formatName(name) + " §7has " + message(entity, key, stack));
		if (shouldPing(rule)) {
			ctx.ping();
		}
		state.lastKey = key;
		state.lastTime = now;
	}

	private int effectiveKey(final ItemStack stack) {
		final String name = Text.stripColorCodes(stack.customName()).toLowerCase(Locale.ROOT);
		if (stack.id == Items.POTION) {
			for (final Rule r : rules) {
				if (r.match == Match.POTION_SUBSTRING && name.contains(r.text)) {
					return r.key;
				}
			}
		}
		for (final Rule r : rules) {
			if (r.match == Match.NAME_SUBSTRING && name.contains(r.text)) {
				return r.key;
			}
		}
		return stack.id;
	}

	private String message(final GameState.Entity entity, final int key, final ItemStack stack) {
		final String distance = "§7(§b" + BedWars.distance(ctx.state(), entity) + "§7)";
		if (key == Items.BOW && stack.isEnchanted()) {
			final int n = stack.enchantmentCount();
			if (n == 1) {
				return "§dPower Bow " + distance;
			}
			if (n == 2) {
				return "§6Punch Bow " + distance;
			}
		}
		return rulesByKey.get(key).message + " " + distance;
	}

	private boolean shouldPing(final Rule rule) {
		final String mode = ctx.config().get("items.ping");
		if ("None".equalsIgnoreCase(mode)) {
			return false;
		}
		if ("Important".equalsIgnoreCase(mode)) {
			return rule.important;
		}
		return true;
	}

	@Override
	public void onWorldLoad() {
		alerts.clear();
	}
}
