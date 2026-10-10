package bwproxy.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON reader/writer, enough for auth responses and chat components. */
public final class Json {
	private final String s;
	private int i;

	private Json(final String s) {
		this.s = s;
	}

	public static Object parse(final String text) {
		final Json p = new Json(text);
		p.ws();
		final Object v = p.value();
		p.ws();
		if (p.i != p.s.length()) {
			throw new IllegalArgumentException("Trailing JSON data at " + p.i);
		}
		return v;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> obj(final Object o) {
		return o instanceof Map ? (Map<String, Object>) o : null;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> list(final Object o) {
		return o instanceof List ? (List<Object>) o : null;
	}

	public static String str(final Object o, final String key) {
		final Map<String, Object> m = obj(o);
		if (m == null) {
			return null;
		}
		final Object v = m.get(key);
		return v instanceof String ? (String) v : v == null ? null : String.valueOf(v);
	}

	public static String quote(final String text) {
		final StringBuilder sb = new StringBuilder(text.length() + 2);
		sb.append('"');
		for (int k = 0; k < text.length(); k++) {
			final char c = text.charAt(k);
			switch (c) {
				case '"' :
					sb.append("\\\"");
					break;
				case '\\' :
					sb.append("\\\\");
					break;
				case '\n' :
					sb.append("\\n");
					break;
				case '\r' :
					sb.append("\\r");
					break;
				case '\t' :
					sb.append("\\t");
					break;
				default :
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		return sb.append('"').toString();
	}

	public static String write(final Object o) {
		final StringBuilder sb = new StringBuilder();
		write(sb, o);
		return sb.toString();
	}

	private static void write(final StringBuilder sb, final Object o) {
		if (o == null) {
			sb.append("null");
		} else if (o instanceof String) {
			sb.append(quote((String) o));
		} else if (o instanceof Number || o instanceof Boolean) {
			sb.append(o);
		} else if (o instanceof Map) {
			sb.append('{');
			boolean first = true;
			for (final Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
				if (e.getValue() == null) {
					continue;
				}
				if (!first) {
					sb.append(',');
				}
				first = false;
				sb.append(quote(String.valueOf(e.getKey()))).append(':');
				write(sb, e.getValue());
			}
			sb.append('}');
		} else if (o instanceof List) {
			sb.append('[');
			boolean first = true;
			for (final Object v : (List<?>) o) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				write(sb, v);
			}
			sb.append(']');
		} else {
			sb.append(quote(String.valueOf(o)));
		}
	}

	private void ws() {
		while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
			i++;
		}
	}

	private Object value() {
		if (i >= s.length()) {
			throw new IllegalArgumentException("Unexpected end of JSON");
		}
		final char c = s.charAt(i);
		if (c == '{') {
			return object();
		}
		if (c == '[') {
			return array();
		}
		if (c == '"') {
			return string();
		}
		if (s.startsWith("true", i)) {
			i += 4;
			return Boolean.TRUE;
		}
		if (s.startsWith("false", i)) {
			i += 5;
			return Boolean.FALSE;
		}
		if (s.startsWith("null", i)) {
			i += 4;
			return null;
		}
		return number();
	}

	private Map<String, Object> object() {
		final Map<String, Object> m = new LinkedHashMap<>();
		i++;
		ws();
		if (s.charAt(i) == '}') {
			i++;
			return m;
		}
		while (true) {
			ws();
			final String k = string();
			ws();
			expect(':');
			ws();
			m.put(k, value());
			ws();
			if (s.charAt(i) == ',') {
				i++;
				continue;
			}
			expect('}');
			return m;
		}
	}

	private List<Object> array() {
		final List<Object> l = new ArrayList<>();
		i++;
		ws();
		if (s.charAt(i) == ']') {
			i++;
			return l;
		}
		while (true) {
			ws();
			l.add(value());
			ws();
			if (s.charAt(i) == ',') {
				i++;
				continue;
			}
			expect(']');
			return l;
		}
	}

	private String string() {
		expect('"');
		final StringBuilder sb = new StringBuilder();
		while (true) {
			final char c = s.charAt(i++);
			if (c == '"') {
				return sb.toString();
			}
			if (c != '\\') {
				sb.append(c);
				continue;
			}
			final char e = s.charAt(i++);
			switch (e) {
				case 'n' :
					sb.append('\n');
					break;
				case 'r' :
					sb.append('\r');
					break;
				case 't' :
					sb.append('\t');
					break;
				case 'b' :
					sb.append('\b');
					break;
				case 'f' :
					sb.append('\f');
					break;
				case 'u' :
					sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
					i += 4;
					break;
				default :
					sb.append(e);
			}
		}
	}

	private Object number() {
		final int start = i;
		while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
			i++;
		}
		if (start == i) {
			throw new IllegalArgumentException("Unexpected character '" + s.charAt(i) + "' at " + i);
		}
		final String n = s.substring(start, i);
		if (n.contains(".") || n.contains("e") || n.contains("E")) {
			return Double.valueOf(n);
		}
		return Long.valueOf(n);
	}

	private void expect(final char c) {
		if (i >= s.length() || s.charAt(i) != c) {
			throw new IllegalArgumentException("Expected '" + c + "' at " + i);
		}
		i++;
	}
}
