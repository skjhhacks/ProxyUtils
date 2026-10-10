package bwproxy;

import bwproxy.auth.AccountControl;
import bwproxy.auth.AccountManager;
import bwproxy.feature.Feature;
import bwproxy.game.BedWars;
import bwproxy.game.GameState;
import bwproxy.util.Json;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Every /bwp command, usable from two places: typed in game (output goes to chat) and typed in
 * the proxy's own console window (output goes to stdout, prefix-free). Same grammar, same
 * behaviour; only the output sink and the "signed in, reconnect" nudge differ.
 */
public final class BwpCommands {
	/** Output sink for command replies. */
	public interface Out {
		void line(String text);
	}

	/** What a command needs; everything may be null where the session cannot supply it. */
	public static final class Ctx {
		public final Config config;
		public final List<Feature> features;
		public final AccountControl accounts;
		/** Live play state; null when typed from the console (status/debug degrade gracefully). */
		public final GameState state;
		/** Called when a sign-in or switch needs the player to reconnect; no-op on console. */
		public final Runnable reconnect;
		/** Console extras: kicks the "disconnect screen" path on console (ignored in game). */
		public final Runnable quit;

		public Ctx(final Config config, final List<Feature> features, final AccountControl accounts,
				final GameState state, final Runnable reconnect) {
			this(config, features, accounts, state, reconnect, null);
		}

		public Ctx(final Config config, final List<Feature> features, final AccountControl accounts,
				final GameState state, final Runnable reconnect, final Runnable quit) {
			this.config = config;
			this.features = features;
			this.accounts = accounts;
			this.state = state;
			this.reconnect = reconnect;
			this.quit = quit;
		}
	}

	private static final String CHAT_RESET = "§r";
	/** Console output is best plain, so colour codes are stripped there. */
	private static final String STRIP = "§";

	private final Ctx ctx;
	private final Out out;
	private final boolean toConsole;

	public BwpCommands(final Ctx ctx, final Out out, final boolean toConsole) {
		this.ctx = ctx;
		this.out = out;
		this.toConsole = toConsole;
	}

	/** Runs one command body, without any /bwp prefix. Empty string prints the help. */
	public void run(final String args) {
		final String[] a = args.isEmpty() ? new String[0] : args.split("\\s+");
		if (a.length == 0 || a[0].equalsIgnoreCase("help") || a[0].equalsIgnoreCase("status")) {
			help();
			return;
		}
		final String sub = a[0].toLowerCase(Locale.ROOT);
		if (sub.equals("save")) {
			try {
				ctx.config.save();
				out.line("§aSettings saved.");
			} catch (final IOException e) {
				out.line("§cCould not save: " + e.getMessage());
			}
		} else if (sub.equals("hud") && a.length >= 2) {
			ctx.config.set("hud.output", a[1].toLowerCase(Locale.ROOT));
			out.line("§7HUD output: §f" + ctx.config.get("hud.output"));
		} else if (sub.equals("set") && a.length >= 3) {
			if (!ctx.config.known(a[1])) {
				out.line("§cUnknown setting " + a[1]);
				return;
			}
			ctx.config.set(a[1], a[2]);
			out.line("§7" + a[1] + " = §f" + a[2]);
		} else if (sub.equals("account") || sub.equals("accounts")) {
			account(a);
		} else if (sub.equals("debug")) {
			debug();
		} else if (sub.equals("stop") || sub.equals("exit") || sub.equals("quit")) {
			if (ctx.quit != null) {
				out.line("§7Stopping the proxy...");
				ctx.quit.run();
			} else {
				out.line("§7Only from the proxy's console window.");
			}
		} else {
			for (final Feature f : ctx.features) {
				if (f.id().equals(sub)) {
					final boolean on = a.length >= 2 ? a[1].equalsIgnoreCase("on") : !f.enabled();
					ctx.config.set(f.id() + ".enabled", String.valueOf(on));
					out.line("§7" + f.title() + ": " + (on ? "§aon" : "§coff"));
					return;
				}
			}
			out.line("§cUnknown command. Try bwp help");
		}
	}

	private void help() {
		out.line("§bBed Wars proxy §7- features:");
		for (final Feature f : ctx.features) {
			out.line(" §7" + f.id() + " §8(" + f.title() + ") " + (f.enabled() ? "§aon" : "§coff"));
		}
		if (ctx.config != null) {
			out.line("§7HUD output: §f" + ctx.config.get("hud.output"));
		}
		out.line("§7" + prefix() + "<feature> [on|off]  " + prefix() + "hud <sidebar|tablist|actionbar|off>");
		out.line("§7" + prefix() + "set <key> <value>  " + prefix() + "save  " + prefix() + "debug");
		out.line("§7" + prefix() + "account  (list, switch, add or remove Microsoft accounts)");
		if (toConsole) {
			out.line("§7stop  (close the proxy)");
		}
	}

	private String prefix() {
		return toConsole ? "" : "/bwp ";
	}

	private void debug() {
		if (ctx.state == null) {
			out.line("§7No client is connected right now, so there is no game state to show.");
			return;
		}
		out.line("§7Status: §f" + BedWars.status(ctx.state) + "§7, self: §f" + ctx.state.selfName);
		final List<String> sb = ctx.state.sidebar();
		for (int i = 0; i < sb.size(); i++) {
			out.line("§8[" + i + "] " + CHAT_RESET + sb.get(i));
		}
	}

	private void account(final String[] a) {
		if (ctx.accounts == null) {
			out.line("§cAccounts are not available in offline test mode.");
			return;
		}
		final String action = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "list";
		if (action.equals("list")) {
			out.line("§bSaved accounts" + (ctx.state != null ? " §7(playing as §f" + ctx.state.selfName + "§7)" : ""));
			for (final String line : ctx.accounts.describe()) {
				out.line(line);
			}
			out.line("§7" + prefix() + "account <name|number>  switch   " + prefix() + "account new   "
					+ prefix() + "account remove <name>");
			out.line("§7" + prefix() + "account token <refresh token>   " + prefix()
					+ "account cookie <cookie file path>");
			if (ctx.accounts instanceof AccountManager && ((AccountManager) ctx.accounts).pinned()) {
				out.line("§eAn explicit account is pinned (won this one); " + prefix()
						+ "account unpin to let each client play as its own account again.");
			}
		} else if (action.equals("new") || action.equals("add")) {
			out.line("§7Starting a new Microsoft sign-in...");
			ctx.accounts.addNew(line -> out.line("§e" + line), (ok, msg) -> done(ok, msg,
					"Signed in. " + msg + "."));
		} else if (action.equals("remove") && a.length >= 3) {
			out.line("§7" + ctx.accounts.remove(a[2]));
		} else if (action.equals("pin")) {
			pin(a);
		} else if ((action.equals("follow") || action.equals("unpin"))
				&& ctx.accounts instanceof AccountManager) {
			((AccountManager) ctx.accounts).unpin();
			out.line("§7Re-released: connections again match the username each client reports.");
		} else if (action.equals("token") && a.length >= 3) {
			out.line("§7Signing in with the pasted refresh token...");				async(() -> {
					if (ctx.accounts instanceof AccountManager) {
						return ((AccountManager) ctx.accounts).addFromRefreshToken(a[2]);
					}
					return null;
				});
		} else if (action.equals("cookie") && a.length >= 3) {
			out.line("§7Reading cookies from " + a[2] + "...");				async(() -> {
					if (ctx.accounts instanceof AccountManager) {
						return ((AccountManager) ctx.accounts).addFromCookieFile(
								java.nio.file.Paths.get(expandPath(a[2])));
					}
					return null;
				});
		} else {
			// "<name>" or "2"
			out.line("§7Switching to " + a[1] + "...");
			ctx.accounts.switchTo(a[1], (ok, msg) -> done(ok, msg, msg + "."));
		}
	}

	/** account pin on|off (or just "pin" to see the current state). */
	private void pin(final String[] a) {
		if (!(ctx.accounts instanceof AccountManager)) {
			out.line("§cAccounts are not available in offline test mode.");
			return;
		}
		final AccountManager am = (AccountManager) ctx.accounts;
		if (a.length >= 3 && a[2].equalsIgnoreCase("off")) {
			am.unpin();
			out.line("§7Released: each client plays as the account matching its username again.");
		} else if (a.length >= 3 && a[2].equalsIgnoreCase("on")) {
			am.setPinned(true);
			out.line("§7Pinned: every connection uses " + am.selectedName() + " until you unpin.");
		} else {
			out.line("§7" + (am.pinned() ? "Pinned" : "Not pinned")
					+ " (" + prefix() + "account pin on|off)");
		}
	}

	/** Sign-in paths finish on their own thread; this is what happens when they do. */
	private void done(final boolean ok, final String msg, final String extra) {
		if (ok) {
			out.line("§a" + extra);
			if (ctx.reconnect != null) {
				ctx.reconnect.run();
			}
		} else {
			out.line("§c" + msg);
		}
	}

	private void async(final java.util.concurrent.Callable<AccountManager.Account> work) {
		new Thread(() -> {
			try {
				final AccountManager.Account acc = work.call();
				if (acc == null) {
					out.line("§cSign-in failed (no account store).");
					return;
				}
				out.line("§aSigned in as " + acc.name + ".");
				if (ctx.reconnect != null) {
					ctx.reconnect.run();
				}
			} catch (final Exception e) {
				out.line("§c" + e.getMessage());
			}
		}, "bwproxy-command").start();
	}

	/** Chat and the console cannot pass spaces well inside paths, so tolerate ~ and quotes. */
	private static String expandPath(final String raw) {
		String p = raw.trim().replace("\"", "");
		if (p.startsWith("~")) {
			p = System.getProperty("user.home") + p.substring(1);
		}
		return p;
	}
}
