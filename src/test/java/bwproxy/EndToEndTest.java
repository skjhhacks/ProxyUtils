package bwproxy;

import bwproxy.auth.MicrosoftAuth;
import bwproxy.net.McConnection;
import bwproxy.net.PacketReader;
import bwproxy.net.PacketWriter;
import bwproxy.util.Text;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs a fake Hypixel and a fake 1.8.9 client against the real proxy code over localhost sockets,
 * with compression enabled upstream, and checks what the client receives.
 */
public final class EndToEndTest {
	private static int failures;
	private static final UUID ENEMY = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
	private static final UUID MATE = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

	private static void check(final String name, final boolean ok) {
		System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
		if (!ok) {
			failures++;
		}
	}

	public static void main(final String[] args) throws Exception {
		final ServerSocket fake = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
		final List<byte[]> serverReceived = new CopyOnWriteArrayList<>();
		final Thread serverThread = new Thread(() -> runFakeServer(fake, serverReceived));
		serverThread.setDaemon(true);
		serverThread.start();

		final Path cfgFile = Files.createTempFile("bwproxy", ".properties");
		final Config cfg = new Config(cfgFile);
		cfg.set("listen.port", "0");
		cfg.set("upstream.host", "127.0.0.1");
		cfg.set("upstream.port", String.valueOf(fake.getLocalPort()));
		cfg.set("hud.refresh_ms", "250");
		cfg.set("hud.output", "sidebar,tablist");
		final MicrosoftAuth.Profile offline = new MicrosoftAuth.Profile(null,
				"00000000000000000000000000000000", "Tester");
		final ProxyServer proxy = new ProxyServer(cfg, name -> offline, true, new FakeAccounts());
		final int port = proxy.start();
		final Thread serve = new Thread(proxy::serve);
		serve.setDaemon(true);
		serve.start();

		// ---- fake client ----
		final Socket s = new Socket("127.0.0.1", port);
		final McConnection c = new McConnection(s);
		c.writePacket(new PacketWriter(0).writeVarInt(47).writeString("localhost").writeShort(port)
				.writeVarInt(2).toByteArray());
		c.writePacket(new PacketWriter(0).writeString("Whatever").toByteArray());
		final PacketReader ok = new PacketReader(c.readPacket());
		check("client gets Login Success", ok.readVarInt() == 0x02);
		ok.readString();
		check("login name is the account name from the profile, not the typed name",
				"Tester".equals(ok.readString()));

		final List<String> chat = new ArrayList<>();
		final List<String> actionBars = new ArrayList<>();
		final List<String> footers = new ArrayList<>();
		final List<String> scoreEntries = new ArrayList<>();
		final java.util.Map<String, Integer> board = new java.util.LinkedHashMap<>();
		final List<String> sounds = new ArrayList<>();
		final List<String> setSlots = new ArrayList<>();
		boolean sawKeepAlive = false;
		boolean clickSent = false;
		boolean commandReply = false;
		c.writePacket(new PacketWriter(0x04).writeDouble(0).writeDouble(64).writeDouble(0)
				.writeBoolean(true).toByteArray());
		final long end = System.currentTimeMillis() + 8000;
		s.setSoTimeout(500);
		while (System.currentTimeMillis() < end) {
			final byte[] pkt;
			try {
				pkt = c.readPacket();
			} catch (final java.net.SocketTimeoutException e) {
				continue;
			}
			final PacketReader r = new PacketReader(pkt);
			final int id = r.readVarInt();
			if (id == 0x00) {
				sawKeepAlive = true;
			} else if (id == 0x02) {
				final String msg = Text.flatten(r.readString());
				final int pos = r.readByte();
				if (pos == 2) {
					actionBars.add(Text.stripColorCodes(msg));
				} else {
					chat.add(Text.stripColorCodes(msg));
				}
			} else if (id == 0x47) {
				r.readString();
				footers.add(Text.stripColorCodes(Text.flatten(r.readString())));
			} else if (id == 0x3C) {
				final String entry = r.readString();
				final int action = r.readByte();
				if (action == 0) {
					scoreEntries.add(Text.stripColorCodes(entry));
					board.put(entry, 1);
				} else {
					board.remove(entry);
				}
			} else if (id == 0x29) {
				sounds.add(r.readString());
			} else if (id == 0x2F) {
				setSlots.add(r.readByte() + ":" + r.readShort());
			} else if (id == 0x30 && !clickSent) {
				clickSent = true;
				final int windowId = r.readUnsignedByte();
				// left click on shop slot 11, as a 1.8.9 client would send it
				c.writePacket(new PacketWriter(0x0E).writeByte(windowId).writeShort(11).writeByte(0)
						.writeShort(7).writeByte(0).writeShort(-1).toByteArray());
				// and a command
				c.writePacket(new PacketWriter(0x01).writeString("/bwp debug").toByteArray());
			}
			if (chat.stream().anyMatch(m -> m.contains("Status:"))) {
				commandReply = true;
			}
			if (commandReply && sounds.size() >= 2 && actionBars.size() > 1 && !footers.isEmpty()) {
				break;
			}
		}
		// in-game account commands: list, then switch (which disconnects with a message)
		c.writePacket(new PacketWriter(0x01).writeString("/bwp account").toByteArray());
		c.writePacket(new PacketWriter(0x01).writeString("/bwp account Bob").toByteArray());
		String kick = null;
		final long end2 = System.currentTimeMillis() + 4000;
		while (System.currentTimeMillis() < end2 && kick == null) {
			try {
				final PacketReader kr = new PacketReader(c.readPacket());
				final int kid = kr.readVarInt();
				if (kid == 0x02) {
					final String m = Text.stripColorCodes(Text.flatten(kr.readString()));
					if (kr.readByte() != 2) {
						chat.add(m);
					}
				} else if (kid == 0x40) {
					kick = Text.flatten(kr.readString());
				}
			} catch (final java.net.SocketTimeoutException e) {
				// keep waiting
			} catch (final java.io.IOException e) {
				break;
			}
		}
		check("/bwp account lists saved accounts",
				chat.stream().anyMatch(m -> m.contains("Saved accounts")) && chat.stream()
						.anyMatch(m -> m.contains("2. Bob")));
		check("/bwp account Bob switches and disconnects with a message",
				kick != null && (kick.contains("Switched to Bob")
						|| kick.contains("Account changed")));
		check("keep-alive forwarded", sawKeepAlive);
		check("armor alert: enemy bought diamond armor, 5m away, in chat",
				chat.stream().anyMatch(m -> m.contains("R Enemy") && m.contains("bought Diamond Armor")
						&& m.contains("(5m)")));
		check("item alert: enemy diamond sword",
				chat.stream().anyMatch(m -> m.contains("has Diamond Sword") && m.contains("(5m)")));
		check("upgrade alert: Red team Sharpened Swords",
				chat.stream().anyMatch(m -> m.contains("Red Team purchased Sharpened Swords")));
		check("teammate equipment is ignored",
				chat.stream().noneMatch(m -> m.contains("Mate")));
		check("ping sounds injected", sounds.size() >= 2 && sounds.get(0).equals("random.orb"));
		// the client renders at most 15 sidebar lines: 14 from the server + our injected ones
		check("sidebar never exceeds 15 lines (server 14 + injected " + (board.size() - 14) + ")",
				board.size() <= 15 && board.size() > 14);
		check("sidebar: both timers packed into the one free line",
				board.keySet().stream().map(Text::stripColorCodes)
						.anyMatch(m -> m.startsWith("E(") && m.contains("D(")));
		check("overflow (upgrades) went to the action bar, not dropped",
				actionBars.stream().anyMatch(m -> m.contains("Trap") && m.contains("Sharp")));
		check("tab list footer keeps Hypixel text and appends full HUD", footers.stream().anyMatch(
				f -> f.contains("STORE.HYPIXEL.NET") && f.contains("Emeralds (")
						&& f.contains("Diamonds (") && f.contains("Trap: ")));
		check("/bwp command answered locally", commandReply);

		// what the server saw
		final long cmdForwarded = serverReceived.stream().filter(p -> p[0] == 0x01).count();
		check("/bwp was NOT forwarded to the server", cmdForwarded == 0);
		byte[] click = null;
		for (final byte[] p : serverReceived) {
			if (p[0] == 0x0E) {
				click = p;
			}
		}
		check("server received a window click", click != null);
		if (click != null) {
			final PacketReader r = new PacketReader(click);
			r.readVarInt();
			r.readUnsignedByte();
			final int slot = r.readShort();
			final int button = r.readByte();
			final int action = r.readShort();
			final int mode = r.readByte();
			check("quick shop: slot kept", slot == 11);
			check("quick shop: left click became button 2 / mode 3", button == 2 && mode == 3);
			check("quick shop: transaction number kept", action == 7);
		}
		check("quick shop: client cursor cleared (set_slot -1:-1)", setSlots.contains("-1:-1"));
		check("quick shop: clicked slot restored", setSlots.contains("5:11"));
		check("client position reached the proxy state (distance used 5m)",
				serverReceived.stream().anyMatch(p -> p[0] == 0x04));

		c.close();
		proxy.stop();

		silentAlerts();

		fake.close();
		System.out.println(failures == 0 ? "ALL END-TO-END CHECKS PASSED"
				: failures + " END-TO-END CHECK(S) FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	/**
	 * alerts.sound=false must mute every ping sound while the chat alerts keep coming.
	 */
	private static void silentAlerts() throws Exception {
		final ServerSocket fake = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
		final List<byte[]> serverReceived = new CopyOnWriteArrayList<>();
		final Thread serverThread = new Thread(() -> runFakeServer(fake, serverReceived));
		serverThread.setDaemon(true);
		serverThread.start();

		final Path cfgFile = Files.createTempFile("bwproxy-silent", ".properties");
		final Config cfg = new Config(cfgFile);
		cfg.set("listen.port", "0");
		cfg.set("upstream.host", "127.0.0.1");
		cfg.set("upstream.port", String.valueOf(fake.getLocalPort()));
		cfg.set("hud.refresh_ms", "250");
		cfg.set("hud.output", "actionbar");
		cfg.set("alerts.sound", "false");
		final MicrosoftAuth.Profile offline = new MicrosoftAuth.Profile(null,
				"00000000000000000000000000000000", "Tester");
		final ProxyServer proxy = new ProxyServer(cfg, name -> offline, true, new FakeAccounts());
		final int port = proxy.start();
		final Thread serve = new Thread(proxy::serve);
		serve.setDaemon(true);
		serve.start();

		final Socket s = new Socket("127.0.0.1", port);
		final McConnection c = new McConnection(s);
		c.writePacket(new PacketWriter(0).writeVarInt(47).writeString("localhost").writeShort(port)
				.writeVarInt(2).toByteArray());
		c.writePacket(new PacketWriter(0).writeString("Whatever").toByteArray());
		// read the Login Success fully (uuid + name) before continuing
		final PacketReader ls = new PacketReader(c.readPacket());
		ls.readVarInt();
		ls.readString();
		ls.readString();
		c.writePacket(new PacketWriter(0x04).writeDouble(0).writeDouble(64).writeDouble(0)
				.writeBoolean(true).toByteArray());
		final List<String> chat = new ArrayList<>();
		final List<String> sounds = new ArrayList<>();
		final long end = System.currentTimeMillis() + 8000;
		s.setSoTimeout(500);
		while (System.currentTimeMillis() < end
				&& !chat.stream().anyMatch(m -> m.contains("purchased"))) {
			final byte[] pkt;
			try {
				pkt = c.readPacket();
			} catch (final java.net.SocketTimeoutException e) {
				continue;
			}
			final PacketReader r = new PacketReader(pkt);
			final int id = r.readVarInt();
			if (id == 0x02) {
				final String msg = Text.stripColorCodes(Text.flatten(r.readString()));
				if (r.readByte() != 2) {
					chat.add(msg);
				}
			} else if (id == 0x29) {
				sounds.add(r.readString());
			}
		}
		check("silent alerts: chat alerts still arrive",
				chat.stream().anyMatch(m -> m.contains("bought Diamond Armor"))
						&& chat.stream().anyMatch(m -> m.contains("has Diamond Sword")));
		check("silent alerts: no ping sound packets at all (" + sounds.size() + " seen)",
				sounds.isEmpty());
		c.close();
		proxy.stop();
	}

	/** Stands in for the real account store. */
	private static final class FakeAccounts implements bwproxy.auth.AccountControl {
		@Override
		public List<String> describe() {
			final List<String> l = new ArrayList<>();
			l.add("§a* 1. Tester");
			l.add("§7  2. Bob");
			return l;
		}

		@Override
		public String selectedName() {
			return "Tester";
		}

		@Override
		public void switchTo(final String name,
				final java.util.function.BiConsumer<Boolean, String> done) {
			done.accept(true, "Switched to " + name);
		}

		@Override
		public void addNew(final java.util.function.Consumer<String> chat,
				final java.util.function.BiConsumer<Boolean, String> done) {
			chat.accept("Open: https://example.invalid");
			done.accept(true, "Added Someone");
		}

		@Override
		public String remove(final String name) {
			return "Removed " + name;
		}
	}

	// ---- fake Hypixel ----

	private static void runFakeServer(final ServerSocket ss, final List<byte[]> received) {
		try (Socket sock = ss.accept()) {
			final McConnection c = new McConnection(sock);
			c.readPacket(); // handshake
			c.readPacket(); // login start
			c.writePacket(new PacketWriter(0x03).writeVarInt(64).toByteArray()); // set compression
			c.setCompressionThreshold(64);
			c.writePacket(new PacketWriter(0x02).writeString("11111111-2222-3333-4444-555555555555")
					.writeString("Tester").toByteArray());

			// Join Game
			c.writePacket(new PacketWriter(0x01).writeInt(100).writeByte(0).writeByte(0).writeByte(2)
					.writeByte(16).writeString("default").writeBoolean(false).toByteArray());
			// Tab list entries
			playerInfo(c, ENEMY, "Enemy");
			playerInfo(c, MATE, "Mate");
			// Teams: self is Blue, Enemy is Red, Mate is Blue
			team(c, "blue", "§9B §9", "Tester", "Mate");
			team(c, "red", "§cR §c", "Enemy");
			// Sidebar: BED WARS in-game layout (index 1 date, 3 event, 5 red team, 6 blue team)
			c.writePacket(new PacketWriter(0x3B).writeString("sb").writeByte(0)
					.writeString("§e§lBED WARS").writeString("integer").toByteArray());
			c.writePacket(new PacketWriter(0x3D).writeByte(1).writeString("sb").toByteArray());
			// A crowded real-looking sidebar: 14 lines + title, only one free slot of 15.
			final String[] lines = {"§710/10/26 §8m12A", "", "Diamond II in §a05:30",
					"", "§cR §cRed: §a\u2714", "§9B §9Blue: §a\u2714", "§aG §aGreen: §a\u2714",
					"§eY §eYellow: §a\u2714", "§bA §bAqua: §a\u2714", "§fW §fWhite: §a\u2714",
					"§dP §dPink: §a\u2714", "§8S §8Gray: §a\u2714", "", "§ewww.hypixel.net"};
			for (int i = 0; i < lines.length; i++) {
				final String entry = "§" + Integer.toHexString(i) + "§r";
				// each line lives in its own team: prefix carries the text
				team(c, "line" + i, lines[i], entry);
				c.writePacket(new PacketWriter(0x3C).writeString(entry).writeByte(0).writeString("sb")
						.writeVarInt(lines.length - i).toByteArray());
			}
			// tab header/footer
			c.writePacket(new PacketWriter(0x47).writeString("{\"text\":\"\"}")
					.writeString("{\"text\":\"§cSTORE.HYPIXEL.NET\"}").toByteArray());
			// spawn players: Enemy 5 blocks away on x
			spawn(c, 201, ENEMY, 5.0, 64.0, 0.0);
			spawn(c, 202, MATE, 3.0, 64.0, 0.0);
			c.writePacket(keepAlive());
			Thread.sleep(300);
			// Enemy: diamond leggings (id 312), diamond sword (276), sharpened sword (iron 267 w/ ench)
			equipment(c, 201, 2, ItemBytes.item(312, null, 0));
			equipment(c, 201, 0, ItemBytes.item(276, null, 0));
			equipment(c, 201, 0, ItemBytes.item(267, null, 1));
			equipment(c, 202, 2, ItemBytes.item(312, null, 0)); // teammate: ignored
			// shop window with a known item in slot 11
			c.writePacket(new PacketWriter(0x2D).writeByte(5).writeString("minecraft:chest")
					.writeString("{\"text\":\"Quick Buy\"}").writeByte(54).toByteArray());
			final PacketWriter items = new PacketWriter(0x30).writeByte(5).writeShort(54);
			for (int i = 0; i < 54; i++) {
				items.writeBytes(i == 11 ? ItemBytes.item(Items.WOOL, "§fWool", 0) : ItemBytes.empty());
			}
			c.writePacket(items.toByteArray());

			final Socket raw = sock;
			raw.setSoTimeout(200);
			final long end = System.currentTimeMillis() + 7000;
			while (System.currentTimeMillis() < end) {
				try {
					final byte[] p = c.readPacket();
					received.add(p);
				} catch (final java.net.SocketTimeoutException e) {
					c.writePacket(keepAlive());
				}
			}
		} catch (final Exception e) {
			System.out.println("fake server error: " + e);
		}
	}

	private static final class Items {
		static final int WOOL = 35;
	}

	private static byte[] keepAlive() {
		return new PacketWriter(0x00).writeVarInt(1).toByteArray();
	}

	private static void playerInfo(final McConnection c, final UUID uuid, final String name)
			throws Exception {
		c.writePacket(new PacketWriter(0x38).writeVarInt(0).writeVarInt(1).writeLong(
				uuid.getMostSignificantBits()).writeLong(uuid.getLeastSignificantBits())
				.writeString(name).writeVarInt(0).writeVarInt(0).writeVarInt(50).writeBoolean(false)
				.toByteArray());
	}

	private static void team(final McConnection c, final String name, final String prefix,
			final String... players) throws Exception {
		final PacketWriter w = new PacketWriter(0x3E).writeString(name).writeByte(0).writeString(name)
				.writeString(prefix).writeString("").writeByte(0).writeString("always").writeByte(-1)
				.writeVarInt(players.length);
		for (final String p : players) {
			w.writeString(p);
		}
		c.writePacket(w.toByteArray());
	}

	private static void spawn(final McConnection c, final int id, final UUID uuid, final double x,
			final double y, final double z) throws Exception {
		c.writePacket(new PacketWriter(0x0C).writeVarInt(id).writeLong(uuid.getMostSignificantBits())
				.writeLong(uuid.getLeastSignificantBits()).writeInt((int) (x * 32))
				.writeInt((int) (y * 32)).writeInt((int) (z * 32)).writeByte(0).writeByte(0)
				.writeShort(0).writeByte(127).toByteArray());
	}

	private static void equipment(final McConnection c, final int entity, final int slot,
			final byte[] item) throws Exception {
		c.writePacket(new PacketWriter(0x04).writeVarInt(entity).writeShort(slot).writeBytes(item)
				.toByteArray());
	}

	/** Slot/NBT byte builders for the fake server. */
	private static final class ItemBytes {
		static byte[] empty() {
			return new byte[]{(byte) 0xFF, (byte) 0xFF};
		}

		static byte[] item(final int id, final String name, final int enchants) {
			final ByteArrayOutputStream o = new ByteArrayOutputStream();
			o.write((id >> 8) & 0xFF);
			o.write(id & 0xFF);
			o.write(1); // count
			o.write(0);
			o.write(0); // damage
			if (name == null && enchants == 0) {
				o.write(0); // no NBT
				return o.toByteArray();
			}
			o.write(10); // root compound
			o.write(0);
			o.write(0); // empty root name
			if (name != null) {
				o.write(10);
				writeName(o, "display");
				o.write(8);
				writeName(o, "Name");
				writeName(o, name);
				o.write(0);
			}
			if (enchants > 0) {
				o.write(9);
				writeName(o, "ench");
				o.write(10);
				o.write(0);
				o.write(0);
				o.write(0);
				o.write(enchants);
				for (int i = 0; i < enchants; i++) {
					o.write(2);
					writeName(o, "id");
					o.write(0);
					o.write(16);
					o.write(2);
					writeName(o, "lvl");
					o.write(0);
					o.write(1);
					o.write(0);
				}
			}
			o.write(0);
			return o.toByteArray();
		}

		private static void writeName(final ByteArrayOutputStream o, final String s) {
			final byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			o.write((b.length >> 8) & 0xFF);
			o.write(b.length & 0xFF);
			o.write(b, 0, b.length);
		}
	}
}
