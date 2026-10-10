package bwproxy;

import bwproxy.auth.AccountControl;
import bwproxy.auth.AccountManager;
import bwproxy.feature.ArmorAlerts;
import bwproxy.feature.Feature;
import bwproxy.feature.FeatureContext;
import bwproxy.feature.ItemAlerts;
import bwproxy.feature.QuickShop;
import bwproxy.feature.TimersHud;
import bwproxy.feature.UpgradeAlerts;
import bwproxy.feature.UpgradesHud;
import bwproxy.game.BedWars;
import bwproxy.game.GameState;
import bwproxy.game.ItemStack;
import bwproxy.net.McConnection;
import bwproxy.net.PacketReader;
import bwproxy.net.PacketWriter;
import bwproxy.util.Json;
import bwproxy.util.Text;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One play-state relay: forwards packets in both directions, rebuilds the bits of world state the
 * features need, and injects chat, sounds and HUD lines towards the client.
 */
public final class ProxySession implements FeatureContext {
	// clientbound
	private static final int S_LOGIN = 0x01;
	private static final int S_CHAT = 0x02;
	private static final int S_EQUIPMENT = 0x04;
	private static final int S_RESPAWN = 0x07;
	private static final int S_POSITION = 0x08;
	private static final int S_SPAWN_PLAYER = 0x0C;
	private static final int S_DESTROY = 0x13;
	private static final int S_REL_MOVE = 0x15;
	private static final int S_MOVE_LOOK = 0x17;
	private static final int S_TELEPORT = 0x18;
	private static final int S_SOUND = 0x29;
	private static final int S_OPEN_WINDOW = 0x2D;
	private static final int S_CLOSE_WINDOW = 0x2E;
	private static final int S_SET_SLOT = 0x2F;
	private static final int S_WINDOW_ITEMS = 0x30;
	private static final int S_PLAYER_INFO = 0x38;
	private static final int S_OBJECTIVE = 0x3B;
	private static final int S_SCORE = 0x3C;
	private static final int S_DISPLAY = 0x3D;
	private static final int S_TEAM = 0x3E;
	private static final int S_TAB_HEADER = 0x47;
	// serverbound
	private static final int C_CHAT = 0x01;
	private static final int C_POSITION = 0x04;
	private static final int C_POSITION_LOOK = 0x06;
	private static final int C_CLOSE_WINDOW = 0x0D;
	private static final int C_WINDOW_CLICK = 0x0E;

	private final McConnection client;
	private final McConnection upstream;
	private final Config config;
	private final boolean debug;
	private final AccountControl accounts;
	private final GameState state = new GameState();
	private final List<Feature> features = new ArrayList<>();
	private final QuickShop quickShop;
	private final Object lock = new Object();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		final Thread t = new Thread(r, "bwproxy-hud");
		t.setDaemon(true);
		return t;
	});
	// HUD bookkeeping (guarded by lock)
	private List<String> injectedSidebar = Collections.emptyList();
	private String injectedObjective;
	/** Last sidebar objective we successfully wrote to; survives brief Hypixel display flaps. */
	private String lastSidebarObjective;
	private String lastTabKey = "";
	private long lastActionBar;

	public ProxySession(final McConnection client, final McConnection upstream, final Config config,
			final String playerName, final boolean debug,
			final AccountControl accounts) {
		this.client = client;
		this.upstream = upstream;
		this.config = config;
		this.debug = debug;
		this.accounts = accounts;
		state.selfName = playerName;
		features.add(new ArmorAlerts(this));
		features.add(new ItemAlerts(this));
		features.add(new UpgradeAlerts(this));
		features.add(new TimersHud(this));
		features.add(new UpgradesHud(this));
		quickShop = new QuickShop(this);
		features.add(quickShop);
	}

	// ---- FeatureContext ----

	@Override
	public Config config() {
		return config;
	}

	@Override
	public GameState state() {
		return state;
	}

	@Override
	public void chat(final String message) {
		sendChatJson(Json.quote(config.get("alerts.prefix") + message), 0);
	}

	@Override
	public void ping() {
		if (config.bool("alerts.sound")) {
			sound("random.orb", 1.0f, 1.0f);
		}
	}

	// ---- lifecycle ----

	/** Blocks until either side disconnects. */
	public void run() {
		final int period = Math.max(250, config.integer("hud.refresh_ms"));
		scheduler.scheduleAtFixedRate(this::tick, period, period, TimeUnit.MILLISECONDS);
		final Thread toClient = new Thread(this::pumpClientbound, "bwproxy-down");
		toClient.setDaemon(true);
		toClient.start();
		pumpServerbound();
		close();
		try {
			toClient.join(2000);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public void close() {
		scheduler.shutdownNow();
		client.close();
		upstream.close();
	}

	private void pumpClientbound() {
		try {
			while (true) {
				final byte[] pkt = upstream.readPacket();
				final PacketReader head = new PacketReader(pkt);
				final int id = head.readVarInt();
				if (id == 0x46) { // Set Compression: ours towards the client is always off
					upstream.setCompressionThreshold(head.readVarInt());
					continue;
				}
				final byte[] out;
				synchronized (lock) {
					out = onClientbound(id, pkt);
				}
				if (out != null) {
					client.writePacket(out);
				}
			}
		} catch (final IOException e) {
			if (debug && !client.isClosed()) {
				System.out.println("[debug] upstream closed: " + e);
			}
		} finally {
			close();
		}
	}

	private void pumpServerbound() {
		try {
			while (true) {
				final byte[] pkt = client.readPacket();
				final int id = new PacketReader(pkt).readVarInt();
				final byte[] out;
				synchronized (lock) {
					out = onServerbound(id, pkt);
				}
				if (out != null) {
					upstream.writePacket(out);
				}
			}
		} catch (final IOException e) {
			if (debug && !upstream.isClosed()) {
				System.out.println("[debug] client closed: " + e);
			}
		}
	}

	// ---- clientbound ----

	private byte[] onClientbound(final int id, final byte[] pkt) {
		try {
			final PacketReader r = new PacketReader(pkt);
			r.readVarInt();
			switch (id) {
				case S_LOGIN : {
					state.resetAll();
					state.selfEntityId = r.readInt();
					injectedSidebar = Collections.emptyList();
					injectedObjective = null;
					lastTabKey = "";
					worldLoad();
					break;
				}
				case S_RESPAWN :
					state.resetWorld();
					worldLoad();
					break;
				case S_POSITION :
					state.onServerPosition(r);
					break;
				case S_SPAWN_PLAYER :
					state.onSpawnPlayer(r);
					break;
				case S_DESTROY :
					state.onDestroy(r);
					break;
				case S_REL_MOVE :
				case S_MOVE_LOOK :
					state.onRelMove(r);
					break;
				case S_TELEPORT :
					state.onTeleport(r);
					break;
				case S_PLAYER_INFO :
					state.onPlayerInfo(r);
					break;
				case S_OBJECTIVE : {
					state.onObjective(r);
					if (injectedObjective != null && !injectedObjective.equals(state.sidebarObjectiveName())) {
						injectedSidebar = Collections.emptyList();
						injectedObjective = null;
					}
					break;
				}
				case S_SCORE :
					state.onScore(r);
					break;
				case S_DISPLAY : {
					state.onDisplayObjective(r);
					if (injectedObjective != null && !injectedObjective.equals(state.sidebarObjectiveName())) {
						injectedSidebar = Collections.emptyList();
						injectedObjective = null;
					}
					break;
				}
				case S_TEAM :
					state.onTeam(r);
					break;
				case S_EQUIPMENT :
					onEquipment(r);
					break;
				case S_CHAT : {
					final String json = r.readString();
					final int position = r.readByte();
					if (position != 2) {
						final String plain = Text.stripColorCodes(Text.flatten(json));
						for (final Feature f : features) {
							if (f.enabled()) {
								f.onChat(plain);
							}
						}
					}
					break;
				}
				case S_OPEN_WINDOW : {
					final int windowId = r.readUnsignedByte();
					r.readString();
					final String title = r.readString();
					state.onOpenWindow(windowId, title, r.readUnsignedByte());
					break;
				}
				case S_CLOSE_WINDOW :
					state.onCloseWindow(r.readUnsignedByte());
					break;
				case S_SET_SLOT :
					state.onSetSlot(r);
					break;
				case S_WINDOW_ITEMS :
					state.onWindowItems(r);
					break;
				case S_TAB_HEADER : {
					state.tabHeaderJson = r.readString();
					state.tabFooterJson = r.readString();
					if (tabEnabled()) {
						final List<String> lines = hudLines();
						if (!lines.isEmpty()) {
							lastTabKey = String.join("\n", lines);
							return tabPacket(state.tabHeaderJson, mergeFooter(state.tabFooterJson, lines));
						}
					}
					lastTabKey = "";
					break;
				}
				default :
					break;
			}
		} catch (final RuntimeException e) {
			if (debug) {
				System.out.println("[debug] could not parse clientbound 0x"
						+ Integer.toHexString(id) + ": " + e);
			}
		}
		return pkt;
	}

	private void onEquipment(final PacketReader r) {
		final int entityId = r.readVarInt();
		final int slot = r.readShort();
		final ItemStack stack = ItemStack.read(r);
		if (stack == null || entityId == state.selfEntityId) {
			return;
		}
		final GameState.Entity entity = state.entity(entityId);
		final String name = state.nameOf(entity);
		if (entity == null || name == null || name.equals(state.selfName)) {
			return;
		}
		if (!BedWars.inGame(state) || BedWars.isTeammate(state, name)) {
			return;
		}
		for (final Feature f : features) {
			if (f.enabled()) {
				f.onEnemyEquipment(entity, name, slot, stack);
			}
		}
	}

	private void worldLoad() {
		for (final Feature f : features) {
			f.onWorldLoad();
		}
	}

	// ---- serverbound ----

	private byte[] onServerbound(final int id, final byte[] pkt) {
		try {
			final PacketReader r = new PacketReader(pkt);
			r.readVarInt();
			switch (id) {
				case C_CHAT : {
					final String msg = r.readString();
					final String lower = msg.toLowerCase(Locale.ROOT);
					if (lower.equals("/bwp") || lower.startsWith("/bwp ")) {
						command(msg.length() > 5 ? msg.substring(5).trim() : "");
						return null;
					}
					break;
				}
				case C_POSITION :
				case C_POSITION_LOOK :
					state.onClientPosition(r);
					break;
				case C_CLOSE_WINDOW :
					state.onCloseWindow(r.readUnsignedByte());
					break;
				case C_WINDOW_CLICK : {
					if (quickShop.enabled()) {
						final QuickShop.Rewrite rw = quickShop.rewrite(pkt);
						if (rw != null) {
							for (final byte[] fix : rw.clientFixes) {
								client.writePacket(fix);
							}
							return rw.clickPacket;
						}
					}
					break;
				}
				default :
					break;
			}
		} catch (final IOException e) {
			// client write failed; the pump loop will notice on its next read
			return pkt;
		} catch (final RuntimeException e) {
			if (debug) {
				System.out.println("[debug] could not parse serverbound 0x" + Integer.toHexString(id)
						+ ": " + e);
			}
		}
		return pkt;
	}

	private void command(final String args) {
		new BwpCommands(commandCtx(), msg -> chat(msg), false).run(args);
		if (args.toLowerCase(Locale.ROOT).startsWith("hud")
				|| args.equalsIgnoreCase("clear")) {
			clearHud();
		}
	}

	/** In game: replies go to chat; a sign-in/switch kicks the player to reconnect. */
	private BwpCommands.Ctx commandCtx() {
		return new BwpCommands.Ctx(config, features, accounts, state, this::kickReconnect);
	}

	/** Kicks the client only when it is actually connected through the proxy. */
	private void kickReconnect() {
		kick("Account changed. Reconnect to play as the new one.");
	}

	// accountCommand was replaced by BwpCommands, shared with the console windows


	/** Sends a disconnect screen with {@code message} and closes the session. */
	private void kick(final String message) {
		try {
			client.writePacket(new PacketWriter(0x40)
					.writeString("{\"text\":" + Json.quote(message) + "}").toByteArray());
		} catch (final IOException ignored) {
			// already gone
		}
		close();
	}

	// ---- HUD ----

	private void tick() {
		try {
			synchronized (lock) {
				for (final Feature f : features) {
					if (f.enabled()) {
						f.onSecond();
					}
				}
				refreshHud();
			}
		} catch (final IOException | RuntimeException e) {
			if (debug) {
				System.out.println("[debug] hud tick failed: " + e);
			}
		}
	}

	private boolean modeOn(final String mode) {
		for (final String m : config.get("hud.output").split(",")) {
			if (m.trim().equalsIgnoreCase(mode)) {
				return true;
			}
		}
		return false;
	}

	private boolean tabEnabled() {
		return modeOn("tablist") || modeOn("tab");
	}

	private List<String> hudLines() {
		final List<String> lines = new ArrayList<>();
		for (final Feature f : features) {
			if (f.enabled()) {
				lines.addAll(f.hudLines());
			}
		}
		return lines;
	}

	/** The 1.8 client draws at most 15 sidebar lines, so only the free ones can be used. */
	private static final int SIDEBAR_MAX_LINES = 15;
	private static final int ENTRY_PACK_LIMIT = 34;

	private List<String> compactLines() {
		final List<String> segments = new ArrayList<>();
		for (final Feature f : features) {
			if (f.enabled()) {
				segments.addAll(f.hudCompact());
			}
		}
		final List<String> packed = new ArrayList<>();
		final StringBuilder cur = new StringBuilder();
		for (final String seg : segments) {
			if (cur.length() > 0 && cur.length() + 7 + seg.length() > ENTRY_PACK_LIMIT) {
				packed.add(cur.toString());
				cur.setLength(0);
			}
			if (cur.length() > 0) {
				cur.append(" §8|§r ");
			}
			cur.append(seg);
		}
		if (cur.length() > 0) {
			packed.add(cur.toString());
		}
		return packed;
	}

	private void refreshHud() throws IOException {
		final List<String> lines = hudLines();
		List<String> sidebarLines = Collections.emptyList();
		List<String> overflow = Collections.emptyList();
		if (modeOn("sidebar")) {
			final List<String> packed = compactLines();
			final int free = Math.max(0, SIDEBAR_MAX_LINES - state.sidebarEntryCount());
			final int fit = Math.min(free, packed.size());
			sidebarLines = packed.subList(0, fit);
			overflow = packed.subList(fit, packed.size());
		}
		syncSidebar(sidebarLines);
		final String tabKey = tabEnabled() ? String.join("\n", lines) : "";
		if (!tabKey.equals(lastTabKey)) {
			lastTabKey = tabKey;
			final String header = state.tabHeaderJson != null ? state.tabHeaderJson : "{\"text\":\"\"}";
			final String footer = state.tabFooterJson != null ? state.tabFooterJson : "{\"text\":\"\"}";
			client.writePacket(tabPacket(header,
					tabKey.isEmpty() ? footer : mergeFooter(footer, lines)));
		}
		// Anything the sidebar had no room for goes to the action bar instead of being dropped.
		final List<String> bar = modeOn("actionbar") ? lines : overflow;
		if (!bar.isEmpty()) {
			final long now = System.currentTimeMillis();
			if (now - lastActionBar >= 1000) {
				lastActionBar = now;
				sendChatJson(Json.quote(String.join(" §8| §r", bar)), 2);
			}
		}
	}

	/** Puts the lines under Hypixel's sidebar as extra (low, negative) scores. */
	private void syncSidebar(final List<String> lines) throws IOException {
		// Stick to the last-known sidebar objective. Hypixel briefly removes the
		// display objective or its score rows while worlds/scoreboards swap, and
		// treating that gap as "no sidebar" would move our lines onto the action
		// bar for a second before they snap back.
		final String objective = state.sidebarObjectiveName() != null
				? state.sidebarObjectiveName() : lastSidebarObjective;
		if (state.sidebarObjectiveName() != null) {
			lastSidebarObjective = objective;
		}
		final List<String> wanted = new ArrayList<>();
		if (objective != null) {
			for (int i = 0; i < lines.size(); i++) {
				wanted.add(entry(lines.get(i), i));
			}
		}
		if (wanted.equals(injectedSidebar)) {
			return;
		}
		if (injectedObjective != null) {
			for (final String old : injectedSidebar) {
				client.writePacket(new PacketWriter(S_SCORE).writeString(old).writeByte(1)
						.writeString(injectedObjective).toByteArray());
			}
		}
		if (wanted.isEmpty()) {
			// Reset the sticky objective on a real clear (world change) so we don't
			// keep targeting a scoreboard name after it is gone for good.
			lastSidebarObjective = null;
			return;
		}
		final int base = Math.min(0, state.lowestSidebarScore()) - 1;
		for (int i = 0; i < wanted.size(); i++) {
			client.writePacket(new PacketWriter(S_SCORE).writeString(wanted.get(i)).writeByte(0)
					.writeString(objective).writeVarInt(base - i).toByteArray());
		}
		injectedSidebar = wanted;
		injectedObjective = wanted.isEmpty() ? null : objective;
		// When we just wrote our own rows, this objective is definitely alive:
		// trust it even if the server's display packets flap right after.
		lastSidebarObjective = objective;
	}

	private static String entry(final String line, final int index) {
		final StringBuilder suffix = new StringBuilder();
		for (int i = 0; i <= index; i++) {
			suffix.append("§r");
		}
		String text = line;
		final int max = 40 - suffix.length();
		if (text.length() > max) {
			text = text.substring(0, max);
			if (text.endsWith("§")) {
				text = text.substring(0, text.length() - 1);
			}
		}
		return text + suffix;
	}

	private void clearHud() {
		try {
			syncSidebar(Collections.<String>emptyList());
			lastTabKey = "";
			refreshHud();
		} catch (final IOException | RuntimeException ignored) {
			// connection is going away
		}
	}

	private static String mergeFooter(final String originalJson, final List<String> lines) {
		final String hud = Json.quote("\n" + String.join("\n", lines));
		return "{\"text\":\"\",\"extra\":[" + originalJson + ",{\"text\":" + hud + "}]}";
	}

	private static byte[] tabPacket(final String header, final String footer) {
		return new PacketWriter(S_TAB_HEADER).writeString(header).writeString(footer).toByteArray();
	}

	// ---- output ----

	private void sendChatJson(final String quotedText, final int position) {
		try {
			client.writePacket(new PacketWriter(S_CHAT).writeString("{\"text\":" + quotedText + "}")
					.writeByte(position).toByteArray());
		} catch (final IOException e) {
			// client gone
		}
	}

	private void sound(final String name, final float volume, final float pitch) {
		try {
			final int p = Math.max(0, Math.min(255, Math.round(pitch * 63f)));
			client.writePacket(new PacketWriter(S_SOUND).writeString(name)
					.writeInt((int) (state.selfX * 8)).writeInt((int) (state.selfY * 8))
					.writeInt((int) (state.selfZ * 8)).writeFloat(volume).writeByte(p).toByteArray());
		} catch (final IOException e) {
			// client gone
		}
	}

}
