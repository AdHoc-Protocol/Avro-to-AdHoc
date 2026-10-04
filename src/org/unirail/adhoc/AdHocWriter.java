package org.unirail.adhoc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Shared helpers for emitting AdHoc protocol descriptions (.cs) from other schema languages.
 *
 * <p>Everything here follows the rules of an AdHoc description:
 * <ul>
 *   <li>{@link #brush} / {@link #isProhibited} - a name must not be a keyword of a target language, and how one is renamed;</li>
 *   <li>{@link #doc} - doc comments are C# XML documentation, so text is XML-escaped;</li>
 *   <li>{@link #dashboard} - the Packs Inventory block that must directly precede the project interface;</li>
 *   <li>{@link #connectionAllPacks} - a bidirectional state over the whole project, with the custom attribute
 *       classes (which the recursive {@code @scope} would otherwise collect as packs) filtered out by name.</li>
 * </ul>
 * Converters must not modify this class; add converter-specific helpers to the converter itself.
 */
public final class AdHocWriter {
	private AdHocWriter() { }

	public static final int DOC_WIDTH = 110;
	public static final String I1 = "    ", I2 = I1 + I1, I3 = I2 + I1, I4 = I3 + I1, I5 = I4 + I1, I6 = I5 + I1;
	public static final String[] LANGS = {"InTS", "InJAVA", "InCS", "InCPP", "InGO", "InRS"};

	// ═══════════════════════════════════════════ naming ═══════════════════════════════════════════

	/**
	 * A name must not be a keyword of any target language: if it is one, capitalise its first lowercase letter
	 * (then the next one, ...) until it is not.
	 */
	public static String brush(String name) {
		if (!isProhibited(name)) return name;
		String n = name;
		for (int i = 0; i < name.length(); i++)
			if (Character.isLowerCase(name.charAt(i))) {
				n = n.substring(0, i) + Character.toUpperCase(n.charAt(i)) + n.substring(i + 1);
				if (!isProhibited(n)) return n;
			}
		return name;
	}

	/** Whether the name is a keyword of C#, C++, Java, TypeScript, Rust or Go; an AdHoc name must not be one. */
	public static boolean isProhibited(String name) {
		if (name.isEmpty()) throw new IllegalArgumentException("Empty entity name");
		if (name.startsWith("_") || name.endsWith("_"))
			throw new IllegalArgumentException("Entity names cannot start or end with an underscore: `" + name + "`");
		return PROHIBITED.contains(name);
	}

	/**
	 * Turns an arbitrary source name into a legal AdHoc identifier: characters outside {@code [A-Za-z0-9_]}
	 * become {@code _}, runs collapse, leading/trailing underscores are trimmed, a leading digit gets an
	 * {@code N} prefix, then {@link #brush} is applied.
	 */
	public static String ident(String raw) {
		StringBuilder sb = new StringBuilder();
		boolean underscore = false;
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (Character.isLetterOrDigit(c) && c < 128 || c == '_') {
				if (c == '_') {
					if (underscore) continue;
					underscore = true;
				} else underscore = false;
				sb.append(c);
			} else if (!underscore) {
				sb.append('_');
				underscore = true;
			}
		}
		String s = sb.toString();
		while (s.startsWith("_")) s = s.substring(1);
		while (s.endsWith("_")) s = s.substring(0, s.length() - 1);
		if (s.isEmpty()) s = "unnamed";
		if (Character.isDigit(s.charAt(0))) s = "N" + s;
		return brush(s);
	}

	/** {@link #ident} plus uniqueness within {@code taken}: appends 2, 3, ... on a collision. Registers the result. */
	public static String unique(String raw, Set<String> taken) {
		String base = ident(raw), s = base;
		for (int i = 2; taken.contains(s); i++) s = base + i;
		taken.add(s);
		return s;
	}

	// ═══════════════════════════════════════════ text ═══════════════════════════════════════════

	/** Doc text is C# XML documentation; also never let a doc terminate/start a C# comment. */
	public static String xmlEscape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("*/", "*&#47;").replace("/*", "&#47;*");
	}

	public static List<String> wrap(String line, int width) {
		List<String> out = new ArrayList<>();
		while (width < line.length()) {
			int cut = line.lastIndexOf(' ', width);
			if (cut <= 0) cut = line.indexOf(' ', width);
			if (cut <= 0) break;
			out.add(line.substring(0, cut));
			line = line.substring(cut + 1);
		}
		out.add(line);
		return out;
	}

	/**
	 * Emits a {@code /** ... *&#47;} doc comment (nothing when the text is blank). Lines are trimmed, blank lines
	 * dropped, long lines wrapped at {@link #DOC_WIDTH}, and the text XML-escaped. Do not start a line with
	 * {@code *} or {@code //}: such a leading mark is read as comment decoration, not as text.
	 */
	public static void doc(StringBuilder sb, String indent, String text) {
		if (text == null) return;
		List<String> lines = new ArrayList<>();
		for (String l : text.split("\\r?\\n")) {
			l = l.trim();
			if (l.isEmpty()) continue;
			lines.addAll(wrap(xmlEscape(l), DOC_WIDTH));
		}
		if (lines.isEmpty()) return;
		sb.append(indent).append("/**\n");
		for (String l : lines) sb.append(indent).append(l).append('\n');
		sb.append(indent).append("*/\n");
	}

	/** A regular C# string literal. */
	public static String str(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n").replace("\t", "\\t") + "\"";
	}

	/** A verbatim C# string literal (multi-line safe). */
	public static String verbatim(String s) { return "@\"" + s.replace("\"", "\"\"") + "\""; }

	/** A numeric C# literal when the value parses as a finite number, otherwise a string literal. */
	public static String num(String s) {
		try {
			double v = Double.parseDouble(s.trim());
			if (Double.isNaN(v) || Double.isInfinite(v)) return str(s);
			if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
			return Double.toString(v);
		} catch (NumberFormatException e) { return str(s); }
	}

	public static String pad(int n) {
		StringBuilder p = new StringBuilder();
		for (int i = 0; i < n; i++) p.append(' ');
		return p.toString();
	}

	// ═══════════════════════════════════════════ file skeleton ═══════════════════════════════════════════

	/** Leading {@code //} lines (outside every entity, so they attach to nothing) plus the two usings. */
	public static void fileHeader(StringBuilder sb, String generator, String sources, String... extraLines) {
		fileHeader(sb, generator, sources, List.of(), extraLines);
	}

	/**
	 * The header of a description. Under the line of the sources come the pages of their originals, one per line
	 * ({@link Originals}): the reader of the description can open what it was made from.
	 */
	public static void fileHeader(StringBuilder sb, String generator, String sources, List<String> originals, String... extraLines) {
		sb.append("// Generated by ").append(generator).append(" (https://github.com/AdHoc-Protocol)\n");
		sb.append("// Sources: ").append(sources).append('\n');
		for (String page : originals) sb.append("//          ").append(page).append('\n');
		for (String l : extraLines) sb.append("// ").append(l).append('\n');
		sb.append("// Re-run the converter instead of editing this file by hand.\n\n");
		sb.append("using System;\n");
		sb.append("using org.unirail.Meta;\n\n");
	}

	/**
	 * The Packs Inventory. Must be the doc block immediately above {@code public interface <Project>}.
	 * Keys are pack names as referenced from the project scope ({@code Pack} or {@code Outer.Inner}); a null id
	 * leaves the entry without {@code id=} so AdHocAgent assigns one.
	 */
	public static void dashboard(StringBuilder sb, String indent, Map<String, Integer> packIds) {
		TreeMap<String, Integer> sorted = new TreeMap<>(packIds);
		int width = 0;
		for (String n : sorted.keySet()) width = Math.max(width, n.length());
		sb.append(indent).append("/**\n");
		for (Map.Entry<String, Integer> e : sorted.entrySet()) {
			sb.append(indent).append(I1).append("<see cref = '").append(e.getKey()).append("'");
			if (e.getValue() != null) sb.append(pad(width - e.getKey().length())).append(" id = '").append(e.getValue()).append("'");
			sb.append("/>\n");
		}
		sb.append(indent).append("*/\n");
	}

	/** The language-configuration doc block requesting every generator for the host that follows. */
	public static void hostLangs(StringBuilder sb, String indent) {
		sb.append(indent).append("/**\n");
		for (String l : LANGS) sb.append(indent).append("<see cref = '").append(l).append("'/>\n");
		sb.append(indent).append("*/\n");
	}

	/** {@code struct <name> : Host { }} with the language block. */
	public static void host(StringBuilder sb, String indent, String name, String doc) {
		if (doc != null) sb.append(indent).append("// ").append(doc).append('\n');
		hostLangs(sb, indent);
		sb.append(indent).append("struct ").append(name).append(" : Host { }\n\n");
	}

	/**
	 * A connection whose single non-transitional state lets either side send every transmittable pack of the
	 * project. Custom attribute classes declared inside the interface are excluded by name.
	 */
	public static void connectionAllPacks(StringBuilder sb, String indent, String name, String left, String right, String project) {
		sb.append(indent).append("interface ").append(name).append(" : Connects<").append(left).append(", ").append(right).append("> {\n");
		sb.append(indent).append(I1).append("// Every pack of the project, in both directions; the FSM never transitions.\n");
		sb.append(indent).append(I1).append("// SkipName keeps the metadata attribute classes declared in this interface out of the pack set.\n");
		sb.append(indent).append(I1).append("[_____lr_____<@").append(project).append(">(SkipName: @\"Attribute$\")]\n");
		sb.append(indent).append(I1).append("struct Start { }\n");
		sb.append(indent).append("}\n\n");
	}

	/** Opens a connection interface; the caller adds states / RPC methods and closes it with {@code }}. */
	public static void connectionOpen(StringBuilder sb, String indent, String name, String left, String right) {
		sb.append(indent).append("interface ").append(name).append(" : Connects<").append(left).append(", ").append(right).append("> {\n");
	}

	/** A non-transitional state with an explicit pack list on the given branch attribute (e.g. {@code _____lr_____}). */
	public static void statePacks(StringBuilder sb, String indent, String branch, String state, Collection<String> packs) {
		if (packs.isEmpty()) return;
		sb.append(indent).append("[").append(branch).append("<").append(tuple(packs)).append(">]\n");
		sb.append(indent).append("struct ").append(state).append(" { }\n");
	}

	/** {@code A} for one element, {@code (A, B, C)} for several. */
	public static String tuple(Collection<String> items) {
		if (items.size() == 1) return items.iterator().next();
		return "(" + String.join(", ", items) + ")";
	}

	/**
	 * Declares a custom attribute class: {@code public class <Name>Attribute : Attribute { public <Name>Attribute(<params>) { } }}.
	 * {@code ctorParams} are C# parameter declarations such as {@code "string units"}; several strings mean several
	 * constructor overloads. Must be declared inside the project interface.
	 */
	public static void attribute(StringBuilder sb, String indent, String name, String doc, String... ctorOverloads) {
		if (doc != null) sb.append(indent).append("/** ").append(xmlEscape(doc)).append(" */\n");
		sb.append(indent).append("public class ").append(name).append("Attribute : Attribute {");
		if (ctorOverloads.length == 0) sb.append(" }\n\n");
		else {
			for (String p : ctorOverloads) sb.append(" public ").append(name).append("Attribute(").append(p).append(") { }");
			sb.append(" }\n\n");
		}
	}

	// ═══════════════════════════════════════════ keyword list ═══════════════════════════════════════════

	public static final Set<String> PROHIBITED = new HashSet<>(Arrays.asList(
			// C#
			"abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked", "class", "const", "continue",
			"decimal", "default", "delegate", "do", "double", "else", "enum", "event", "explicit", "extern", "false", "finally",
			"fixed", "float", "for", "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock",
			"long", "namespace", "new", "null", "object", "operator", "out", "override", "params", "private", "protected",
			"public", "readonly", "ref", "return", "sbyte", "sealed", "short", "sizeof", "stackalloc", "static", "string",
			"struct", "switch", "this", "throw", "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort",
			"using", "virtual", "void", "volatile",
			// C++
			"alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand", "bitor", "char16_t", "char32_t", "compl",
			"concept", "consteval", "constexpr", "constinit", "const_cast", "decltype", "delete", "dynamic_cast", "export",
			"friend", "inline", "mutable", "noexcept", "nullptr", "or", "or_eq", "reflexpr", "register", "reinterpret_cast",
			"requires", "signed", "static_assert", "static_cast", "template", "thread_local", "typedef", "typeid", "typename",
			"union", "unsigned", "wchar_t", "while", "xor", "xor_eq",
			// Java
			"assert", "boolean", "extends", "final", "implements", "import", "instanceof", "native", "package", "strictfp",
			"super", "synchronized", "throws", "transient",
			// TypeScript
			"any", "debugger", "declare", "from", "function", "keyof", "let", "module", "never", "number", "require",
			"symbol", "type", "undefined", "unique", "unknown", "var", "with", "yield",
			// Rust
			"async", "await", "become", "box", "crate", "dyn", "fn", "impl", "loop", "macro", "match", "mod", "move", "mut",
			"priv", "pub", "self", "Self", "trait", "use", "where",
			// Go
			"chan", "defer", "fallthrough", "func", "go", "range", "select",
			// special cases across languages
			"arguments", "eval"));
}
