package bwproxy.feature;

import bwproxy.Config;
import bwproxy.game.GameState;

/** What a feature may do: read state and config, talk to the player. */
public interface FeatureContext {
	Config config();

	GameState state();

	/** Chat line for the player, with the proxy prefix. */
	void chat(String message);

	/** The ping used by every alert (random.orb at the player's position). */
	void ping();
}
