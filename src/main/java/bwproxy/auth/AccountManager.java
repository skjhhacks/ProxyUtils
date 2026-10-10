package bwproxy.auth;

import bwproxy.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The Microsoft accounts you have signed in with, and which one new connections use. Only refresh
 * tokens are stored (in {@code ~/.bedwars-proxy/accounts.json}); the Minecraft access token is
 * obtained on demand and kept in memory.
 */
public final class AccountManager implements AccountControl {
	/** Seam for tests: the real implementation is {@link MicrosoftAuth}. */
	public interface Authenticator {
		MicrosoftAuth.Result signInWithRefreshToken(String refreshToken) throws Exception;

		MicrosoftAuth.Result signInWithDeviceCode(Consumer<List<String>> prompt) throws Exception;

		/** Optional extra: cookie-file sign in. The real MicrosoftAuth implements this. */
		interface CookieCapable {
			MicrosoftAuth.Result signInWithCookieFile(Path cookieFile) throws Exception;
		}
	}

	public static final class Account {
		public final String uuid;
		public final String name;
		private String refreshToken;

		Account(final String uuid, final String name, final String refreshToken) {
			this.uuid = uuid;
			this.name = name;
			this.refreshToken = refreshToken;
		}
	}

	private static final long MAX_TOKEN_AGE_MS = 12L * 60 * 60 * 1000;
	private final Path file;
	private final Authenticator auth;
	private final List<Account> accounts = new ArrayList<>();
	private final AtomicBoolean addInProgress = new AtomicBoolean();
	private String selectedUuid;
	private MicrosoftAuth.Profile profile;
	private long profileObtained;
	/**
	 * When true (the default), the fallback account follows the one the Minecraft Launcher
	 * currently has active. Connections whose client reports a username of a saved account
	 * always use that account, whatever this is set to.
	 */
	private boolean followLauncher = true;
	/**
	 * When an explicit pick exists (console/account command sign-in or switch), it wins over
	 * the client-name router until released: you said which account to play, so the username
	 * a client happens to report must not silently override it. Released by "account pin off",
	 * the menu F toggle or turning launcher-follow back on.
	 */
	private boolean pinnedDefault = false;
	/** Extra in-memory profiles for accounts reached only through the client-name router. */
	private final Map<String, MicrosoftAuth.Profile> profileCache = new LinkedHashMap<>();
	private final Map<String, Long> profileCacheAge = new LinkedHashMap<>();
	/** Set via -D to force every connection to one stored account (overrides the router). */
	private static final String FORCE_NAME = System.getProperty("bwproxy.account");

	public AccountManager(final Path file, final Authenticator auth) {
		this.file = file;
		this.auth = auth;
	}

	// ---- persistence ----

	public synchronized void load() {
		accounts.clear();
		selectedUuid = null;
		try {
			if (!Files.exists(file)) {
				return;
			}
			final Object root = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
			selectedUuid = Json.str(root, "selected");
			final String follow = Json.str(root, "follow_launcher");
			followLauncher = follow == null ? true : Boolean.parseBoolean(follow);
			// "selected" was always meant as an explicit choice once set by a command
			pinnedDefault = selectedUuid != null && Json.str(root, "pinned") == null
					? false : Boolean.parseBoolean(Json.str(root, "pinned"));
			final List<Object> list = Json.list(Json.obj(root).get("accounts"));
			if (list != null) {
				for (final Object o : list) {
					final String uuid = Json.str(o, "uuid");
					final String name = Json.str(o, "name");
					final String token = Json.str(o, "refresh_token");
					if (uuid != null && name != null && token != null) {
						accounts.add(new Account(uuid, name, token));
					}
				}
			}
		} catch (final IOException | RuntimeException e) {
			System.out.println("[auth] Could not read saved accounts (" + e.getMessage() + "); starting empty.");
			accounts.clear();
		}
	}

	private void save() {
		try {
			Files.createDirectories(file.getParent());
			final List<Object> list = new ArrayList<>();
			for (final Account a : accounts) {
				final Map<String, Object> m = new LinkedHashMap<>();
				m.put("uuid", a.uuid);
				m.put("name", a.name);
				m.put("refresh_token", a.refreshToken);
				list.add(m);
			}
			final Map<String, Object> root = new LinkedHashMap<>();
			root.put("selected", selectedUuid);
			root.put("follow_launcher", String.valueOf(followLauncher));
			root.put("pinned", String.valueOf(pinnedDefault));
			root.put("accounts", list);
			Files.write(file, Json.write(root).getBytes(StandardCharsets.UTF_8));
		} catch (final IOException e) {
			System.out.println("[auth] Could not save accounts: " + e.getMessage());
		}
	}

	// ---- queries ----

	public synchronized List<Account> accounts() {
		return new ArrayList<>(accounts);
	}

	public synchronized Account selected() {
		for (final Account a : accounts) {
			if (a.uuid.equals(selectedUuid)) {
				return a;
			}
		}
		return null;
	}

	/** Finds by 1-based number, name (case-insensitive) or uuid. */
	public synchronized Account find(final String key) {
		if (key == null) {
			return null;
		}
		final String k = key.trim();
		try {
			final int n = Integer.parseInt(k);
			if (n >= 1 && n <= accounts.size()) {
				return accounts.get(n - 1);
			}
		} catch (final NumberFormatException ignored) {
			// not a number
		}
		for (final Account a : accounts) {
			if (a.name.equalsIgnoreCase(k) || a.uuid.equalsIgnoreCase(k)) {
				return a;
			}
		}
		return null;
	}

	/** The profile new connections log in with; refreshed silently when it gets old. */
	public synchronized MicrosoftAuth.Profile currentProfile() {
		final Account a = selected();
		return profileFor(a);
	}

	/**
	 * The profile to use for a specific stored account; silently re-signed when it gets old.
	 */
	public synchronized MicrosoftAuth.Profile profileFor(final Account a) {
		if (a == null) {
			throw new IllegalStateException("No account selected. Run switch-account.bat or /bwp account.");
		}
		MicrosoftAuth.Profile cached = profileCache.get(a.uuid);
		final Long cachedAt = profileCacheAge.get(a.uuid);
		if (cached == null || cachedAt == null
				|| System.currentTimeMillis() - cachedAt > MAX_TOKEN_AGE_MS) {
			try {
				final MicrosoftAuth.Result r = auth.signInWithRefreshToken(a.refreshToken);
				applyResult(a, r);
				cached = r.profile;
				profileCache.put(a.uuid, r.profile);
				profileCacheAge.put(a.uuid, System.currentTimeMillis());
			} catch (final Exception e) {
				throw new IllegalStateException("Sign-in as " + a.name + " failed: " + e.getMessage(), e);
			}
		}
		return cached;
	}


	/**
	 * If the Minecraft Launcher's active account is one of the stored accounts and it is not the
	 * one currently selected, switch to it silently. Never throws: a missing or unreadable
	 * launcher file just means "keep the current account". A real sign-in happens later, when a
	 * connection needs a profile, so following the launcher costs nothing.
	 */
	private void followLauncherAccount() {
		final String uuid = new LauncherAccountScanner().activeUuid();
		if (uuid == null) {
			return;
		}
		for (final Account a : accounts) {
			if (a.uuid.equals(uuid) && !a.uuid.equals(selectedUuid)) {
				markDefault(a);
				System.out.println("[auth] Default play-as: " + a.name
						+ " (from the launcher)");
				return;
			}
		}
	}

	/** Turns launcher-following on or off (from the console menu); releasing the pin too. */
	public synchronized void setFollowLauncher(final boolean follow) {
		if (followLauncher != follow || (follow && pinnedDefault)) {
			if (follow) {
				pinnedDefault = false; // back to "play as whatever the client/launcher says"
			}
			followLauncher = follow;
			save();
		}
	}

	/** True when an explicit account pick is currently overriding per-client routing. */
	public synchronized boolean pinned() {
		return pinnedDefault;
	}

	/** Releases the pin so the client-name router decides again. */
	public synchronized void unpin() {
		if (pinnedDefault) {
			pinnedDefault = false;
			save();
		}
	}

	public synchronized boolean followsLauncher() {
		return followLauncher;
	}

	// ---- changes ----

	/** Makes {@code a} the account for new connections, verifying it with a silent sign-in. */
	public synchronized MicrosoftAuth.Profile select(final Account a) throws Exception {
		final MicrosoftAuth.Result r = auth.signInWithRefreshToken(a.refreshToken);
		selectedUuid = a.uuid;
		useResult(a, r);
		return profile;
	}

	/** Changes the fallback account without signing in; the next connect does that lazily. */
	public synchronized void markDefault(final Account a) {
		selectedUuid = a.uuid;
		profile = null;
		save();
	}

	/** Pin or release the explicit default from the console/in-game commands. */
	public synchronized void setPinned(final boolean pinned) {
		if (pinnedDefault != pinned) {
			pinnedDefault = pinned;
			save();
		}
	}

	/** Device-code sign-in; stores (or updates) the account and selects it. */
	public Account addNew(final Consumer<List<String>> prompt) throws Exception {
		final MicrosoftAuth.Result r = auth.signInWithDeviceCode(prompt);
		return store(r);
	}

	/**
	 * Signs in with a refresh token pasted by the user (one line, no quotes), stores or updates
	 * that account and selects it. Microsoft rotates the token on use, so what is saved differs
	 * from what was pasted afterwards - that is normal.
	 */
	public Account addFromRefreshToken(final String pastedToken) throws Exception {
		final String token = pastedToken == null ? "" : pastedToken.trim();
		if (token.isEmpty()) {
			throw new IOException("The pasted refresh token is empty.");
		}
		final MicrosoftAuth.Result r = auth.signInWithRefreshToken(token);
		return store(r);
	}

	/** Signs in with a Netscape-format browser cookie file, then stores and selects the account. */
	public Account addFromCookieFile(final Path cookieFile) throws Exception {
		if (!(auth instanceof Authenticator.CookieCapable)) {
			throw new IOException("This authenticator cannot read cookie files.");
		}
		final MicrosoftAuth.Result r = ((Authenticator.CookieCapable) auth).signInWithCookieFile(cookieFile);
		return store(r);
	}

	/** Common tail of every add* path: upsert by uuid and make it the default. */
	private synchronized Account store(final MicrosoftAuth.Result r) {
		Account existing = null;
		for (final Account a : accounts) {
			if (a.uuid.equals(r.profile.uuid)) {
				existing = a;
			}
		}
		final Account acc = existing != null ? existing
				: new Account(r.profile.uuid, r.profile.name, r.refreshToken);
		if (existing == null) {
			accounts.add(acc);
		}
		selectedUuid = acc.uuid;
		pinnedDefault = true; // an explicit sign-in means "play as this one now"
		useResult(acc, r);
		return acc;
	}

	private void useResult(final Account a, final MicrosoftAuth.Result r) {
		applyResult(a, r);
	}

	private void applyResult(final Account a, final MicrosoftAuth.Result r) {
		profileCache.put(a.uuid, r.profile);
		profileCacheAge.put(a.uuid, System.currentTimeMillis());
		if (r.refreshToken != null) {
			a.refreshToken = r.refreshToken;
		}
		profile = r.profile;
		profileObtained = System.currentTimeMillis();
		save();
	}

	public synchronized boolean removeAccount(final Account a) {
		final boolean removed = accounts.remove(a);
		if (removed) {
			if (a.uuid.equals(selectedUuid)) {
				selectedUuid = accounts.isEmpty() ? null : accounts.get(0).uuid;
				profile = null;
			}
			save();
		}
		return removed;
	}

	// ---- AccountControl (in-game /bwp account) ----

	@Override
	public synchronized List<String> describe() {
		final List<String> lines = new ArrayList<>();
		for (int i = 0; i < accounts.size(); i++) {
			final Account a = accounts.get(i);
			lines.add((a.uuid.equals(selectedUuid) ? "§a* " : "§7  ") + (i + 1) + ". "
					+ a.name);
		}
		return lines;
	}

	@Override
	public synchronized String selectedName() {
		final Account a = selected();
		return a == null ? null : a.name;
	}

	@Override
	public void switchTo(final String name, final BiConsumer<Boolean, String> done) {
		final Account a = find(name);
		if (a == null) {
			done.accept(false, "No stored account called " + name + ". Try /bwp account");
			return;
		}
		async("bwproxy-switch", () -> {
			try {
				select(a);
				done.accept(true, "Switched to " + a.name);
			} catch (final Exception e) {
				done.accept(false, "Could not sign in as " + a.name + ": " + e.getMessage());
			}
		});
	}

	@Override
	public void addNew(final Consumer<String> chat, final BiConsumer<Boolean, String> done) {
		if (!addInProgress.compareAndSet(false, true)) {
			done.accept(false, "A sign-in is already waiting for a code.");
			return;
		}
		async("bwproxy-add", () -> {
			try {
				final Account a = addNew(lines -> {
					for (final String l : lines) {
						chat.accept(l);
					}
				});
				done.accept(true, "Added " + a.name);
			} catch (final Exception e) {
				done.accept(false, "Sign-in failed: " + e.getMessage());
			} finally {
				addInProgress.set(false);
			}
		});
	}

	@Override
	public String remove(final String name) {
		final Account a = find(name);
		if (a == null) {
			return "No stored account called " + name;
		}
		return removeAccount(a) ? "Removed " + a.name : "Could not remove " + a.name;
	}

	/**
	 * Which account a client connection should play as. {@code clientName} is the username the
	 * client's own launcher (vanilla, Lunar, Prism, Badlion, ...) reports in Login Start.
	 * Order: a forced -Dbwproxy.account name; an explicit pin (from the last console sign-in or
	 * switch); an exact saved account with the client's name; a case-insensitive match; the
	 * default (launcher-follow may have picked a different default).
	 */
	public synchronized MicrosoftAuth.Profile profileForClient(final String clientName) {
		Account a = null;
		final String force = FORCE_NAME;
		if (force != null && !force.trim().isEmpty()) {
			a = find(force.trim());
			if (a == null) {
				throw new IllegalStateException("-Dbwproxy.account=" + force
						+ " does not match any saved account.");
			}
		} else if (pinnedDefault) {
			// An explicit choice is still in force: keep it, whatever the client says.
			a = selected();
		} else if (clientName != null && !clientName.trim().isEmpty()) {
			for (final Account saved : accounts) {
				if (saved.name.equals(clientName.trim())) {
					a = saved;
					break;
				}
			}
			if (a == null) {
				a = find(clientName.trim().toLowerCase(Locale.ROOT));
			}
		}
		if (a == null) {
			a = selected();
		}
		if (a == null && !accounts.isEmpty()) {
			a = accounts.get(0);
		}
		if (a != null && !a.uuid.equals(selectedUuid)) {
			selectedUuid = a.uuid; // remember the fallback for clients that send no usable name
			save();
		}
		return profileFor(a);
	}

	private static void async(final String threadName, final Runnable r) {
		final Thread t = new Thread(r, threadName);
		t.setDaemon(true);
		t.start();
	}

	/** For logs and menus: never includes tokens. */
	public static String label(final Account a) {
		return a.name + " (" + a.uuid.substring(0, Math.min(8, a.uuid.length())).toLowerCase(Locale.ROOT)
				+ ")";
	}
}
