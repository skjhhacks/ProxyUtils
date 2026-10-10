package bwproxy.feature;

import bwproxy.game.GameState;
import bwproxy.game.ItemStack;
import java.util.Collections;
import java.util.List;

public abstract class Feature {
	protected final FeatureContext ctx;

	protected Feature(final FeatureContext ctx) {
		this.ctx = ctx;
	}

	/** Config key prefix and /bwp name. */
	public abstract String id();

	public abstract String title();

	public boolean enabled() {
		return ctx.config().bool(id() + ".enabled");
	}

	/** Equipment of an enemy player in a running Bed Wars game (slot 0 held, 2 leggings, 3 chest). */
	public void onEnemyEquipment(final GameState.Entity entity, final String name, final int slot,
			final ItemStack stack) {
	}

	/** Chat or system message without formatting codes. */
	public void onChat(final String plain) {
	}

	/** Called about once a second. */
	public void onSecond() {
	}

	public void onWorldLoad() {
	}

	/** Extra lines for the HUD (tab list / action bar). */
	public List<String> hudLines() {
		return Collections.emptyList();
	}

	/**
	 * Short segments (about 20 characters each) that are packed into the crowded sidebar. Defaults
	 * to the full lines.
	 */
	public List<String> hudCompact() {
		return hudLines();
	}
}
