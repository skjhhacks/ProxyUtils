package bwproxy.auth;

import bwproxy.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Microsoft device-code sign-in for a Minecraft Java account. The flow and endpoints follow the
 * open-source prismarine-auth "live" flow. This class holds no state between calls: storing the
 * refresh tokens is {@link AccountManager}'s job. Tokens only go to Microsoft/Xbox/Mojang endpoints.
 */
public final class MicrosoftAuth implements AccountManager.Authenticator,
		AccountManager.Authenticator.CookieCapable {
	public static final class Profile {
		public final String accessToken;
		public final String uuid;
		public final String name;

		public Profile(final String accessToken, final String uuid, final String name) {
			this.accessToken = accessToken;
			this.uuid = uuid;
			this.name = name;
		}
	}

	/** A signed-in profile plus the refresh token to store for next time. */
	public static final class Result {
		public final Profile profile;
		public final String refreshToken;

		public Result(final Profile profile, final String refreshToken) {
			this.profile = profile;
			this.refreshToken = refreshToken;
		}
	}

	/**
	 * Minecraft Java Edition title id (Win32), as used by the official launcher. XBL no longer
	 * accepts the Nintendo Switch title id (00000000441cc96b) for title authentication; the
	 * Windows SISU endpoints below require this one.
	 */
	private static final String CLIENT_ID = "00000000402b5328";
	private static final String LAUNCHER_UA = "MinecraftLauncher/2.2.10675";
	private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
	private static final String DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf";
	private static final String TOKEN_URL = "https://login.live.com/oauth20_token.srf";
	private static final String USER_AUTH = "https://user.auth.xboxlive.com/user/authenticate";
	private static final String DEVICE_AUTH = "https://device.auth.xboxlive.com/device/authenticate";
	private static final String SISU_AUTHORIZE = "https://sisu.xboxlive.com/authorize";
	private static final String XSTS_AUTH = "https://xsts.auth.xboxlive.com/xsts/authorize";
	private static final String MC_LOGIN = "https://api.minecraftservices.com/authentication/login_with_xbox";
	private static final String MC_PROFILE = "https://api.minecraftservices.com/minecraft/profile";
	/** OAuth authorize endpoint replaying browser cookies against. */
	private static final String AUTHORIZE_URL = "https://login.live.com/oauth20_authorize.srf";
	/** Where the authorize flow ends up with the code= we exchange for tokens. */
	private static final String DESKTOP_REDIRECT = "https://login.live.com/oauth20_desktop.srf";
	private static final String AUTH_SCOPE = "XboxLive.signin XboxLive.offline_access";
	private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
			+ "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
	private KeyPair key;

	/** Silent sign-in from a stored refresh token. Fails if Microsoft no longer accepts it. */
	@Override
	public Result signInWithRefreshToken(final String refreshToken) throws Exception {
		final String body = "scope=" + enc(SCOPE) + "&client_id=" + CLIENT_ID
				+ "&grant_type=refresh_token&refresh_token=" + enc(refreshToken);
		final Http.Response r = Http.post(TOKEN_URL, form(), body);
		if (r.status == 400 && r.body.contains("invalid_grant")) {
			throw new IOException("Microsoft no longer accepts this saved sign-in (invalid_grant). "
					+ "Remove the account and add it again.");
		}
		if (r.status != 200) {
			throw new IOException("Microsoft token refresh failed (HTTP " + r.status + "): " + r.body);
		}
		final Map<String, Object> token = Json.obj(Json.parse(r.body));
		if (Json.str(token, "access_token") == null) {
			throw new IOException("Microsoft token refresh did not return an access token: " + r.body);
		}
		return finish(token);
	}

	/**
	 * Interactive sign-in. {@code prompt} receives the lines to show the user (link and code); it is
	 * called on the calling thread before this blocks waiting for the user.
	 */
	@Override
	public Result signInWithDeviceCode(final Consumer<List<String>> prompt)
			throws Exception {
		final String body = "scope=" + enc(SCOPE) + "&client_id=" + CLIENT_ID
				+ "&response_type=device_code";
		final Http.Response r = Http.post(DEVICE_CODE_URL, form(), body);
		if (r.status != 200) {
			throw new IOException("Could not start Microsoft sign-in (" + r.status + "): " + r.body);
		}
		final Map<String, Object> dc = Json.obj(Json.parse(r.body));
		final String deviceCode = Json.str(dc, "device_code");
		final long interval = Math.max(1, Long.parseLong(Json.str(dc, "interval")));
		final long expires = Long.parseLong(Json.str(dc, "expires_in"));
		final List<String> lines = new ArrayList<>();
		lines.add("Open:  " + Json.str(dc, "verification_uri"));
		lines.add("Enter: " + Json.str(dc, "user_code"));
		lines.add("Sign in with the Microsoft account that owns Minecraft.");
		prompt.accept(lines);
		final long deadline = System.currentTimeMillis() + expires * 1000L;
		while (System.currentTimeMillis() < deadline) {
			Thread.sleep(interval * 1000L);
			final String poll = "client_id=" + CLIENT_ID + "&device_code=" + enc(deviceCode)
					+ "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code");
			final Http.Response p = Http.post(TOKEN_URL + "?client_id=" + CLIENT_ID, form(), poll);
			final Map<String, Object> j = Json.obj(Json.parse(p.body));
			final String error = Json.str(j, "error");
			if (error == null) {
				return finish(j);
			}
			if (!"authorization_pending".equals(error)) {
				throw new IOException("Sign-in failed: " + error + " - "
						+ Json.str(j, "error_description"));
			}
		}
		throw new IOException("The sign-in code expired before it was used.");
	}

	/**
	 * Sign in with a browser cookie file (Netscape format, as exported by cookie-editor and
	 * friends). Replays the OAuth authorize flow with the MSA session cookies, auto-accepts the
	 * consent page, harvests the authorization code and exchanges it for tokens, exactly like a
	 * browser would. The account ends up with a normal refresh token afterwards.
	 *
	 * @throws IOException with a readable reason when the cookies no longer work
	 */
	public Result signInWithCookieFile(final Path cookieFile) throws Exception {
		final Map<String, String> jar = new LinkedHashMap<>();
		int loaded = 0;
		for (final String line : Files.readAllLines(cookieFile, StandardCharsets.UTF_8)) {
			final String t = line.trim();
			if (t.isEmpty() || t.startsWith("#")) {
				continue;
			}
			final String[] p = t.split("\\t");
			if (p.length < 7 || !p[0].contains("live.com")) {
				continue;
			}
			jar.put(p[5], p[6]); // values are stored exactly as in the file; sent raw
			loaded++;
		}
		if (loaded == 0) {
			throw new IOException("No login.live.com cookies found in " + cookieFile.getFileName()
					+ " (expected a Netscape cookie file).");
		}
		final String authorize = AUTHORIZE_URL
				+ "?client_id=" + CLIENT_ID
				+ "&response_type=code"
				+ "&redirect_uri=" + enc(DESKTOP_REDIRECT)
				+ "&scope=" + enc(AUTH_SCOPE)
				+ "&approval_prompt=auto";
		final String code = walkForCode(authorize, jar);
		if (code == null) {
			throw new IOException("Could not complete the browser sign-in with these cookies. "
					+ "They may have expired - export a fresh cookie file from an account signed "
					+ "in at login.live.com.");
			}
		final String body = "client_id=" + CLIENT_ID + "&grant_type=authorization_code&code=" + enc(code)
				+ "&redirect_uri=" + enc(DESKTOP_REDIRECT);
		final Http.Response token = Http.post(TOKEN_URL, form(), body);
		if (token.status != 200) {
			throw new IOException("Token exchange failed (HTTP " + token.status + "): "
					+ shortBody(token.body));
		}
		final Map<String, Object> j = Json.obj(Json.parse(token.body));
		if (Json.str(j, "access_token") == null) {
			throw new IOException("Token exchange did not return an access token: "
					+ shortBody(token.body));
			}
		// The authorize flow used the XboxLive sign-in scope, whose access token is addressed to
		// xboxlive, not user.auth.xboxlive.com. Refresh it once with the standard MBI_SSL scope
		// (this also rotates the refresh token) and continue through the normal chain.
		return signInWithRefreshToken(Json.str(j, "refresh_token"));
	}

	/**
	 * Follows the authorize chain on the cookie jar until an {@code code=} shows up. Handles:
	 * 302 redirects; the no-JS auto-submit form on login.live.com; and the React consent page
	 * on account.live.com, which is accepted by POSTing {@code ucaction=Yes} with its canary
	 * back to the same URL.
	 */
	private static String walkForCode(final String startUrl, final Map<String, String> jar)
			throws IOException {
		String url = startUrl;
		String postBody = null;
		String lastError = null;
		for (int hop = 0; hop < 15; hop++) {
			final boolean isPost = postBody != null;
			final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
			c.setRequestMethod(isPost ? "POST" : "GET");
			c.setInstanceFollowRedirects(false);
			c.setConnectTimeout(15_000);
			c.setReadTimeout(30_000);
			c.setRequestProperty("Cookie", cookieHeader(jar));
			c.setRequestProperty("User-Agent", BROWSER_UA);
			c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
			if (isPost) {
				c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
				c.setDoOutput(true);
				final byte[] bytes = postBody.getBytes(StandardCharsets.UTF_8);
				c.setFixedLengthStreamingMode(bytes.length);
				c.getOutputStream().write(bytes);
			}
			postBody = null;
			final int status = c.getResponseCode();
			final String location = c.getHeaderField("Location");
			storeCookies(c, jar);
			final String html = readBody(c, status);
			storeCookies(c, jar); // some Set-Cookie headers arrive after the body is read
			if (location != null) {
				final String next = resolve(url, location);
				final String code = codeIn(next);
				if (code != null) {
					return code;
				}
				url = next;
				continue;
			}
			if (html.isEmpty()) {
				lastError = "server returned empty " + (isPost ? "response" : "page") + " (HTTP " + status + ")";
				break;
			}
			if (url.contains("Consent/Update")) {
				// The React consent SPA: accept it with the same hidden fields a browser sends.
				final String canary = jsonString(html, "sCanary");
				final String clientId = jsonString(html, "sClientId");
				final String scopes = jsonString(html, "sRawInputScopes");
				final String granted = jsonString(html, "sRawInputGrantedScopes");
				if (canary == null || clientId == null) {
					lastError = "consent page did not carry the expected data";
					break;
				}
				postBody = "ucaction=Yes"
						+ "&client_id=" + urlEnc(clientId)
						+ "&scope=" + urlEnc(scopes == null ? "" : scopes)
						+ "&cscope=" + urlEnc(granted == null ? "" : granted)
						+ "&canary=" + urlEnc(canary);
				continue; // same URL
			}
			final String action = formAction(html);
			if (action == null) {
				lastError = "unexpected page at " + domainOf(url) + " (no form, no consent page)";
				break;
			}
			final Map<String, String> fields = formFields(html);
			final StringBuilder b = new StringBuilder();
			for (final Map.Entry<String, String> f : fields.entrySet()) {
				if (b.length() > 0) {
					b.append('&');
				}
				b.append(urlEnc(f.getKey())).append('=').append(urlEnc(f.getValue()));
			}
			postBody = b.toString();
			final String resolved = resolve(url, action);
			final String code = codeIn(resolved);
			if (code != null) {
				return code;
			}
			url = resolved;
		}
		throw new IOException("Sign-in stuck at " + domainOf(url) + " (" + lastError + ").");
	}

	/** Reads a whole response body as UTF-8 text. */
	private static String readBody(final HttpURLConnection c, final int status) throws IOException {
		final InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
		if (is == null) {
			return "";
		}
		final ByteArrayOutputStream bos = new ByteArrayOutputStream();
		final byte[] buf = new byte[4096];
		int n;
		while ((n = is.read(buf)) > 0) {
			bos.write(buf, 0, n);
		}
		is.close();
		return new String(bos.toByteArray(), StandardCharsets.UTF_8);
	}

	/** Merges every Set-Cookie header into the jar (name -> raw value). */
	private static void storeCookies(final HttpURLConnection c, final Map<String, String> jar) {
		final List<String> set = c.getHeaderFields().get("Set-Cookie");
		if (set == null) {
			return;
		}
		for (final String sc : set) {
			final String nv = sc.split(";", 2)[0];
			final int eq = nv.indexOf('=');
			if (eq > 0) {
				jar.put(nv.substring(0, eq).trim(), nv.substring(eq + 1));
			}
		}
	}

	private static String cookieHeader(final Map<String, String> jar) {
		final StringBuilder sb = new StringBuilder();
		for (final Map.Entry<String, String> e : jar.entrySet()) {
			if (sb.length() > 0) {
				sb.append("; ");
			}
			sb.append(e.getKey()).append('=').append(e.getValue());
		}
		return sb.toString();
	}

	/** first <form action="..."> in the page, &amp; decoded. */
	private static String formAction(final String html) {
		final java.util.regex.Matcher m = Pattern
				.compile("<form[^>]*action=\"([^\"]+)\"").matcher(html);
		return m.find() ? m.group(1).replace("&amp;", "&") : null;
	}

	/** All hidden <input> name/value pairs, first attribute order tolerated. */
	private static Map<String, String> formFields(final String html) {
		final Map<String, String> fields = new LinkedHashMap<>();
		java.util.regex.Matcher m = Pattern
				.compile("<input[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"").matcher(html);
		while (m.find()) {
			if (!fields.containsKey(m.group(1))) {
				fields.put(m.group(1), m.group(2));
			}
		}
		if (fields.isEmpty()) {
			m = Pattern.compile("<input[^>]*value=\"([^\"]*)\"[^>]*name=\"([^\"]+)\"").matcher(html);
			while (m.find()) {
				fields.put(m.group(2), m.group(1));
			}
		}
		return fields;
	}

	/** "key":"value" (with \\uXXXX escapes) from the ServerData JSON embedded in a page. */
	private static String jsonString(final String html, final String key) {
		final String needle = "\"" + key + "\":\"";
		int i = html.indexOf(needle);
		if (i < 0) {
			return null;
		}
		i += needle.length();
		final StringBuilder b = new StringBuilder();
		while (i < html.length()) {
			final char ch = html.charAt(i);
			if (ch == '"') {
				break;
			}
			if (ch == '\\' && i + 1 < html.length()) {
				final char n = html.charAt(++i);
				if (n == 'u' && i + 4 < html.length()) {
					b.append((char) Integer.parseInt(html.substring(i + 1, i + 5), 16));
					i += 4;
				} else {
					b.append(n);
				}
			} else {
				b.append(ch);
			}
			i++;
		}
		return b.toString();
	}

	/** code=<token> in a redirect URL; the token may contain '.', '!', '-', '*', '~'. */
	private static String codeIn(final String url) {
		final java.util.regex.Matcher m = Pattern
				.compile("[?&]code=([A-Za-z0-9!*._~!-]+)").matcher(url);
		return m.find() ? m.group(1) : null;
	}

	/** Absolute form of a possibly relative redirect target. */
	private static String resolve(final String base, final String location) throws IOException {
		try {
			return new URL(new URL(base), location).toString();
		} catch (final IOException e) {
			throw new IOException("Bad redirect from " + base, e);
		}
	}

	private static String domainOf(final String url) {
		try {
			return new URL(url).getHost();
			} catch (final IOException e) {
			return url;
		}
	}

	private static String shortBody(final String body) {
		return body.length() <= 300 ? body : body.substring(0, 300) + "...";
	}

	private static String urlEnc(final String s) {
		try {
			return URLEncoder.encode(s == null ? "" : s, "UTF-8");
		} catch (final java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	private Result finish(final Map<String, Object> token) throws Exception {
		final Profile profile = minecraftLogin(Json.str(token, "access_token"));
		return new Result(profile, Json.str(token, "refresh_token"));
	}

	/** Tells Mojang's session server this account is joining {@code serverHash}. */
	public static void joinServer(final Profile profile, final String serverHash) throws IOException {
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("accessToken", profile.accessToken);
		body.put("selectedProfile", profile.uuid);
		body.put("serverId", serverHash);
		final Http.Response r = Http.post("https://sessionserver.mojang.com/session/minecraft/join",
				jsonHeaders(), Json.write(body));
		if (r.status != 204 && r.status != 200) {
			throw new IOException("Session server refused the login (" + r.status + "): " + r.body);
		}
	}

	/**
	 * Offset between this machine's clock and Microsoft's, cached forever per run: added to every
	 * timestamp in the Xbox Signature header. A skewed clock is the single most common cause of a
	 * 401 from the SISU/authorize endpoints.
	 */
	private static volatile long timeOffsetMillis = Long.MIN_VALUE;

	private static long serverAdjustedNowMillis() {
		if (timeOffsetMillis == Long.MIN_VALUE) {
			try {
				final Http.Response head = Http.head("https://login.live.com/");
				final String date = head.dateHeader;
				if (date != null) {
					timeOffsetMillis = parseHttpDate(date) - System.currentTimeMillis();
				} else {
					timeOffsetMillis = 0L;
				}
			} catch (final IOException | RuntimeException e) {
				timeOffsetMillis = 0L; // offline/misc; let the real endpoints report the true error
			}
		}
		return System.currentTimeMillis() + timeOffsetMillis;
	}

	private static long parseHttpDate(final String date) {
		for (final String format : new String[]{"EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm:ss ZZZ"}) {
			try {
				return new java.text.SimpleDateFormat(format, java.util.Locale.US) {{
					setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
				}}.parse(date).getTime();
			} catch (final RuntimeException | java.text.ParseException ignored) {
				// try the next
			}
		}
		return 0L;
	}

	/**
	 * The XBL login chain that currently works: user.auth with a "t=" prefixed ticket (title
	 * client id), then plain xsts.authorize with relying party rp://api.minecraftservices.com/.
	 * The old title.authenticate endpoint (403) and the Win32 SISU authorize (401) are both dead
	 * for third-party clients; this path requires no request signing.
	 */
	private synchronized Profile minecraftLogin(final String msaToken) throws Exception {
		final Map<String, Object> userProps = new LinkedHashMap<>();
		userProps.put("AuthMethod", "RPS");
		userProps.put("SiteName", "user.auth.xboxlive.com");
		userProps.put("RpsTicket", "t=" + msaToken);
		final Map<String, Object> userPayload = body(userProps);
		userPayload.put("RelyingParty", "http://auth.xboxlive.com");
		userPayload.put("TokenType", "JWT");
		final Map<String, String> userHeaders = jsonHeaders();
		userHeaders.put("x-xbl-contract-version", "1");
		final Http.Response user = Http.post(USER_AUTH, userHeaders, Json.write(userPayload));
		if (user.status != 200) {
			throw new IOException(explainXboxError(USER_AUTH, user));
		}
		final Map<String, Object> userJson = Json.obj(Json.parse(user.body));
		final String userToken = Json.str(userJson, "Token");
		if (userToken == null) {
			throw new IOException("Xbox user token missing from the sign-in reply.");
		}
		final String uhs = userHash(userJson);
		if (uhs == null) {
			throw new IllegalStateException("Could not read the Xbox user hash from the sign-in reply.");
		}

		final Map<String, Object> xstsProps = new LinkedHashMap<>();
		xstsProps.put("SandboxId", "RETAIL");
		final List<Object> userTokens = new ArrayList<>();
		userTokens.add(userToken);
		xstsProps.put("UserTokens", userTokens);
		final Map<String, Object> xstsPayload = body(xstsProps);
		xstsPayload.put("RelyingParty", "rp://api.minecraftservices.com/");
		xstsPayload.put("TokenType", "JWT");
		final Http.Response xsts = Http.post(XSTS_AUTH, jsonHeaders(), Json.write(xstsPayload));
		if (xsts.status != 200) {
			throw new IOException(explainXboxError(XSTS_AUTH, xsts));
		}
		final Map<String, Object> xstsJson = Json.obj(Json.parse(xsts.body));
		final String xstsToken = Json.str(xstsJson, "Token");
		if (xstsToken == null) {
			throw new IOException("Xbox XSTS token missing from the sign-in reply.");
		}
		final String xstsUhs = userHash(xstsJson);

		final Map<String, String> jsonHeaders = jsonHeaders();
		jsonHeaders.put("User-Agent", LAUNCHER_UA);
		final Map<String, Object> mcBody = new LinkedHashMap<>();
		mcBody.put("identityToken", "XBL3.0 x=" + xstsUhs + ";" + xstsToken);
		final Http.Response mc = Http.post(MC_LOGIN, jsonHeaders, Json.write(mcBody));
		if (mc.status != 200) {
			throw new IOException("Minecraft services login failed (" + mc.status + "): " + mc.body);
		}
		final String mcToken = Json.str(Json.parse(mc.body), "access_token");

		final Map<String, String> auth = new LinkedHashMap<>();
		auth.put("Authorization", "Bearer " + mcToken);
		auth.put("User-Agent", LAUNCHER_UA);
		final Http.Response prof = Http.get(MC_PROFILE, auth);
		if (prof.status == 404) {
			throw new IOException("This Microsoft account does not own Minecraft: Java Edition.");
		}
		if (prof.status != 200) {
			throw new IOException("Could not fetch Minecraft profile (" + prof.status + "): " + prof.body);
		}
		final Object p = Json.parse(prof.body);
		return new Profile(mcToken, Json.str(p, "id"), Json.str(p, "name"));
	}

	/** The "uhs" from a DisplayClaims.xui[0] reply. */
	private static String userHash(final Map<String, Object> reply) {
		final Map<String, Object> claims = Json.obj(reply.get("DisplayClaims"));
		if (claims == null) {
			return null;
		}
		final List<Object> xui = Json.list(claims.get("xui"));
		if (xui == null || xui.isEmpty()) {
			return null;
		}
		return Json.str(Json.obj(xui.get(0)), "uhs");
	}

	/** Standard XBL JSON body shape: Properties first. */
	private static Map<String, Object> body(final Map<String, Object> properties) {
		final Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("Properties", properties);
		return payload;
	}

	/** The "u" (user hash) claim from an XBL token JWT payload. */
	private static String userHashFromToken(final String jwt) {
		if (jwt == null) {
			throw new IllegalStateException("XBL did not return a user hash.");
		}
		final String[] parts = jwt.split(Pattern.quote("."));
		if (parts.length < 2) {
			throw new IllegalStateException("XBL returned a malformed token.");
		}
		try {
			final byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
			final Map<String, Object> claimsJson = Json.obj(Json
					.parse(new String(decoded, StandardCharsets.UTF_8)));
			final String xuid = Json.str(claimsJson, "u");
			if (xuid != null) {
				return xuid;
			}
		} catch (final RuntimeException ignored) {
			// fall through
		}
		throw new IllegalStateException("Could not read the Xbox user hash from the sign-in reply.");
	}

	private Map<String, Object> xbl(final String url, final String relyingParty,
			final Map<String, Object> properties, final String contractVersion) throws Exception {
		final Map<String, Object> payload = body(properties);
		payload.put("RelyingParty", relyingParty);
		payload.put("TokenType", "JWT");
		final String body = Json.write(payload);
		final Map<String, String> headers = jsonHeaders();
		headers.put("Cache-Control", "no-store, must-revalidate, no-cache");
		headers.put("x-xbl-contract-version", contractVersion);
		headers.put("Signature", sign(new java.net.URL(url).getPath(), body));
		final Http.Response r = Http.post(url, headers, body);
		if (r.status != 200) {
			throw new IOException(explainXboxError(url, r));
		}
		return Json.obj(Json.parse(r.body));
	}

	private static String explainXboxError(final String url, final Http.Response r) {
		String detail = "";
		try {
			Long fromHeader = null;
			if (r.xerr != null) {
				try {
					fromHeader = Long.parseLong(r.xerr.trim());
				} catch (final NumberFormatException ignored) {
					detail = " X-Err=" + r.xerr;
				}
			}
			final Map<String, Object> parsed = r.body.isEmpty() ? null : Json.obj(Json.parse(r.body));
			final Object x = fromHeader != null ? (Object) fromHeader
					: parsed != null ? parsed.get("XErr") : null;
			if (x instanceof Number) {
				final long code = ((Number) x).longValue();
				switch ((int) (code - 2148916000L)) {
					case 227 :
						return "This account is banned from Xbox and cannot play.";
					case 229 :
						return "This account is restricted; a guardian must allow online play at https://account.microsoft.com/family/";
					case 233 :
						return "This Microsoft account has no Xbox profile. Create one at https://signup.live.com/signup";
					case 234 :
						return "Accept the Xbox terms of service by signing in once at https://www.xbox.com";
					case 235 :
						return "Xbox Live is not available in this account's region.";
					case 236 :
						return "This account needs age verification at https://login.live.com/login.srf";
					case 237 :
						return "This account has hit its Xbox playtime limit and is blocked from signing in.";
					case 238 :
						return "This is a child account; an adult must add it to a Microsoft family.";
					default :
						detail = " XErr=" + code;
				}
			}
		} catch (final RuntimeException ignored) {
			// not JSON
		}
		final String extra = r.status == 401
				? " (401 at SISU usually means the MSA token was not accepted - retry the sign-in; "
					+ "if it repeats, remove the account and add it again)"
				: "";
		return "Xbox sign-in failed at " + url + " (HTTP " + r.status + ")." + detail + extra + " "
				+ r.body;
	}

	/** Xbox request signature: policy(4) + windows timestamp(8) + ES256 r||s (64), base64. */
	private String sign(final String path, final String payload) throws Exception {
		final long windowsTs = (serverAdjustedNowMillis() / 1000L + 11644473600L) * 10000000L;
		final ByteBuffer data = ByteBuffer.allocate(4 + 1 + 8 + 1 + 5 + path.length() + 1 + 1
				+ payload.getBytes(StandardCharsets.UTF_8).length + 1);
		data.putInt(1);
		data.put((byte) 0);
		data.putLong(windowsTs);
		data.put((byte) 0);
		data.put("POST".getBytes(StandardCharsets.UTF_8)).put((byte) 0);
		data.put(path.getBytes(StandardCharsets.UTF_8)).put((byte) 0);
		data.put((byte) 0); // empty authorization token, NUL-terminated
		data.put(payload.getBytes(StandardCharsets.UTF_8)).put((byte) 0);
		final Signature s = Signature.getInstance("SHA256withECDSA");
		s.initSign(key.getPrivate());
		s.update(data.array(), 0, data.position());
		final byte[] raw = derToRaw(s.sign(), 32);
		final ByteBuffer header = ByteBuffer.allocate(12 + raw.length);
		header.putInt(1);
		header.putLong(windowsTs);
		header.put(raw);
		return Base64.getEncoder().encodeToString(header.array());
	}

	/** Converts an ASN.1 DER ECDSA signature to fixed-width r||s (IEEE P1363). */
	public static byte[] derToRaw(final byte[] der, final int size) {
		int i = 2;
		if ((der[1] & 0x80) != 0) {
			i += der[1] & 0x7F;
		}
		final byte[] out = new byte[size * 2];
		for (int part = 0; part < 2; part++) {
			if (der[i] != 0x02) {
				throw new IllegalArgumentException("Bad DER signature");
			}
			int len = der[i + 1] & 0xFF;
			int off = i + 2;
			i = off + len;
			while (len > size) {
				off++;
				len--;
			}
			System.arraycopy(der, off, out, part * size + (size - len), len);
		}
		return out;
	}

	private Map<String, Object> jwk() {
		final ECPublicKey pub = (ECPublicKey) key.getPublic();
		final Map<String, Object> jwk = new LinkedHashMap<>();
		jwk.put("kty", "EC");
		jwk.put("x", b64url(pub.getW().getAffineX()));
		jwk.put("y", b64url(pub.getW().getAffineY()));
		jwk.put("crv", "P-256");
		jwk.put("alg", "ES256");
		jwk.put("use", "sig");
		return jwk;
	}

	private static String b64url(final BigInteger v) {
		final byte[] raw = v.toByteArray();
		final byte[] fixed = new byte[32];
		final int copy = Math.min(32, raw.length);
		System.arraycopy(raw, raw.length - copy, fixed, 32 - copy, copy);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed);
	}

	private static Map<String, String> form() {
		final Map<String, String> h = new LinkedHashMap<>();
		h.put("Content-Type", "application/x-www-form-urlencoded");
		return h;
	}

	private static Map<String, String> jsonHeaders() {
		final Map<String, String> h = new LinkedHashMap<>();
		h.put("Content-Type", "application/json");
		h.put("Accept", "application/json");
		return h;
	}

	private static String enc(final String s) {
		try {
			return java.net.URLEncoder.encode(s, "UTF-8");
		} catch (final java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}
}
