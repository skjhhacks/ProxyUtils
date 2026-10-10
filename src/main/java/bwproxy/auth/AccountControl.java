package bwproxy.auth;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** What the in-game {@code /bwp account} command may do. Network work is asynchronous. */
public interface AccountControl {
	/** One line per stored account, the selected one marked. */
	List<String> describe();

	/** Name of the account new connections will use, or null. */
	String selectedName();

	/** Selects a stored account for the next connection (silent sign-in, so it may take a moment). */
	void switchTo(String name, BiConsumer<Boolean, String> done);

	/** Starts the device-code flow; link and code are sent through {@code chat}. */
	void addNew(Consumer<String> chat, BiConsumer<Boolean, String> done);

	/** Forgets a stored account. Returns a message for the player. */
	String remove(String name);
}
