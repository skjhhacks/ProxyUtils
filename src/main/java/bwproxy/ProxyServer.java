package bwproxy;

import bwproxy.auth.AccountControl;
import bwproxy.auth.MicrosoftAuth;
import bwproxy.net.McConnection;
import bwproxy.net.PacketReader;
import bwproxy.net.PacketWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;

/**
 * Local Minecraft 1.8.x server that logs in to the real server as the signed-in account and relays
 * the connection through a {@link ProxySession}.
 */
public final class ProxyServer {
	private static final int PROTOCOL_1_8 = 47;
	private final Config config;
	private final LoginResolver profiles;
	private final boolean debug;
	private final AccountControl accountControl;
	private ServerSocket server;

	public ProxyServer(final Config config, final LoginResolver profiles,
			final boolean debug, final AccountControl accountControl) {
		this.config = config;
		this.profiles = profiles;
		this.debug = debug;
		this.accountControl = accountControl;
	}

	/** Binds to loopback only: anyone who could connect here could play on your account. */
	public int start() throws IOException {
		server = new ServerSocket();
		server.setReuseAddress(true);
		server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
				config.integer("listen.port")));
		return server.getLocalPort();
	}

	/** Feature list for the console prompt (no session): reads what is enabled from config. */
	public static java.util.List<bwproxy.feature.Feature> consoleFeatures(final Config config) {
		final java.util.List<bwproxy.feature.Feature> list = new java.util.ArrayList<>();
		final bwproxy.feature.FeatureContext empty = new bwproxy.feature.FeatureContext() {
			@Override public Config config() { return config; }
			@Override public bwproxy.game.GameState state() { return new bwproxy.game.GameState(); }
			@Override public void chat(final String message) { }
			@Override public void ping() { }
		};
		list.add(new bwproxy.feature.ArmorAlerts(empty));
		list.add(new bwproxy.feature.ItemAlerts(empty));
		list.add(new bwproxy.feature.UpgradeAlerts(empty));
		list.add(new bwproxy.feature.TimersHud(empty));
		list.add(new bwproxy.feature.UpgradesHud(empty));
		return list;
	}

	public void serve() {
		while (!server.isClosed()) {
			try {
				final Socket s = server.accept();
				final Thread t = new Thread(() -> handle(s), "bwproxy-conn");
				t.setDaemon(true);
				t.start();
			} catch (final IOException e) {
				if (!server.isClosed()) {
					System.out.println("[proxy] accept failed: " + e.getMessage());
				}
			}
		}
	}

	public void stop() {
		try {
			server.close();
		} catch (final IOException ignored) {
			// closing
		}
	}

	private void handle(final Socket socket) {
		McConnection client = null;
		McConnection upstream = null;
		try {
			socket.setSoTimeout(0);
			client = new McConnection(socket);
			final PacketReader hs = new PacketReader(client.readPacket());
			if (hs.readVarInt() != 0x00) {
				return;
			}
			final int protocol = hs.readVarInt();
			hs.readString();
			hs.readShort();
			final int next = hs.readVarInt();
			final String host = config.get("upstream.host");
			final int port = config.integer("upstream.port");
			upstream = connect(host, port);
			final byte[] handshake = new PacketWriter(0x00).writeVarInt(protocol).writeString(host)
					.writeShort(port).writeVarInt(next).toByteArray();
			upstream.writePacket(handshake);
			if (next == 1) {
				pipeStatus(client, upstream);
				return;
			}
			if (next != 2) {
				return;
			}
			final PacketReader ls = new PacketReader(client.readPacket());
			if (ls.readVarInt() != 0x00) {
				return;
			}
			final String clientName = ls.readString();
			if (protocol != PROTOCOL_1_8) {
				kickLogin(client, "This proxy only supports Minecraft 1.8.x (1.8.9 recommended).");
				return;
			}
			final MicrosoftAuth.Profile profile;
			try {
				profile = profiles.resolve(clientName);
			} catch (final Exception e) {
				kickLogin(client, "Bed Wars proxy: " + e.getMessage());
				if (e instanceof RuntimeException) {
					throw (RuntimeException) e;
				}
				throw new RuntimeException(e);
			}
			upstream.writePacket(new PacketWriter(0x00).writeString(profile.name).toByteArray());
			final String[] account = loginUpstream(upstream, profile, client);
			if (account == null) {
				return;
			}
			client.writePacket(new PacketWriter(0x02).writeString(account[0]).writeString(account[1])
					.toByteArray());
			System.out.println("[proxy] " + account[1] + " connected -> " + host);
			final ProxySession session = new ProxySession(client, upstream, config, account[1], debug,
					accountControl);
			session.run();
			System.out.println("[proxy] " + account[1] + " disconnected");
		} catch (final IOException | RuntimeException e) {
			System.out.println("[proxy] connection ended: " + e);
			if (debug) {
				e.printStackTrace(System.out);
			}
		} finally {
			if (client != null) {
				client.close();
			}
			if (upstream != null) {
				upstream.close();
			}
			try {
				socket.close();
			} catch (final IOException ignored) {
				// closed
			}
		}
	}

	private static McConnection connect(final String host, final int port) throws IOException {
		final Socket s = new Socket();
		s.connect(new InetSocketAddress(host, port), 10_000);
		return new McConnection(s);
	}

	/** Server-list pings are piped through unchanged so the entry shows the real server. */
	private static void pipeStatus(final McConnection client, final McConnection upstream) {
		final Thread t = new Thread(() -> copy(upstream.rawIn(), client.rawOut()), "bwproxy-status");
		t.setDaemon(true);
		t.start();
		copy(client.rawIn(), upstream.rawOut());
		try {
			t.join(3000);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void copy(final InputStream in, final OutputStream out) {
		final byte[] buf = new byte[4096];
		try {
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				out.flush();
			}
		} catch (final IOException ignored) {
			// one side closed
		}
	}

	/** @return {uuid-with-dashes, name} once the server accepted the login, null if refused. */
	private String[] loginUpstream(final McConnection up, final MicrosoftAuth.Profile profile,
			final McConnection client) throws IOException {
		while (true) {
			final PacketReader r = new PacketReader(up.readPacket());
			final int id = r.readVarInt();
			switch (id) {
				case 0x00 : { // Disconnect
					final String reason = r.readString();
					client.writePacket(new PacketWriter(0x00).writeString(reason).toByteArray());
					return null;
				}
				case 0x01 : { // Encryption Request
					answerEncryption(up, profile, r);
					break;
				}
				case 0x02 : // Login Success
					return new String[]{r.readString(), r.readString()};
				case 0x03 : // Set Compression
					up.setCompressionThreshold(r.readVarInt());
					break;
				default :
					throw new IOException("Unexpected login packet 0x" + Integer.toHexString(id));
			}
		}
	}

	private static void answerEncryption(final McConnection up, final MicrosoftAuth.Profile profile,
			final PacketReader r) throws IOException {
		try {
			final String serverId = r.readString();
			final byte[] publicKey = r.readBytes(r.readVarInt());
			final byte[] verifyToken = r.readBytes(r.readVarInt());
			if (profile.accessToken == null) {
				throw new IOException("The server wants online-mode authentication but no account is "
						+ "signed in (was --no-auth used?)");
			}
			final byte[] secret = new byte[16];
			new SecureRandom().nextBytes(secret);
			MicrosoftAuth.joinServer(profile, serverHash(serverId, secret, publicKey));
			final PublicKey key = KeyFactory.getInstance("RSA")
					.generatePublic(new X509EncodedKeySpec(publicKey));
			final Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
			rsa.init(Cipher.ENCRYPT_MODE, key);
			final byte[] encSecret = rsa.doFinal(secret);
			final byte[] encToken = rsa.doFinal(verifyToken);
			up.writePacket(new PacketWriter(0x01).writeVarInt(encSecret.length).writeBytes(encSecret)
					.writeVarInt(encToken.length).writeBytes(encToken).toByteArray());
			up.enableEncryption(secret);
		} catch (final java.security.GeneralSecurityException e) {
			throw new IOException("Encryption handshake failed", e);
		}
	}

	/** Minecraft's signed-hex SHA-1 "server hash" for the session server. */
	public static String serverHash(final String serverId, final byte[] secret,
			final byte[] publicKey) throws java.security.NoSuchAlgorithmException {
		final MessageDigest sha = MessageDigest.getInstance("SHA-1");
		sha.update(serverId.getBytes(StandardCharsets.US_ASCII));
		sha.update(secret);
		sha.update(publicKey);
		return new BigInteger(sha.digest()).toString(16);
	}

	private static void kickLogin(final McConnection client, final String message) throws IOException {
		client.writePacket(new PacketWriter(0x00)
				.writeString("{\"text\":" + bwproxy.util.Json.quote(message) + "}").toByteArray());
	}

	/** Picks the account a connection plays as, given the name its client reports. */
	public interface LoginResolver {
		MicrosoftAuth.Profile resolve(String clientName) throws Exception;
	}
}
