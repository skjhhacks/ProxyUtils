package bwproxy;

import java.io.IOException;

import bwproxy.auth.AccountManager;
import bwproxy.auth.AccountMenu;
import bwproxy.auth.MicrosoftAuth;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;

public final class Main {
	private Main() {
	}

	public static void main(final String[] args) throws Exception {
		boolean noAuth = false;
		boolean debug = false;
		boolean newLogin = false;
		boolean menu = false;
		String account = null;
		Path configPath = Paths.get("bedwars-proxy.properties");
		String port = null;
		String upstream = null;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--no-auth" :
					noAuth = true;
					break;
				case "--debug" :
					debug = true;
					break;
				case "--new-login" :
					newLogin = true;
					break;
				case "--accounts" :
					menu = true;
					break;
				case "--account" :
					account = args[++i];
					break;
				case "--config" :
					configPath = Paths.get(args[++i]);
					break;
				case "--port" :
					port = args[++i];
					break;
				case "--upstream" :
					upstream = args[++i];
					break;
				default :
					System.out.println("Usage: java -jar BedwarsProxy.jar [options]\n"
							+ "  --accounts          show the account menu (pick / add / remove)\n"
							+ "  --account <name|n>  use a saved account without asking\n"
							+ "  --new-login         add another Microsoft account now\n"
							+ "  --debug             print extra diagnostics\n"
							+ "  --config <file>  --port <n>  --upstream <host[:port]>\n"
							+ "  --no-auth           offline test mode (no account)");
					return;
			}
		}
		final Config config = new Config(configPath);
		config.load();
		if (port != null) {
			config.set("listen.port", port);
		}
		if (upstream != null) {
			final int colon = upstream.lastIndexOf(':');
			config.set("upstream.host", colon > 0 ? upstream.substring(0, colon) : upstream);
			if (colon > 0) {
				config.set("upstream.port", upstream.substring(colon + 1));
			}
		}

		final ProxyServer.LoginResolver profiles;
		AccountManager accounts = null;
		if (noAuth) {
			final MicrosoftAuth.Profile offline = new MicrosoftAuth.Profile(null,
					"00000000000000000000000000000000", "Tester");
			profiles = name -> offline;
		} else {
			// The live.com device-code flow hands out a cookie that the token polls expect back.
			java.net.CookieHandler.setDefault(new java.net.CookieManager());
			final Path store = Paths.get(System.getProperty("user.home"), ".bedwars-proxy",
					"accounts.json");
			accounts = new AccountManager(store, new MicrosoftAuth());
			accounts.load();
			try {
				new AccountMenu(accounts, new BufferedReader(new InputStreamReader(System.in)),
						System.out).choose(account, newLogin, menu);
			} catch (final Exception e) {
				System.out.println();
				System.out.println("[auth] " + e.getMessage());
				System.out.println("[auth] Nothing was changed on your account. Run again with --debug"
						+ " and send the output if this keeps happening.");
				if (debug) {
					e.printStackTrace(System.out);
				}
				System.exit(1);
				return;
			}
			// Each connection plays as the saved account matching the name its client reports
			// (vanilla launcher, Lunar, Prism, ... all send their signed-in username).
			profiles = accounts::profileForClient;
		}

		final ProxyServer server = new ProxyServer(config, profiles, debug, accounts);
		final int bound = server.start();
		System.out.println("[proxy] Add a server in Minecraft 1.8.9 with address:  localhost"
				+ (bound == 25565 ? "" : ":" + bound));
		System.out.println("[proxy] Forwarding to " + config.get("upstream.host") + ":"
				+ config.get("upstream.port"));
		System.out.println("[proxy] Commands work here too: type help (same as /bwp in game),"
				+ " account, timers, hud, stop...");
		serveWithConsole(server, config, accounts, debug);
	}

	/**
	 * Runs the accept loop on a thread and turns this console window into a live command
	 * prompt: every /bwp command works here without being in game (accounts, settings, HUD),
	 * plus stop to shut the proxy down. EOF just falls back to plain serving.
	 */
	private static void serveWithConsole(final ProxyServer server, final Config config,
			final AccountManager accounts, final boolean debug) throws IOException {
		final Thread accept = new Thread(server::serve, "bwproxy-accept");
		accept.setDaemon(true);
		accept.start();
		System.out.println("");
		final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			System.out.print("bwp> ");
			System.out.flush();
			final String line = in.readLine();
			if (line == null) {
				// stdin closed (e.g. started without a console): keep serving until killed
				try {
					accept.join();
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return;
			}
			final String t = line.trim();
			if (t.isEmpty()) {
				continue;
			}
			if (t.equalsIgnoreCase("stop") || t.equalsIgnoreCase("exit")
					|| t.equalsIgnoreCase("quit")) {
				System.out.println("[proxy] Stopping...");
				server.stop();
				System.exit(0);
				return;
			}
			final String body = t.toLowerCase(java.util.Locale.ROOT).startsWith("/bwp")
					? t.substring(4).trim() : t;
			final BwpCommands.Ctx ctx = new BwpCommands.Ctx(config,
					ProxyServer.consoleFeatures(config), accounts, null, null, server::stop);
			new BwpCommands(ctx, msg -> System.out.println("  "
					+ msg.replaceAll("\u00a7.", "")), true).run(body);
		}
	}
}
