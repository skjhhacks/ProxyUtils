package bwproxy.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import bwproxy.util.Json;

/**
 * Reads the account the Minecraft Launcher is signed in to from its local files, so the proxy can
 * default to "whatever my Minecraft is logged into" instead of the last proxy account.
 *
 * <p>Only names and uuids are read from the launcher's files. No tokens are copied or used: the
 * proxy still signs in itself, it just picks the right account automatically.</p>
 */
public final class LauncherAccountScanner {
	/** Set by the first successful read and cached afterwards. */
	private String activeUuid;
	private String activeName;
	private boolean read;

	/** Minecraft uuid (with dashes) of the launcher's active account, or null if unknown. */
	public String activeUuid() {
		read();
		return activeUuid;
	}

	/** Minecraft username of the launcher's active account, or null if unknown. */
	public String activeName() {
		read();
		return activeName;
	}

	private void read() {
		if (read) {
			return;
		}
		read = true;
		final Path dir = dotMinecraft();
		final String[] files = {"launcher_accounts.json", "launcher_accounts_microsoft_store.json"};
		for (final String name : files) {
			try {
				final Path f = dir.resolve(name);
				if (!Files.exists(f)) {
					continue;
				}
				final Map<String, Object> root = Json.obj(Json
						.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)));
				if (root == null) {
					continue;
				}
				if (entryFromAccounts(root, true) || entryFromAccounts(root, false)) {
					return;
				}
			} catch (final IOException | RuntimeException ignored) {
				// try the next file
			}
		}
	}

	/**
	 * Walks the "accounts" map; the first pass only accepts the entry named by
	 * "activeAccountLocalId", the second any single entry present.
	 */
	private boolean entryFromAccounts(final Map<String, Object> root, final boolean activeOnly) {
		final String activeId = Json.str(root, "activeAccountLocalId");
		final Map<String, Object> accounts = Json.obj(root.get("accounts"));
		if (accounts == null) {
			return false;
		}
		for (final Map.Entry<String, Object> e : accounts.entrySet()) {
			final Map<String, Object> acc = Json.obj(e.getValue());
			if (acc == null) {
				continue;
			}
			if (activeOnly && !e.getKey().equals(activeId)) {
				continue;
			}
			final Map<String, Object> profile = Json.obj(acc.get("minecraftProfile"));
			String uuid = profile != null ? Json.str(profile, "id") : null;
			String name = profile != null ? Json.str(profile, "name") : null;
			if (uuid == null) {
				// Older per-user launcher format: key is a uuid, "displayName" is the name.
				uuid = e.getKey();
				name = Json.str(acc, "displayName");
			}
			if (isEmpty(uuid) || isEmpty(name) || !looksLikeUuid(uuid)) {
				continue;
			}
			activeUuid = addDashes(uuid);
			activeName = name;
			return true;
		}
		return false;
	}

	private static boolean isEmpty(final String s) {
		return s == null || s.trim().isEmpty() || "null".equals(s.trim());
	}

	private static boolean looksLikeUuid(final String s) {
		return s != null && (s.matches("[0-9a-fA-F]{32}") || s.matches(
				"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
	}

	private static String addDashes(final String u) {
		if (u != null && u.length() == 32 && !u.contains("-")) {
			return u.substring(0, 8) + "-" + u.substring(8, 12) + "-" + u.substring(12, 16) + "-"
					+ u.substring(16, 20) + "-" + u.substring(20);
		}
		return u;
	}

	/** The .minecraft folder: %APPDATA%\.minecraft, or the folder a launcher log points at. */
	static Path dotMinecraft() {
		final Path def = Paths.get(System.getenv("APPDATA"), ".minecraft");
		if (Files.isDirectory(def)) {
			return def;
		}
		// Non-standard installs: recent launcher logs mention the folder they launched from.
		for (int i = 0; i <= 9; i++) {
			final Path log = Paths.get(System.getenv("APPDATA"), ".minecraft",
					i == 0 ? "launcher_log.txt" : "launcher_log" + i + ".txt");
			final Path p = fromLog(log);
			if (p != null) {
				return p;
			}
		}
		return def;
	}

	private static Path fromLog(final Path log) {
		try {
			if (!Files.exists(log)) {
				return null;
			}
			final List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
			for (int i = lines.size() - 1; i >= 0; i--) {
				final String l = lines.get(i);
				final int idx = l.indexOf("game_directory");
				if (idx < 0) {
					continue;
				}
				final int start = l.indexOf(':', idx) + 1;
				String dir = l.substring(start).trim().replace("\\\\", "\\");
				dir = dir.replaceAll("^\"|\"$", "");
				if (!dir.isEmpty()) {
					return Paths.get(dir);
				}
			}
		} catch (final IOException | RuntimeException ignored) {
			// keep looking
		}
		return null;
	}
}
