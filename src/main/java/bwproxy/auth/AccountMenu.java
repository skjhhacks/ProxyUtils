package bwproxy.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/** The console account picker shown when the proxy starts. */
public final class AccountMenu {
	private final AccountManager accounts;
	private final BufferedReader in;
	private final PrintStream out;

	public AccountMenu(final AccountManager accounts, final BufferedReader in, final PrintStream out) {
		this.accounts = accounts;
		this.in = in;
		this.out = out;
	}

	/**
	 * Gets one account selected and signed in.
	 *
	 * @param wanted name/number from {@code --account}, or null
	 * @param addNew skip the menu and sign in with a new account
	 * @param forceMenu show the menu even when only one account is stored
	 */
	public MicrosoftAuth.Profile choose(final String wanted, final boolean addNew,
			final boolean forceMenu) throws Exception {
		if (addNew) {
			return addAccount();
		}
		if (wanted != null) {
			final AccountManager.Account a = accounts.find(wanted);
			if (a != null) {
				return signIn(a);
			}
			out.println("[auth] No saved account matches \"" + wanted + "\".");
		}
		final List<AccountManager.Account> list = accounts.accounts();
		if (list.isEmpty()) {
			out.println("[auth] No saved accounts yet. Sign in with the one you play Minecraft on.");
			return addAccount();
		}
		if (!forceMenu && wanted == null) {
			return autoSelect();
		}
		return menu();
	}

	/**
	 * No explicit choice: when launcher-following is on (the default) and the Minecraft
	 * Launcher's active account is one of the stored accounts, use it, so the proxy always plays
	 * as whatever the game itself is logged into. Otherwise keep the last used account.
	 */
	private MicrosoftAuth.Profile autoSelect() throws Exception {
		if (!accounts.followsLauncher()) {
			final AccountManager.Account kept = accounts.selected();
			return kept == null ? addAccount() : signIn(kept);
		}
		final LauncherAccountScanner scanner = new LauncherAccountScanner();
		final String uuid = scanner.activeUuid();
		final String name = scanner.activeName();
		AccountManager.Account match = null;
		if (uuid != null) {
			for (final AccountManager.Account a : accounts.accounts()) {
				if (a.uuid.equals(uuid)) {
					match = a;
				}
			}
		}
		if (match == null) {
			if (name != null) {
				out.println("[auth] Minecraft is signed in as " + name
						+ ". Sign in with that account once (A in the menu) and this proxy "
						+ "will follow it automatically from then on.");
			}
			final AccountManager.Account kept = accounts.selected();
			return kept == null ? addAccount() : signIn(kept);
		}
		try {
			accounts.select(match);
		} catch (final Exception e) {
			out.println("[auth] " + name + ": " + e.getMessage());
			out.print("Remove it and sign in again? [Y/n] ");
			out.flush();
			final String answer = in.readLine();
			if (answer != null && answer.trim().toLowerCase(Locale.ROOT).startsWith("n")) {
				throw e;
			}
			accounts.removeAccount(match);
			return addAccount();
		}
		out.println("[auth] Playing as " + name + " (the launcher's active account).");
		return accounts.currentProfile();
	}

	private MicrosoftAuth.Profile menu() throws Exception {
		while (true) {
			final List<AccountManager.Account> list = accounts.accounts();
			final AccountManager.Account current = accounts.selected();
			out.println();
			out.println("Saved Microsoft accounts:");
			for (int i = 0; i < list.size(); i++) {
				final AccountManager.Account a = list.get(i);
				out.println("  " + (i + 1) + ") " + a.name + (a == current ? "   (last used)" : ""));
			}
			out.println("  A) Add a new account");
			out.println("  T) Add with a pasted refresh token");
			out.println("  C) Add from a browser cookie file");
			out.println("  R) Remove a saved account");
			out.println("  F) Follow account: "
					+ (accounts.followsLauncher()
							? "on - play as whatever Minecraft is logged into (default)"
							: "off - always use the account chosen here"));
			out.print("Choose" + (current != null ? " [Enter = " + current.name + "]" : "") + ": ");
			out.flush();
			final String line = in.readLine();
			if (line == null) {
				if (current != null) {
					return signIn(current);
				}
				throw new IOException("No input available to choose an account.");
			}
			final String c = line.trim().toLowerCase(Locale.ROOT);
			if (c.isEmpty() && current != null) {
				return signIn(current);
			}
			if (c.equals("a")) {
				return addAccount();
			}
			if (c.equals("t")) {
				return addFromToken();
			}
			if (c.equals("c")) {
				return addFromCookieFile();
			}
			if (c.equals("f")) {
				accounts.setFollowLauncher(!accounts.followsLauncher());
				continue;
			}
			if (c.equals("r")) {
				out.print("Remove which number? ");
				out.flush();
				final AccountManager.Account victim = accounts.find(in.readLine());
				if (victim == null) {
					out.println("No such account.");
				} else {
					accounts.removeAccount(victim);
					out.println("Removed " + victim.name + ".");
				}
				continue;
			}
			final AccountManager.Account picked = accounts.find(c);
			if (picked != null) {
				// Picking a specific account means "use this one": stop following the launcher
				// until F is turned on again.
				accounts.setFollowLauncher(false);
				return signIn(picked);
			}
			out.println("Not an option: " + line);
		}
	}

	private MicrosoftAuth.Profile signIn(final AccountManager.Account a) throws Exception {
		try {
			final MicrosoftAuth.Profile p = accounts.select(a);
			out.println("[auth] Signed in as " + p.name);
			return p;
		} catch (final Exception e) {
			out.println("[auth] " + a.name + ": " + e.getMessage());
			out.print("Remove it and sign in again? [Y/n] ");
			out.flush();
			final String answer = in.readLine();
			if (answer != null && answer.trim().toLowerCase(Locale.ROOT).startsWith("n")) {
				throw e;
			}
			accounts.removeAccount(a);
			return addAccount();
		}
	}

	private MicrosoftAuth.Profile addAccount() throws Exception {
		final AccountManager.Account a = accounts.addNew(lines -> {
			out.println();
			out.println("================ MICROSOFT SIGN-IN ================");
			for (final String l : lines) {
				out.println(" " + l);
			}
			out.println(" Tip: use a private/incognito window to pick a different account.");
		 out.println("===================================================");
			out.println();
		});
		out.println("[auth] Signed in as " + a.name);
		return accounts.currentProfile();
	}

	/** T: one refresh token per line (blank line or EOF ends). Signs in and rotates them. */
	private MicrosoftAuth.Profile addFromToken() throws Exception {
		out.println("Paste the refresh token, then press Enter:");
		out.print("> ");
		out.flush();
		final String line = in.readLine();
		try {
			final AccountManager.Account a = accounts.addFromRefreshToken(line);
			out.println("[auth] Signed in as " + a.name);
			return accounts.currentProfile();
		} catch (final Exception e) {
			out.println("[auth] " + e.getMessage());
			return menu();
		}
	}

	/** C: path to a Netscape cookie file exported from a browser signed into login.live.com. */
	private MicrosoftAuth.Profile addFromCookieFile() throws Exception {
		out.println("Path to the cookie file (e.g. " + Paths.get(System.getProperty("user.home"), "Downloads", "cookies.txt") + "):");
		out.print("> ");
		out.flush();
		final String line = in.readLine();
		try {
			final Path p = expand(line);
			final AccountManager.Account a = accounts.addFromCookieFile(p);
			out.println("[auth] Signed in as " + a.name);
			return accounts.currentProfile();
		} catch (final Exception e) {
			out.println("[auth] " + e.getMessage());
			return menu();
		}
	}

	/** ~ and quotes tolerated in the pasted path. */
	private static Path expand(final String raw) throws IOException {
		if (raw == null) {
			throw new IOException("No path entered.");
		}
		String p = raw.trim().replace("\"", "").replace("'", "");
		if (p.startsWith("~")) {
			final String rest = p.length() == 1 ? "" : p.substring(p.indexOf('/') >= 0 ? p.indexOf('/') : 1);
			p = Paths.get(System.getProperty("user.home"), rest).toString();
		}
		final Path path = Paths.get(p);
		if (!Files.exists(path)) {
			throw new IOException("No such file: " + path);
		}
		return path;
	}
}
