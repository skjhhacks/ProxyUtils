package bwproxy.net;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Cursor over a decompressed packet body (packet id included). */
public final class PacketReader {
	private final byte[] b;
	private int pos;

	public PacketReader(final byte[] data) {
		this.b = data;
	}

	public int position() {
		return pos;
	}

	public void position(final int p) {
		this.pos = p;
	}

	public int remaining() {
		return b.length - pos;
	}

	public byte[] data() {
		return b;
	}

	private void need(final int n) {
		if (pos + n > b.length) {
			throw new IndexOutOfBoundsException("Packet underflow: need " + n + " at " + pos + "/"
					+ b.length);
		}
	}

	public int readByte() {
		need(1);
		return b[pos++];
	}

	public int readUnsignedByte() {
		need(1);
		return b[pos++] & 0xFF;
	}

	public boolean readBoolean() {
		return readByte() != 0;
	}

	public int readShort() {
		need(2);
		final int v = (short) (((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF));
		pos += 2;
		return v;
	}

	public int readInt() {
		need(4);
		final int v = ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16)
				| ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
		pos += 4;
		return v;
	}

	public long readLong() {
		return ((long) readInt() << 32) | (readInt() & 0xFFFFFFFFL);
	}

	public float readFloat() {
		return Float.intBitsToFloat(readInt());
	}

	public double readDouble() {
		return Double.longBitsToDouble(readLong());
	}

	public int readVarInt() {
		int value = 0;
		int shift = 0;
		while (true) {
			final int c = readUnsignedByte();
			value |= (c & 0x7F) << shift;
			if ((c & 0x80) == 0) {
				return value;
			}
			shift += 7;
			if (shift > 35) {
				throw new IllegalStateException("VarInt too long");
			}
		}
	}

	public String readString() {
		final int len = readVarInt();
		if (len < 0) {
			throw new IllegalStateException("Negative string length");
		}
		need(len);
		final String s = new String(b, pos, len, StandardCharsets.UTF_8);
		pos += len;
		return s;
	}

	public byte[] readBytes(final int n) {
		need(n);
		final byte[] out = new byte[n];
		System.arraycopy(b, pos, out, 0, n);
		pos += n;
		return out;
	}

	public UUID readUuid() {
		return new UUID(readLong(), readLong());
	}

	public void skip(final int n) {
		need(n);
		pos += n;
	}
}
