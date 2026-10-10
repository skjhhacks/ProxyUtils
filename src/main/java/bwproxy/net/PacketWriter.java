package bwproxy.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Builds a packet body (packet id included). */
public final class PacketWriter {
	private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);

	public PacketWriter(final int packetId) {
		writeVarInt(packetId);
	}

	public PacketWriter writeByte(final int v) {
		out.write(v);
		return this;
	}

	public PacketWriter writeBoolean(final boolean v) {
		out.write(v ? 1 : 0);
		return this;
	}

	public PacketWriter writeShort(final int v) {
		out.write((v >>> 8) & 0xFF);
		out.write(v & 0xFF);
		return this;
	}

	public PacketWriter writeInt(final int v) {
		out.write((v >>> 24) & 0xFF);
		out.write((v >>> 16) & 0xFF);
		out.write((v >>> 8) & 0xFF);
		out.write(v & 0xFF);
		return this;
	}

	public PacketWriter writeLong(final long v) {
		writeInt((int) (v >>> 32));
		writeInt((int) v);
		return this;
	}

	public PacketWriter writeFloat(final float v) {
		return writeInt(Float.floatToIntBits(v));
	}

	public PacketWriter writeDouble(final double v) {
		return writeLong(Double.doubleToLongBits(v));
	}

	public PacketWriter writeVarInt(final int value) {
		int v = value;
		while ((v & ~0x7F) != 0) {
			out.write((v & 0x7F) | 0x80);
			v >>>= 7;
		}
		out.write(v);
		return this;
	}

	public PacketWriter writeString(final String s) {
		final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		writeVarInt(bytes.length);
		out.write(bytes, 0, bytes.length);
		return this;
	}

	public PacketWriter writeBytes(final byte[] bytes) {
		out.write(bytes, 0, bytes.length);
		return this;
	}

	public byte[] toByteArray() {
		return out.toByteArray();
	}
}
