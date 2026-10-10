package bwproxy.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * One side of a Minecraft connection: VarInt framing, optional AES/CFB8 encryption, optional zlib
 * compression. Packets are exchanged as {@code packetId + payload} byte arrays.
 */
public final class McConnection implements Closeable {
	private static final int MAX_PACKET = 8 * 1024 * 1024;
	private final Socket socket;
	private final InputStream in;
	private final OutputStream out;
	private final CryptInputStream crypt;
	private final Object writeLock = new Object();
	private Cipher encrypt;
	private volatile int threshold = -1;

	public McConnection(final Socket socket) throws IOException {
		this.socket = socket;
		this.crypt = new CryptInputStream(socket.getInputStream());
		this.in = new BufferedInputStream(crypt, 64 * 1024);
		this.out = socket.getOutputStream();
		socket.setTcpNoDelay(true);
	}

	/** Raw (already de-framed-from-crypto) input, used to pipe status pings untouched. */
	public InputStream rawIn() {
		return in;
	}

	public OutputStream rawOut() {
		return out;
	}

	public Socket socket() {
		return socket;
	}

	/** Call only when nothing is in flight: the server sends nothing until the key is answered. */
	public void enableEncryption(final byte[] secret) throws IOException {
		try {
			final SecretKeySpec key = new SecretKeySpec(secret, "AES");
			final Cipher dec = Cipher.getInstance("AES/CFB8/NoPadding");
			dec.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(secret));
			final Cipher enc = Cipher.getInstance("AES/CFB8/NoPadding");
			enc.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(secret));
			synchronized (writeLock) {
				this.encrypt = enc;
			}
			crypt.cipher = dec;
		} catch (final java.security.GeneralSecurityException e) {
			throw new IOException("Could not enable encryption", e);
		}
	}

	public void setCompressionThreshold(final int threshold) {
		this.threshold = threshold;
	}

	public byte[] readPacket() throws IOException {
		final int length = readVarInt();
		if (length < 0 || length > MAX_PACKET) {
			throw new IOException("Bad packet length " + length);
		}
		final byte[] frame = readFully(length);
		if (threshold < 0) {
			return frame;
		}
		int i = 0;
		int dataLength = 0;
		int shift = 0;
		while (true) {
			final int c = frame[i++] & 0xFF;
			dataLength |= (c & 0x7F) << shift;
			if ((c & 0x80) == 0) {
				break;
			}
			shift += 7;
		}
		if (dataLength == 0) {
			final byte[] raw = new byte[frame.length - i];
			System.arraycopy(frame, i, raw, 0, raw.length);
			return raw;
		}
		if (dataLength > MAX_PACKET) {
			throw new IOException("Bad uncompressed length " + dataLength);
		}
		final Inflater inflater = new Inflater();
		try {
			inflater.setInput(frame, i, frame.length - i);
			final byte[] raw = new byte[dataLength];
			int n = 0;
			while (n < dataLength) {
				final int r = inflater.inflate(raw, n, dataLength - n);
				if (r == 0 && (inflater.finished() || inflater.needsInput())) {
					throw new IOException("Truncated compressed packet");
				}
				n += r;
			}
			return raw;
		} catch (final DataFormatException e) {
			throw new IOException("Bad compressed packet", e);
		} finally {
			inflater.end();
		}
	}

	public void writePacket(final byte[] body) throws IOException {
		final ByteArrayOutputStream frame = new ByteArrayOutputStream(body.length + 8);
		final int t = threshold;
		if (t < 0) {
			writeVarInt(frame, body.length);
			frame.write(body, 0, body.length);
		} else if (body.length < t) {
			writeVarInt(frame, body.length + 1);
			frame.write(0);
			frame.write(body, 0, body.length);
		} else {
			final Deflater deflater = new Deflater();
			final ByteArrayOutputStream z = new ByteArrayOutputStream(body.length / 2 + 16);
			try {
				deflater.setInput(body);
				deflater.finish();
				final byte[] buf = new byte[4096];
				while (!deflater.finished()) {
					final int n = deflater.deflate(buf);
					z.write(buf, 0, n);
				}
			} finally {
				deflater.end();
			}
			final ByteArrayOutputStream lenBytes = new ByteArrayOutputStream(5);
			writeVarInt(lenBytes, body.length);
			writeVarInt(frame, lenBytes.size() + z.size());
			frame.write(lenBytes.toByteArray(), 0, lenBytes.size());
			frame.write(z.toByteArray(), 0, z.size());
		}
		final byte[] bytes = frame.toByteArray();
		synchronized (writeLock) {
			if (encrypt != null) {
				final byte[] enc = encrypt.update(bytes);
				out.write(enc);
			} else {
				out.write(bytes);
			}
			out.flush();
		}
	}

	private int readVarInt() throws IOException {
		int value = 0;
		int shift = 0;
		while (true) {
			final int c = in.read();
			if (c < 0) {
				throw new EOFException();
			}
			value |= (c & 0x7F) << shift;
			if ((c & 0x80) == 0) {
				return value;
			}
			shift += 7;
			if (shift > 35) {
				throw new IOException("VarInt too long");
			}
		}
	}

	private byte[] readFully(final int n) throws IOException {
		final byte[] b = new byte[n];
		int off = 0;
		while (off < n) {
			final int r = in.read(b, off, n - off);
			if (r < 0) {
				throw new EOFException();
			}
			off += r;
		}
		return b;
	}

	private static void writeVarInt(final ByteArrayOutputStream o, final int value) {
		int v = value;
		while ((v & ~0x7F) != 0) {
			o.write((v & 0x7F) | 0x80);
			v >>>= 7;
		}
		o.write(v);
	}

	@Override
	public void close() {
		try {
			socket.close();
		} catch (final IOException ignored) {
			// already closed
		}
	}

	public boolean isClosed() {
		return socket.isClosed();
	}

	private static final class CryptInputStream extends FilterInputStream {
		volatile Cipher cipher;

		CryptInputStream(final InputStream in) {
			super(in);
		}

		@Override
		public int read() throws IOException {
			final int c = in.read();
			if (c < 0 || cipher == null) {
				return c;
			}
			return cipher.update(new byte[]{(byte) c})[0] & 0xFF;
		}

		@Override
		public int read(final byte[] b, final int off, final int len) throws IOException {
			final int n = in.read(b, off, len);
			if (n > 0 && cipher != null) {
				System.arraycopy(cipher.update(b, off, n), 0, b, off, n);
			}
			return n;
		}
	}
}
