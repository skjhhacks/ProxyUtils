package bwproxy.auth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Minimal HTTP client over HttpURLConnection (Java 8 has no java.net.http). */
final class Http {
	static final class Response {
		final int status;
		final String body;
		/** Xbox puts its error code in this header, often with an empty body. */
		final String xerr;
		/** Raw Date header, used for clock-skew correction. */
		final String dateHeader;

		Response(final int status, final String body, final String xerr, final String dateHeader) {
			this.status = status;
			this.body = body;
			this.xerr = xerr;
			this.dateHeader = dateHeader;
		}
	}

	private Http() {
	}

	static Response post(final String url, final Map<String, String> headers, final String body)
			throws IOException {
		return send("POST", url, headers, body);
	}

	static Response head(final String url) throws IOException {
		return send("HEAD", url, null, null);
	}

	static Response get(final String url, final Map<String, String> headers) throws IOException {
		return send("GET", url, headers, null);
	}

	private static Response send(final String method, final String url,
			final Map<String, String> headers, final String body) throws IOException {
		final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setRequestMethod(method);
		c.setConnectTimeout(15_000);
		c.setReadTimeout(30_000);
		if (headers != null) {
			for (final Map.Entry<String, String> h : headers.entrySet()) {
				c.setRequestProperty(h.getKey(), h.getValue());
			}
		}
		if (body != null) {
			c.setDoOutput(true);
			final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			c.setFixedLengthStreamingMode(bytes.length);
			c.getOutputStream().write(bytes);
		}
		final int status = c.getResponseCode();
		final InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
		String text = "";
		if (is != null) {
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			final byte[] buf = new byte[4096];
			int n;
			while ((n = is.read(buf)) > 0) {
				bos.write(buf, 0, n);
			}
			is.close();
			text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
		}
		return new Response(status, text, c.getHeaderField("X-Err"), c.getHeaderField("Date"));
	}
}
