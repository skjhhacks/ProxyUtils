package bwproxy.feature;

import bwproxy.game.BedWars;
import bwproxy.game.GameState;
import bwproxy.net.PacketReader;
import bwproxy.net.PacketWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Turns a left click in the Bed Wars item shop into a middle click (port of OpenUtils QuickShop).
 * One click in, one click out; nothing is automated.
 */
public final class QuickShop extends Feature {
	public static final int WINDOW_CLICK = 0x0E;
	public static final int SET_SLOT = 0x2F;
	private static final List<String> SHOP_TITLES = Arrays.asList("quick buy", "upgrades & traps",
			"blocks", "melee", "armor", "tools", "ranged", "potions", "utility", "rotating items");

	/** Result of looking at a client click. */
	public static final class Rewrite {
		public final byte[] clickPacket;
		/** Packets to send to the client to undo what its own click prediction did. */
		public final byte[][] clientFixes;

		Rewrite(final byte[] clickPacket, final byte[][] clientFixes) {
			this.clickPacket = clickPacket;
			this.clientFixes = clientFixes;
		}
	}

	public QuickShop(final FeatureContext ctx) {
		super(ctx);
	}

	@Override
	public String id() {
		return "quickshop";
	}

	@Override
	public String title() {
		return "Quick Shop";
	}

	/** @return null to leave the packet alone. */
	public Rewrite rewrite(final byte[] packet) {
		final GameState state = ctx.state();
		if (!BedWars.inGame(state)) {
			return null;
		}
		final PacketReader r = new PacketReader(packet);
		r.readVarInt();
		final int windowId = r.readUnsignedByte();
		final int slot = r.readShort();
		final int button = r.readByte();
		final int action = r.readShort();
		final int mode = r.readByte();
		// Plain left click only. A shift-click also moves the stack in the client's own inventory
		// view, which cannot be undone without mirroring the whole window.
		if (windowId == 0 || button != 0 || slot < 0 || mode != 0) {
			return null;
		}
		final GameState.Window w = state.window(windowId);
		if (w == null || slot >= w.slotCount || !isShop(w.title)) {
			return null;
		}
		final byte[] click = new PacketWriter(WINDOW_CLICK).writeByte(windowId).writeShort(slot)
				.writeByte(2).writeShort(action).writeByte(3).writeShort(-1).toByteArray();
		// The client already "picked up" the item locally. Empty its cursor and restore the slot, as
		// the server would for a cancelled click.
		final byte[] cursor = new PacketWriter(SET_SLOT).writeByte(-1).writeShort(-1).writeShort(-1)
				.toByteArray();
		final byte[] known = w.slots[slot];
		if (known == null) {
			return new Rewrite(click, new byte[][]{cursor});
		}
		final byte[] restore = new PacketWriter(SET_SLOT).writeByte(windowId).writeShort(slot)
				.writeBytes(known).toByteArray();
		return new Rewrite(click, new byte[][]{cursor, restore});
	}

	private static boolean isShop(final String title) {
		final String t = bwproxy.util.Text.stripColorCodes(title).toLowerCase(Locale.ROOT);
		for (final String s : SHOP_TITLES) {
			if (t.contains(s)) {
				return true;
			}
		}
		return false;
	}
}
