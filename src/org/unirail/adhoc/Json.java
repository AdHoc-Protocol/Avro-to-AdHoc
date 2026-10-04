package org.unirail.adhoc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader (the JDK has none). Objects become {@link LinkedHashMap} (insertion order kept), arrays
 * {@link List}, numbers {@link Long} when integral else {@link Double}, plus {@link String}, {@link Boolean} and null.
 */
public final class Json {
	private final String s;
	private int i;

	private Json(String s) { this.s = s; }

	public static Object parse(String text) {
		Json p = new Json(text);
		p.ws();
		Object v = p.value();
		p.ws();
		if (p.i != p.s.length()) throw p.err("trailing characters");
		return v;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> object(Object o) { return (Map<String, Object>) o; }

	@SuppressWarnings("unchecked")
	public static List<Object> array(Object o) { return (List<Object>) o; }

	private Object value() {
		if (i >= s.length()) throw err("unexpected end");
		char c = s.charAt(i);
		switch (c) {
			case '{': return object();
			case '[': return array();
			case '"': return string();
			case 't': expect("true"); return Boolean.TRUE;
			case 'f': expect("false"); return Boolean.FALSE;
			case 'n': expect("null"); return null;
			default: return number();
		}
	}

	private Map<String, Object> object() {
		Map<String, Object> m = new LinkedHashMap<>();
		i++; // {
		ws();
		if (peek() == '}') { i++; return m; }
		while (true) {
			ws();
			if (peek() != '"') throw err("object key expected");
			String k = string();
			ws();
			if (peek() != ':') throw err("':' expected");
			i++;
			ws();
			m.put(k, value());
			ws();
			char c = peek();
			if (c == ',') { i++; continue; }
			if (c == '}') { i++; return m; }
			throw err("',' or '}' expected");
		}
	}

	private List<Object> array() {
		List<Object> a = new ArrayList<>();
		i++; // [
		ws();
		if (peek() == ']') { i++; return a; }
		while (true) {
			ws();
			a.add(value());
			ws();
			char c = peek();
			if (c == ',') { i++; continue; }
			if (c == ']') { i++; return a; }
			throw err("',' or ']' expected");
		}
	}

	private String string() {
		StringBuilder sb = new StringBuilder();
		i++; // "
		while (true) {
			if (i >= s.length()) throw err("unterminated string");
			char c = s.charAt(i++);
			if (c == '"') return sb.toString();
			if (c != '\\') { sb.append(c); continue; }
			char e = s.charAt(i++);
			switch (e) {
				case '"': sb.append('"'); break;
				case '\\': sb.append('\\'); break;
				case '/': sb.append('/'); break;
				case 'b': sb.append('\b'); break;
				case 'f': sb.append('\f'); break;
				case 'n': sb.append('\n'); break;
				case 'r': sb.append('\r'); break;
				case 't': sb.append('\t'); break;
				case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
				default: throw err("bad escape \\" + e);
			}
		}
	}

	private Object number() {
		int start = i;
		while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
		String t = s.substring(start, i);
		if (t.isEmpty()) throw err("value expected");
		try {
			if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) return Long.parseLong(t);
			return Double.parseDouble(t);
		} catch (NumberFormatException e) { throw err("bad number " + t); }
	}

	private void expect(String w) {
		if (!s.startsWith(w, i)) throw err(w + " expected");
		i += w.length();
	}

	private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

	private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

	private IllegalArgumentException err(String msg) { return new IllegalArgumentException("JSON: " + msg + " at offset " + i); }
}
