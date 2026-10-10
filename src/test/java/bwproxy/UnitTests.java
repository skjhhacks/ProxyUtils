package bwproxy;

import bwproxy.auth.AccountManager;
import bwproxy.auth.MicrosoftAuth;
import bwproxy.net.McConnection;
import bwproxy.net.PacketReader;
import bwproxy.net.PacketWriter;
import bwproxy.util.Json;
import bwproxy.util.Text;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

public final class UnitTests {
	private static int failures;

	private static void check(final String name, final boolean ok, final String detail) {
		check(name + (ok ? "" : " [" + detail + "]"), ok);
	}

	private static void check(final String name, final boolean ok) {
		System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
		if (!ok) {
			failures++;
		}
	}

	public static void main(final String[] args) throws Exception {
		// Known Minecraft server hashes (wiki.vg "Protocol Encryption")
		check("server hash Notch", "4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48"
				.equals(ProxyServer.serverHash("Notch", new byte[0], new byte[0])));
		check("server hash jeb_", "-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1"
				.equals(ProxyServer.serverHash("jeb_", new byte[0], new byte[0])));
		check("server hash simon", "88e16a1019277b15d58faf0541e11910eb756f6"
				.equals(ProxyServer.serverHash("simon", new byte[0], new byte[0])));

		// Encryption + compression round trip over a real socket pair
		final ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
		final Socket a = new Socket("127.0.0.1", ss.getLocalPort());
		final Socket b = ss.accept();
		final McConnection ca = new McConnection(a);
		final McConnection cb = new McConnection(b);
		final byte[] secret = new byte[16];
		Arrays.fill(secret, (byte) 7);
		ca.enableEncryption(secret);
		cb.enableEncryption(secret);
		ca.setCompressionThreshold(32);
		cb.setCompressionThreshold(32);
		final byte[] small = new PacketWriter(0x10).writeString("hi").toByteArray();
		final StringBuilder big = new StringBuilder();
		for (int i = 0; i < 500; i++) {
			big.append("bed wars ");
		}
		final byte[] large = new PacketWriter(0x11).writeString(big.toString()).toByteArray();
		ca.writePacket(small);
		ca.writePacket(large);
		check("encrypted small packet round trip", Arrays.equals(small, cb.readPacket()));
		check("encrypted + compressed large packet round trip", Arrays.equals(large, cb.readPacket()));
		cb.writePacket(large);
		check("reverse direction", Arrays.equals(large, ca.readPacket()));
		ca.close();
		cb.close();
		ss.close();

		// DER -> raw ECDSA conversion verifies against Java's own signature check
		final KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
		g.initialize(new ECGenParameterSpec("secp256r1"));
		boolean allWidth = true;
		for (int i = 0; i < 200; i++) {
			final Signature sig = Signature.getInstance("SHA256withECDSA");
			sig.initSign(g.generateKeyPair().getPrivate());
			sig.update(new byte[]{1, 2, 3});
			allWidth &= MicrosoftAuth.derToRaw(sig.sign(), 32).length == 64;
		}
		check("ECDSA DER->r||s always 64 bytes (200 random signatures)", allWidth);

		check("JSON round trip", "{\"a\":[1,\"x\\n\"],\"b\":true}".equals(Json.write(Json.parse(
				"{\"a\":[1,\"x\\n\"],\"b\":true,\"c\":null}"))));
		check("chat component flatten", "\u00a7aHello \u00a7cWorld".equals(Text.flatten(
				"{\"text\":\"\",\"extra\":[{\"text\":\"Hello \",\"color\":\"green\"},"
						+ "{\"text\":\"World\",\"color\":\"red\"}]}")));
		check("strip codes", "Hello".equals(Text.stripColorCodes("\u00a7aHel\u00a7clo")));
		final PacketReader r = new PacketReader(new PacketWriter(1).writeVarInt(300)
				.writeString("\u00e9").toByteArray());
		r.readVarInt();
		check("varint/string", r.readVarInt() == 300 && "\u00e9".equals(r.readString()));
		accountTests();
		menuTests();
		routerTests();
		sidebarTests();
		System.out.println(failures == 0 ? "ALL UNIT CHECKS PASSED" : failures + " UNIT CHECK(S) FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	/** Pretends to be Microsoft: each device-code sign-in yields the next name in the list. */
	private static final class FakeAuth implements AccountManager.Authenticator,
			AccountManager.Authenticator.CookieCapable {
		private final String[] names = {"Alice", "Bob", "Carol"};
		private int next;
		int refreshCalls;
		boolean refuse;
		java.nio.file.Path lastCookieFile;

		@Override
		public MicrosoftAuth.Result signInWithRefreshToken(final String token) throws Exception {
			refreshCalls++;
			if (refuse) {
				throw new java.io.IOException("expired");
			}
			final String name = token.substring("rt-".length());
			return new MicrosoftAuth.Result(new MicrosoftAuth.Profile("at-" + name, "uuid-" + name, name),
					"rt-" + name);
		}

		@Override
		public MicrosoftAuth.Result signInWithDeviceCode(final java.util.function.Consumer<
				java.util.List<String>> prompt) throws Exception {
			prompt.accept(Arrays.asList("Open: x", "Enter: y"));
			final String name = names[next++ % names.length];
			return new MicrosoftAuth.Result(new MicrosoftAuth.Profile("at-" + name, "uuid-" + name, name),
					"rt-" + name);
		}

		/** In the fake, the cookie file's first data line names the account. */
		@Override
		public MicrosoftAuth.Result signInWithCookieFile(final java.nio.file.Path cookieFile)
				throws Exception {
			lastCookieFile = cookieFile;
			for (final String line : java.nio.file.Files.readAllLines(cookieFile,
					java.nio.charset.StandardCharsets.UTF_8)) {
				final String[] p = line.split("\t");
				if (p.length >= 7 && p[5].equals("fake-account")) {
					final String name = p[6];
					return new MicrosoftAuth.Result(new MicrosoftAuth.Profile("at-" + name,
							"uuid-" + name, name), "rt-" + name);
				}
			}
			throw new java.io.IOException("No login.live.com cookies found in "
					+ cookieFile.getFileName() + " (expected a Netscape cookie file).");
		}
	}

	private static void accountTests() throws Exception {
		final java.nio.file.Path f = java.nio.file.Files.createTempFile("bwaccounts", ".json");
		java.nio.file.Files.delete(f);
		FakeAuth auth = new FakeAuth();
		AccountManager m = new AccountManager(f, auth);
		m.load();
		check("accounts: starts empty", m.accounts().isEmpty());
		final java.util.List<String> shown = new java.util.ArrayList<>();
		m.addNew(shown::addAll);
		m.addNew(shown::addAll);
		check("accounts: device-code prompt shown to the user", shown.size() == 4);
		check("accounts: two stored, newest selected",
				m.accounts().size() == 2 && "Bob".equals(m.selected().name));
		check("accounts: current profile is the selected one",
				"Bob".equals(m.currentProfile().name));
		m.setFollowLauncher(false); // this section tests the saved selection, not the router
		m.select(m.find("alice"));
		check("accounts: switch by name (case-insensitive)", "Alice".equals(m.currentProfile().name));
		m.select(m.find("2"));
		check("accounts: switch by number", "Bob".equals(m.currentProfile().name));
		check("accounts: in-game list marks the selected one", m.describe().get(1).contains("Bob")
				&& m.describe().get(1).contains("\u00a7a*"));

		m = new AccountManager(f, auth);
		m.load();
		check("accounts: survive a restart, selection kept",
				m.accounts().size() == 2 && "Bob".equals(m.selected().name));
		check("accounts: file holds no access tokens", !new String(java.nio.file.Files.readAllBytes(f),
				java.nio.charset.StandardCharsets.UTF_8).contains("at-"));

		m.addNew(shown::addAll); // third name is Carol
		m.addNew(shown::addAll); // wraps to Alice again: must not create a duplicate
		check("accounts: signing in again with a known account does not duplicate it",
				m.accounts().size() == 3 && "Alice".equals(m.selected().name));

		m.removeAccount(m.find("Alice"));
		check("accounts: removing the selected one falls back to another",
				m.accounts().size() == 2 && m.selected() != null);
		check("accounts: remove by name via in-game command",
				"Removed Carol".equals(m.remove("carol")) && m.accounts().size() == 1);

		auth.refuse = true;
		boolean threw = false;
		try {
			m.select(m.accounts().get(0));
		} catch (final Exception e) {
			threw = true;
		}
		check("accounts: an expired saved login is reported, selection unchanged", threw);
		m.removeAccount(m.accounts().get(0));
		check("accounts: no account selected after removing the last",
				m.selected() == null && m.accounts().isEmpty());
		boolean noSel = false;
		try {
			m.currentProfile();
		} catch (final IllegalStateException e) {
			noSel = true;
		}
		check("accounts: logging in with none selected gives a clear error", noSel);

		// ---- pasted refresh-token import ----
		auth = new FakeAuth();
		final java.nio.file.Path f2 = java.nio.file.Files.createTempFile("bwimport", ".json");
		java.nio.file.Files.delete(f2);
		final AccountManager im = new AccountManager(f2, auth);
		im.load();
		final AccountManager.Account fromToken = im.addFromRefreshToken(" rt-Dave \n");
		check("import: pasted token adds its account and selects it",
				"Dave".equals(fromToken.name) && "Dave".equals(im.selected().name));
		check("import: token was trimmed", "Dave".equals(im.currentProfile().name));
		final AccountManager.Account again = im.addFromRefreshToken("rt-Dave");
		check("import: same token again does not duplicate",
				im.accounts().size() == 1 && again.name.equals("Dave"));
		boolean emptyThrown = false;
		try {
			im.addFromRefreshToken("   ");
		} catch (final java.io.IOException e) {
			emptyThrown = true;
		}
		check("import: an empty paste is rejected", emptyThrown);

		// ---- cookie-file import ----
		final java.nio.file.Path cookie = java.nio.file.Files.createTempFile("bwcookies", ".txt");
		java.nio.file.Files.write(cookie, Arrays.asList(
				"# Netscape HTTP Cookie File",
				".live.com\tTRUE\t/\tTRUE\t3784011825\tfake-account\tErin"),
				java.nio.charset.StandardCharsets.UTF_8);
		final AccountManager.Account fromCookie = im.addFromCookieFile(cookie);
		check("import: cookie file adds its account and selects it",
				"Erin".equals(fromCookie.name) && "Erin".equals(im.selected().name)
						&& "Erin".equals(im.currentProfile().name));
		check("import: cookie path passed through to the authenticator",
				cookie.equals(auth.lastCookieFile));
		boolean noCookies = false;
		final java.nio.file.Path emptyCookie = java.nio.file.Files.createTempFile("bwempty", ".txt");
		java.nio.file.Files.write(emptyCookie, Arrays.asList("# nothing relevant"),
				java.nio.charset.StandardCharsets.UTF_8);
		try {
			im.addFromCookieFile(emptyCookie);
		} catch (final java.io.IOException e) {
			noCookies = e.getMessage().contains("No login.live.com cookies");
		}
		check("import: a cookie file without live.com cookies says so", noCookies);
		java.nio.file.Files.delete(cookie);
		java.nio.file.Files.delete(emptyCookie);
		java.nio.file.Files.delete(f2);
	}

	private static String runMenu(final AccountManager m, final String input, final String wanted,
			final boolean addNew, final boolean forceMenu) throws Exception {
		final java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
		final bwproxy.auth.AccountMenu menu = new bwproxy.auth.AccountMenu(m,
				new java.io.BufferedReader(new java.io.StringReader(input)),
				new java.io.PrintStream(sink, true, "UTF-8"));
		return menu.choose(wanted, addNew, forceMenu).name;
	}

	private static void menuTests() throws Exception {
		final java.nio.file.Path f = java.nio.file.Files.createTempFile("bwmenu", ".json");
		java.nio.file.Files.delete(f);
		final AccountManager m = new AccountManager(f, new FakeAuth());
		m.load();
		check("menu: no accounts -> goes straight to sign-in", "Alice".equals(
				runMenu(m, "", null, false, false)));
		check("menu: public launcher account not stored, sign in with it", "Alice".equals(
				runMenu(m, "", null, false, false)));
		check("menu: --new-login adds a second account", "Bob".equals(
				runMenu(m, "", null, true, false)));
		check("menu: pick a number", "Alice".equals(runMenu(m, "1\n", null, false, true)));
		check("menu: Enter keeps the last used account", "Alice".equals(
				runMenu(m, "\n", null, false, true)));
		check("menu: --account by name", "Bob".equals(runMenu(m, "", "bob", false, false)));
		check("menu: A adds a new account", "Carol".equals(runMenu(m, "n\na\n\n", null, false, true)));
		check("menu: R removes, then a pick works", "Carol".equals(
				runMenu(m, "r\n1\n2\n", null, false, true)) && m.accounts().size() == 2);
	}

	/** The client-name router: each connection maps to the account its client reports. */
	private static void routerTests() throws Exception {
		final java.nio.file.Path f = java.nio.file.Files.createTempFile("bwrouter", ".json");
		java.nio.file.Files.delete(f);
		final FakeAuth auth = new FakeAuth();
		final AccountManager m = new AccountManager(f, auth);
		m.load();
		// Menu-less setup: add Alice directly through a device-code fake.
		m.addNew(lines -> {
		});
		m.addNew(lines -> {
		});
		// Every add pins the default; release it so the client-name router is under test.
		m.unpin();
		check("router: exact client name picks its own account", "Bob".equals(
				m.profileForClient("Bob").name));
		check("router: case-insensitive client name", "Alice".equals(
				m.profileForClient("ALICE").name));
		check("router: unknown client name falls back to the default", !m.profileForClient(
				"Ghost").name.equals("Ghost"));
		check("router: each account caches its own profile", "Bob".equals(
				m.profileForClient("Bob").name) && "Alice".equals(
				m.profileForClient("Alice").name));
		check("router: cached profiles avoid repeat sign-ins",
				auth.refreshCalls == 0, "refreshCalls=" + auth.refreshCalls);

		// An explicit pick must survive a differently-named client connecting.
		m.select(m.find("Bob"));
		m.setPinned(true);
		check("router: pinned default beats the client's username", "Bob".equals(
				m.profileForClient("Alice").name));
		m.unpin();
		check("router: after unpin, the client name routes again", "Alice".equals(
				m.profileForClient("Alice").name));

		// The pin survives a restart (file round trip).
		m.select(m.find("Bob"));
		m.setPinned(true);
		final AccountManager reloaded = new AccountManager(f, auth);
		reloaded.load();
		final boolean pinSurvived = reloaded.pinned();
		final String viaName = reloaded.profileForClient("Alice").name;
		check("router: the pin survives a restart", pinSurvived && "Bob".equals(viaName));
		reloaded.unpin();
		final AccountManager reloaded2 = new AccountManager(f, auth);
		reloaded2.load();
		check("router: unpinning survives a restart too", !reloaded2.pinned());
	}

	/** GameState must tolerate Hypixel clearing and rebuilding the sidebar. */
	private static void sidebarTests() {
		final bwproxy.game.GameState s = new bwproxy.game.GameState();
		// Create the objective with its title: id, name, action, value.
		s.onObjective(pr(new PacketWriter(0).writeString("sb").writeByte(0)
				.writeString("\u00a7 Title")));
		s.onDisplayObjective(disp("sb"));
		s.onScore(wrap(0x3C, "line", 0, "sb", 3));
		check("sidebar: entry count + lowest score tracked", s.sidebarEntryCount() == 1
				&& s.lowestSidebarScore() == 3);
		check("sidebar: lines come back bottom-first with the title",
				Arrays.asList("\u00a7 Title", "line").equals(s.sidebar()));
		s.onDisplayObjective(disp(""));
		check("sidebar: an explicit clear is reported",
				s.sidebarObjectiveName() == null && s.sidebarEntryCount() == 0);
		// Rows are removed while the display objective stays: the state the
		// diamond timer glitch lives in.
		s.onDisplayObjective(disp("sb"));
		s.onScore(wrap(0x3C, "line", 1, ""));
		check("sidebar: row removal keeps the objective",
				"sb".equals(s.sidebarObjectiveName()) && s.sidebarEntryCount() == 0);
		s.onScore(wrap(0x3C, "line2", 0, "sb", 1));
		s.onScore(wrap(0x3C, "line2", 1, ""));
		check("sidebar: removal is header-aware", s.sidebarEntryCount() == 0);
	}

	private static PacketReader disp(final String name) {
		final PacketReader r = new PacketReader(new PacketWriter(0).writeByte(1).writeString(name)
				.toByteArray());
		r.readVarInt(); // packet id, as the session does before handing to GameState
		return r;
	}

	/** Wraps raw packet bytes, consuming the packet id like the live reader does. */
	private static PacketReader wrap(final int pid, final Object... fields) {
		PacketWriter w = new PacketWriter(pid);
		for (final Object f : fields) {
			if (f instanceof Integer) {
				w = w.writeVarInt((Integer) f);
			} else {
				w = w.writeString((String) f);
			}
		}
		final PacketReader r = new PacketReader(w.toByteArray());
		r.readVarInt(); // packet id
		return r;
	}

	private static PacketReader pr(final PacketWriter w) {
		final PacketReader r = new PacketReader(w.toByteArray());
		r.readVarInt(); // packet id
		return r;
	}

}
