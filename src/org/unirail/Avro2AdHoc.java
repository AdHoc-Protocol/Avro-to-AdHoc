package org.unirail;

import org.unirail.adhoc.AdHocWriter;
import org.unirail.adhoc.Json;
import org.unirail.adhoc.Originals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.unirail.adhoc.AdHocWriter.I1;
import static org.unirail.adhoc.AdHocWriter.I2;
import static org.unirail.adhoc.AdHocWriter.I3;
import static org.unirail.adhoc.AdHocWriter.doc;
import static org.unirail.adhoc.AdHocWriter.ident;
import static org.unirail.adhoc.AdHocWriter.str;

/**
 * Apache Avro schema (.avsc) / protocol (.avpr) → AdHoc protocol description (.cs) converter.
 *
 * <p>Usage: <code>java -cp out org.unirail.Avro2AdHoc &lt;file or folder&gt; [output folder]</code>
 * (output defaults to <code>&lt;cwd&gt;/AdHoc</code>). One <code>.cs</code> per input file.
 *
 * <p>Mapping summary (details in README.md):
 * <ul>
 *   <li>record / error → pack (<code>class</code>); enum → <code>enum</code> (constants container below 2 symbols);
 *       fixed(N) → TYPEDEF alias of <code>[D(N)] Binary[]</code>;</li>
 *   <li>bytes → <code>Binary[,,]</code>, array&lt;T&gt; → <code>T[,,]</code>, map&lt;T&gt; → <code>Map&lt;string, T&gt;</code>;
 *       a collection nested in a collection goes through a TYPEDEF alias;</li>
 *   <li>[null, T] → optional T; a union of several non-null types → a pack with one optional field per alternative;</li>
 *   <li>logical types, defaults, order and aliases → custom attributes;</li>
 *   <li>protocol messages → RPC shorthand <code>(L____________, Response, Errors…) name(name_Request req);</code>,
 *       one-way messages → one fire-and-forget state.</li>
 * </ul>
 */
public class Avro2AdHoc {

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("Usage: java -cp out org.unirail.Avro2AdHoc <.avsc/.avpr file or folder> [output folder]");
			System.out.println("       output folder defaults to <current dir>/AdHoc");
			return;
		}
		Path src = Paths.get(args[0]);
		Path dst = 1 < args.length ? Paths.get(args[1]) : Paths.get(System.getProperty("user.dir"), "AdHoc");

		List<File> files = new ArrayList<>();
		if (Files.isDirectory(src)) {
			File[] all = src.toFile().listFiles((d, n) -> n.endsWith(".avsc") || n.endsWith(".avpr"));
			if (all != null) files.addAll(Arrays.asList(all));
		} else files.add(src.toFile());
		if (files.isEmpty()) {
			System.err.println("No .avsc / .avpr files found in `" + src.toAbsolutePath() + "`.");
			System.exit(1);
		}
		files.sort(null);
		Files.createDirectories(dst);

		int failed = 0;
		for (File f : files)
			try {
				Converter c = new Converter(f);
				String base = f.getName().lastIndexOf('.') < 0 ? f.getName() : f.getName().substring(0, f.getName().lastIndexOf('.'));
				Path out = dst.resolve(base + ".cs"); // output named after the input file, whatever the project interface is called
				Files.write(out, c.emit().getBytes(StandardCharsets.UTF_8));
				System.out.printf("%-36s -> %s  (%d packs, %d enums, %d rpc)%n", f.getName(), out, c.packCount, c.enumCount, c.rpcCount);
			} catch (Exception e) {
				failed++;
				System.err.println("FAILED " + f + ": " + e);
				e.printStackTrace();
			}
		if (0 < failed) System.exit(2);
	}

	// ═══════════════════════════════════════════ model ═══════════════════════════════════════════

	/** A parsed Avro type expression. */
	static final class T {
		String kind;        // prim | named | array | map | union
		String prim;        // null boolean int long float double bytes string
		String ref;         // named: full name (resolved)
		T items, values;
		List<T> alts;
		String logical;     // logicalType, if any
		Long precision, scale;

		static T prim(String p) { T t = new T(); t.kind = "prim"; t.prim = p; return t; }
	}

	static final class Field {
		String name, doc, order;
		T type;
		Object dflt;
		boolean hasDefault;
		List<String> aliases = new ArrayList<>();
	}

	/** A named Avro type: record, error, enum or fixed. */
	static final class Named {
		String kind, fullName, simpleName, doc, cs, logical;
		boolean referenced; // used as a field type somewhere (otherwise an enum must be pushed to the hosts explicitly)
		List<Field> fields = new ArrayList<>();   // record / error
		List<String> symbols = new ArrayList<>(); // enum
		String enumDefault;
		int size;                                 // fixed
		List<String> aliases = new ArrayList<>();
	}

	static final class Message {
		String name, doc;
		List<Field> request = new ArrayList<>();
		T response;
		List<String> errors = new ArrayList<>();
		boolean oneWay;
	}

	// ═══════════════════════════════════════════ converter (one input file) ═══════════════════════════════════════════

	static final class Converter {
		final String fileName, project;
		final Path path; // the input: the header of the description links the original of it
		final LinkedHashMap<String, Named> named = new LinkedHashMap<>(); // full name → definition
		final List<String> roots = new ArrayList<>();                     // full names of top-level records (.avsc)
		final List<Message> messages = new ArrayList<>();                 // .avpr
		String protocolName, protocolDoc, protocolNs;

		final Set<String> taken = new HashSet<>();                        // identifiers used at project scope
		final Set<String> timeAliases = new java.util.LinkedHashSet<>();  // DateTimeDef / Duration aliases actually used
		final StringBuilder unionPacks = new StringBuilder();
		final StringBuilder rpcPacks = new StringBuilder();
		final LinkedHashMap<String, Integer> dashboard = new LinkedHashMap<>();
		int packCount, enumCount, rpcCount;

		Converter(File file) throws Exception {
			fileName = file.getName();
			path = file.toPath();
			Object json = Json.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
			if (json instanceof Map && Json.object(json).containsKey("protocol")) readProtocol(Json.object(json));
			else {
				T root = parseType(json, "");
				collectRoots(root);
			}
			// The project interface is named after the file. C# forbids a nested type named like its enclosing
			// type, so when a schema type carries that very name the project gets a `Schema` suffix instead.
			int dot = fileName.lastIndexOf('.');
			String p = ident(dot < 0 ? fileName : fileName.substring(0, dot));
			for (Named n : named.values()) if (ident(n.simpleName).equals(p)) { p = p + "Schema"; break; }
			project = p;
			taken.addAll(Arrays.asList(project, "Client", "Server", "Connection", "Exchange", "OneWay", "NoArg"));
			// names of org.unirail.Meta members and System types a pack must not shadow (inside the project a pack of that name would hide them)
			taken.addAll(Arrays.asList("Binary", "Map", "Set", "Duration", "File", "Stream", "Host", "Actor", "End", "Close", "Empty",
					"Modify", "Connects", "SwapHosts", "HeaderFor", "FieldsInjectInto", "DateTimeDef", "TimeSpanDef", "longJS", "ulongJS",
					"InTS", "InJAVA", "InCS", "InCPP", "InGO", "InRS", "All", "IfSendingFrom", "Offline", "VirtuallyConnects",
					"Zstd", "ChaCha20", "DateTime", "TimeSpan", "Attribute", "Math",
					"DaysSinceEpoch", "TimeOfDayMillis", "TimeOfDayMicros")); // the time aliases emitted below
			assignNames();
		}

		// ───────────────────────────── parsing ─────────────────────────────

		void readProtocol(Map<String, Object> p) {
			protocolNs = string(p.get("namespace"), "");
			protocolName = string(p.get("protocol"), project);
			protocolDoc = string(p.get("doc"), null);
			if (p.get("types") != null) for (Object t : Json.array(p.get("types"))) parseType(t, protocolNs);
			if (p.get("messages") != null)
				for (Map.Entry<String, Object> e : Json.object(p.get("messages")).entrySet()) {
					Map<String, Object> m = Json.object(e.getValue());
					Message msg = new Message();
					msg.name = e.getKey();
					msg.doc = string(m.get("doc"), null);
					if (m.get("request") != null) for (Object f : Json.array(m.get("request"))) msg.request.add(parseField(Json.object(f), protocolNs));
					msg.response = m.get("response") == null ? T.prim("null") : parseType(m.get("response"), protocolNs);
					if (m.get("errors") != null) for (Object er : Json.array(m.get("errors"))) msg.errors.add(fullName(string(er, ""), protocolNs));
					msg.oneWay = Boolean.TRUE.equals(m.get("one-way"));
					messages.add(msg);
				}
		}

		/** Top-level .avsc: the root record, or the records of a root union, are the transmittable packs. */
		void collectRoots(T t) {
			if (t.kind.equals("named")) {
				Named n = named.get(t.ref);
				if (n != null && (n.kind.equals("record") || n.kind.equals("error"))) roots.add(t.ref);
			} else if (t.kind.equals("union")) for (T a : t.alts) collectRoots(a);
		}

		static final Set<String> PRIMS = new HashSet<>(Arrays.asList("null", "boolean", "int", "long", "float", "double", "bytes", "string"));

		/** Parses a schema JSON value; registers every named type it contains. `ns` is the enclosing namespace. */
		T parseType(Object schema, String ns) {
			if (schema instanceof String) {
				String s = (String) schema;
				if (PRIMS.contains(s)) return T.prim(s);
				T t = new T();
				t.kind = "named";
				t.ref = fullName(s, ns);
				return t;
			}
			if (schema instanceof List) {
				T t = new T();
				t.kind = "union";
				t.alts = new ArrayList<>();
				for (Object a : Json.array(schema)) t.alts.add(parseType(a, ns));
				return t;
			}
			Map<String, Object> o = Json.object(schema);
			String type = string(o.get("type"), "");
			String logical = string(o.get("logicalType"), null);
			switch (type) {
				case "array": {
					T t = new T();
					t.kind = "array";
					t.items = parseType(o.get("items"), ns);
					return t;
				}
				case "map": {
					T t = new T();
					t.kind = "map";
					t.values = parseType(o.get("values"), ns);
					return t;
				}
				case "record":
				case "error":
				case "enum":
				case "fixed": {
					Named n = new Named();
					n.kind = type;
					String name = string(o.get("name"), "");
					String tns = string(o.get("namespace"), null);
					if (name.contains(".")) {
						n.fullName = name;
						n.simpleName = name.substring(name.lastIndexOf('.') + 1);
						tns = name.substring(0, name.lastIndexOf('.'));
					} else {
						if (tns == null) tns = ns;
						n.fullName = tns.isEmpty() ? name : tns + "." + name;
						n.simpleName = name;
					}
					n.doc = string(o.get("doc"), null);
					n.logical = logical;
					if (o.get("aliases") != null) for (Object a : Json.array(o.get("aliases"))) n.aliases.add(string(a, ""));
					named.putIfAbsent(n.fullName, n); // register before the fields: records may be recursive
					if (type.equals("enum")) {
						for (Object s : Json.array(o.get("symbols"))) n.symbols.add(string(s, ""));
						n.enumDefault = string(o.get("default"), null);
					} else if (type.equals("fixed")) n.size = ((Number) o.get("size")).intValue();
					else if (o.get("fields") != null) for (Object f : Json.array(o.get("fields"))) n.fields.add(parseField(Json.object(f), tns));
					T t = new T();
					t.kind = "named";
					t.ref = n.fullName;
					return t;
				}
				default: {
					// {"type": "long", "logicalType": "timestamp-millis"} or {"type": "string", "avro.java.string": …}
					T t = PRIMS.contains(type) ? T.prim(type) : parseType(type, ns);
					if (logical != null) {
						t.logical = logical;
						if (o.get("precision") instanceof Number) t.precision = ((Number) o.get("precision")).longValue();
						if (o.get("scale") instanceof Number) t.scale = ((Number) o.get("scale")).longValue();
					}
					return t;
				}
			}
		}

		Field parseField(Map<String, Object> f, String ns) {
			Field fld = new Field();
			fld.name = string(f.get("name"), "field");
			fld.doc = string(f.get("doc"), null);
			fld.order = string(f.get("order"), null);
			fld.type = parseType(f.get("type"), ns);
			if (f.containsKey("default")) {
				fld.hasDefault = true;
				fld.dflt = f.get("default");
			}
			if (f.get("aliases") != null) for (Object a : Json.array(f.get("aliases"))) fld.aliases.add(string(a, ""));
			return fld;
		}

		static String fullName(String name, String ns) { return name.contains(".") || ns.isEmpty() ? name : ns + "." + name; }

		static String string(Object o, String dflt) { return o == null ? dflt : o.toString(); }

		/** A referenced name → definition: exact full name, then the simple name if it is unique in the file. */
		Named resolve(String ref) {
			Named n = named.get(ref);
			if (n != null) return n;
			String simple = ref.substring(ref.lastIndexOf('.') + 1);
			Named found = null;
			for (Named c : named.values())
				if (c.simpleName.equals(simple)) {
					if (found != null) return null; // ambiguous
					found = c;
				}
			return found;
		}

		/** C# identifiers: the simple name when unique in the file, else the full name flattened. */
		void assignNames() {
			Map<String, Integer> bySimple = new HashMap<>();
			for (Named n : named.values()) bySimple.merge(n.simpleName, 1, Integer::sum);
			for (Named n : named.values())
				n.cs = AdHocWriter.unique(bySimple.get(n.simpleName) == 1 ? n.simpleName : n.fullName, taken);
		}

		// ───────────────────────────── emission ─────────────────────────────

		String emit() {
			StringBuilder body = new StringBuilder();

			// named types first: emitting their fields discovers the union packs and collection aliases
			for (Named n : named.values())
				switch (n.kind) {
					case "record":
					case "error": record(body, n); break;
					case "enum": enumeration(body, n); break;
					case "fixed": fixed(body, n); break;
					default: break;
				}

			StringBuilder conn = new StringBuilder();
			List<String> oneWay = new ArrayList<>();
			for (Message m : messages) rpc(conn, m, oneWay);

			StringBuilder sb = new StringBuilder(1 << 16);
			AdHocWriter.fileHeader(sb, "Avro2AdHoc", fileName, Originals.of(List.of(path)),
					protocolName != null ? "Avro protocol: " + protocolName + (protocolNs.isEmpty() ? "" : " (namespace " + protocolNs + ")") : "Avro schema");
			// enums / constants containers no field refers to would reach no host: push them to every host explicitly
			List<String> unreferenced = new ArrayList<>();
			// the base list of the interface is resolved outside its scope: nested types need the project prefix
			for (Named n : named.values()) if (n.kind.equals("enum") && !n.referenced) unreferenced.add(project + "." + n.cs);

			boolean nothingToSend = roots.isEmpty() && conn.length() == 0 && oneWay.isEmpty();
			if (nothingToSend) { // nothing to transmit: the README's NoArg sentinel pack gives the connection a state
				dashboard.put("NoArg", null);
				body.append('\n').append(I2).append("// The schema declares no record and no message, so the connection would have nothing to transmit:\n");
				body.append(I2).append("// the empty sentinel pack recommended by the AdHoc README carries the enums to both hosts.\n");
				body.append(I2).append("class NoArg { }\n");
			}

			sb.append("namespace org.avro {\n");
			AdHocWriter.dashboard(sb, I1, dashboard);
			sb.append(I1).append("public interface ").append(project);
			if (!unreferenced.isEmpty()) {
				sb.append(" :\n").append(I2).append("// enums no field references: included in every host anyway\n");
				sb.append(I2).append("_<").append(AdHocWriter.tuple(unreferenced)).append(">");
			}
			sb.append(" {\n");
			if (protocolDoc != null) {
				sb.append(I2).append("// ═════════════════════════ protocol ").append(protocolName).append(" ═════════════════════════\n");
				doc(sb, I2, protocolDoc);
				sb.append('\n');
			}
			sb.append(body);

			if (0 < unionPacks.length()) {
				sb.append('\n').append(I2).append("// ═════════════════════════ union packs: one optional field per alternative ═════════════════════════\n");
				sb.append(unionPacks);
			}
			if (0 < rpcPacks.length()) {
				sb.append('\n').append(I2).append("// ═════════════════════════ RPC request / response packs ═════════════════════════\n");
				sb.append(rpcPacks);
			}
			timeAliasDeclarations(sb);

			sb.append('\n').append(I2).append("// ═════════════════════════ collection limits ═════════════════════════\n\n");
			sb.append(I2).append("// Avro strings, bytes, arrays and maps are unbounded, AdHoc caps every collection at 255 by default.\n");
			sb.append(I2).append("// This raises the file-wide cap; override a single field with [D(N)] / [D(+N)] where the real bound is known.\n");
			sb.append(I2).append("enum _DefaultMaxLengthOf {\n");
			sb.append(I3).append("Arrays  = 65_535,\n");
			sb.append(I3).append("Maps    = 65_535,\n");
			sb.append(I3).append("Sets    = 65_535,\n");
			sb.append(I3).append("Strings = 65_535,\n");
			sb.append(I2).append("}\n\n");

			sb.append(I2).append("// ═════════════════════════ demo topology ═════════════════════════\n\n");
			AdHocWriter.host(sb, I2, "Client", messages.isEmpty() ? "either side may send any top-level record" : "calls the protocol messages");
			AdHocWriter.host(sb, I2, "Server", messages.isEmpty() ? null : "answers them");
			AdHocWriter.connectionOpen(sb, I2, "Connection", "Client", "Server");
			if (nothingToSend) {
				sb.append(I3).append("// nothing real to transmit: the sentinel keeps the connection valid\n");
				AdHocWriter.statePacks(sb, I3, "_____lr_____", "Exchange", Arrays.asList("NoArg"));
			} else if (!roots.isEmpty()) {
				List<String> packs = new ArrayList<>();
				for (String r : roots) packs.add(named.get(r).cs);
				sb.append(I3).append("// Every top-level record of the schema, in both directions; nested records travel as sub-packs.\n");
				AdHocWriter.statePacks(sb, I3, "_____lr_____", "Exchange", packs);
			}
			sb.append(conn);
			if (!oneWay.isEmpty()) {
				sb.append(I3).append("// one-way messages: fire-and-forget from the client\n");
				AdHocWriter.statePacks(sb, I3, "l____________", "OneWay", oneWay);
			}
			sb.append(I2).append("}\n");

			attributes(sb);
			sb.append(I1).append("}\n}\n");
			return sb.toString();
		}

		void record(StringBuilder sb, Named n) {
			packCount++;
			dashboard.put(n.cs, null);
			sb.append('\n');
			doc(sb, I2, docOf(n));
			sb.append(I2).append("class ").append(n.cs).append(" {\n");
			Set<String> fieldNames = new HashSet<>();
			fieldNames.add(n.cs); // a pack cannot contain a member with its own name
			for (Field f : n.fields) sb.append(field(f, n.cs, fieldNames, I3));
			sb.append(I2).append("}\n");
		}

		String docOf(Named n) {
			StringBuilder d = new StringBuilder();
			if (n.doc != null) d.append(n.doc).append('\n');
			d.append("Avro ").append(n.kind).append(": ").append(n.fullName);
			if (!n.aliases.isEmpty()) d.append(", aliases: ").append(String.join(", ", n.aliases));
			if (n.logical != null) d.append(", logicalType: ").append(n.logical);
			return d.toString();
		}

		/** One field line (with its doc). `ctx` names the owner for synthesized union packs. */
		String field(Field f, String ctx, Set<String> fieldNames, String indent) {
			StringBuilder sb = new StringBuilder();
			Ref r = cs(f.type, ctx + "_" + f.name, false);
			doc(sb, indent, f.doc);
			if (r.type == null) { // Avro `null` type: carries nothing
				sb.append(indent).append("// ").append(f.name).append(": Avro type \"null\" carries no data, no field emitted\n");
				return sb.toString();
			}
			List<String> extra = new ArrayList<>(); // a custom attribute must not repeat on one field
			if (f.hasDefault) extra.add(f.dflt == null ? "DefaultNull" : "Default(" + defaultLiteral(f.dflt) + ")");
			if (f.order != null && !f.order.equals("ascending")) extra.add("Order(" + str(f.order) + ")");
			if (!f.aliases.isEmpty()) extra.add("Aliases(" + str(String.join(",", f.aliases)) + ")");
			String name = AdHocWriter.unique(f.name, fieldNames);
			sb.append(indent).append(r.prefix(extra)).append(r.type).append(' ').append(name).append(";").append(physics(f, r)).append('\n');
			return sb.toString();
		}

		/** A default value as an attribute argument: JSON scalars keep their type, anything else is JSON text. */
		static String defaultLiteral(Object v) {
			if (v instanceof String) return str((String) v);
			if (v instanceof Long || v instanceof Boolean) return v.toString();
			if (v instanceof Double) return AdHocWriter.num(v.toString());
			return str(toJson(v));
		}

		static String toJson(Object v) {
			if (v == null) return "null";
			if (v instanceof String) return "\"" + ((String) v).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
			if (v instanceof Map) {
				StringBuilder sb = new StringBuilder("{");
				boolean first = true;
				for (Map.Entry<String, Object> e : Json.object(v).entrySet()) {
					if (!first) sb.append(',');
					first = false;
					sb.append(toJson(e.getKey())).append(':').append(toJson(e.getValue()));
				}
				return sb.append('}').toString();
			}
			if (v instanceof List) {
				StringBuilder sb = new StringBuilder("[");
				boolean first = true;
				for (Object o : Json.array(v)) {
					if (!first) sb.append(',');
					first = false;
					sb.append(toJson(o));
				}
				return sb.append(']').toString();
			}
			return v.toString();
		}

		void enumeration(StringBuilder sb, Named n) {
			sb.append('\n');
			StringBuilder d = new StringBuilder(docOf(n));
			if (n.enumDefault != null) d.append(", default: ").append(n.enumDefault);
			doc(sb, I2, d.toString());
			Set<String> used = new HashSet<>();
			if (n.symbols.size() < 2) {
				sb.append(I2).append("// AdHoc rejects enums with fewer than two members: kept as a constants container.\n");
				sb.append(I2).append("public struct ").append(n.cs).append(" {\n");
				int i = 0;
				for (String s : n.symbols) sb.append(I3).append("public const int ").append(AdHocWriter.unique(s, used)).append(" = ").append(i++).append(";\n");
				if (n.symbols.isEmpty()) sb.append(I3).append("public const int EMPTY = 0; // the Avro enum declares no symbols\n");
				sb.append(I2).append("}\n");
				return;
			}
			enumCount++;
			sb.append(I2).append("enum ").append(n.cs).append(" {\n");
			for (String s : n.symbols) sb.append(I3).append(AdHocWriter.unique(s, used)).append(",\n");
			sb.append(I2).append("}\n");
		}

		void fixed(StringBuilder sb, Named n) {
			sb.append('\n');
			doc(sb, I2, docOf(n));
			// `fixed` with a logical type is always `decimal` or `duration` in practice: AdHoc has no concept for
			// either, so the bytes stay raw and the logical name is carried as metadata.
			String attr = n.logical == null ? "" : "LogicalType(" + str(n.logical) + "), ";
			sb.append(I2).append("class ").append(n.cs).append(" { [").append(attr).append("D(").append(n.size).append(")] Binary[] TYPEDEF; }\n");
		}

		/** A C# type reference plus the attributes it carries. `type == null` means "carries nothing". */
		static final class Ref {
			String type;
			boolean value;          // value type (may take `?`)
			boolean collection;     // needs a TYPEDEF alias when nested in another collection
			List<String> attrs = new ArrayList<>();    // plain attributes: `[X]`, `[LogicalType(…)]`
			List<String> valAttrs = new ArrayList<>(); // attributes of a Map value: `[Val: X]`

			boolean hasAttrs() { return !attrs.isEmpty() || !valAttrs.isEmpty(); }

			/** The attribute prefix of a field declaration; targeted groups get their own bracket. */
			String prefix(List<String> extra) {
				List<String> plain = new ArrayList<>(new java.util.LinkedHashSet<>(attrs));
				if (extra != null) plain.addAll(extra);
				StringBuilder sb = new StringBuilder();
				if (!plain.isEmpty()) sb.append('[').append(String.join(", ", plain)).append("] ");
				if (!valAttrs.isEmpty()) sb.append("[Val: ").append(String.join(", ", new java.util.LinkedHashSet<>(valAttrs))).append("] ");
				return sb.toString();
			}

			String prefix() { return prefix(null); }
		}

		/**
		 * An Avro logical type that AdHoc models natively. Returns the AdHoc type, or null when AdHoc has no
		 * concept for it (then the base type plus a `[LogicalType]` attribute is emitted instead).
		 *
		 * <p>Avro stores a date as days since the epoch and a time as milli/microseconds after midnight; AdHoc
		 * expresses both as first-class time types, so the raw integer never reaches the generated API.
		 */
		String timeType(String logical) {
			switch (logical) {
				case "timestamp-millis":
				case "timestamp-micros":
				case "timestamp-nanos":
				case "local-timestamp-millis":
				case "local-timestamp-micros":
				case "local-timestamp-nanos":
					return "DateTime";                       // wall-clock instant: AdHoc's own DateTime
				case "date":
					timeAliases.add("DaysSinceEpoch");
					return "DaysSinceEpoch";                 // DateTimeDef alias, one-day precision
				case "time-millis":
					timeAliases.add("TimeOfDayMillis");
					return "TimeOfDayMillis";                // Duration alias: elapsed time since midnight
				case "time-micros":
					timeAliases.add("TimeOfDayMicros");
					return "TimeOfDayMicros";
				default:
					return null;                             // decimal, uuid, duration, big-decimal, unknown
			}
		}

		/** Maps an Avro type to its C# spelling. `inCollection`: the type is an item/value of a collection. */
		Ref cs(T t, String ctx, boolean inCollection) {
			Ref r = new Ref();
			// a logical type AdHoc models natively replaces the base type outright
			if (t.logical != null) {
				String nativeType = timeType(t.logical);
				if (nativeType != null) {
					r.type = nativeType;
					r.value = nativeType.equals("DateTime"); // the aliases are packs, i.e. reference types
					return r;
				}
			}
			switch (t.kind) {
				case "prim":
					switch (t.prim) {
						case "null": r.type = null; return r;
						case "boolean": r.type = "bool"; r.value = true; break;
						// Avro encodes int and long as ZIGZAG VARINT by specification: values cluster around zero
						// and small magnitudes cost one byte. [X] is exactly that distribution on the AdHoc wire.
						case "int": r.type = "int"; r.value = true; r.attrs.add("X"); break;
						case "long": r.type = "long"; r.value = true; r.attrs.add("X"); break;
						case "float": r.type = "float"; r.value = true; break;   // Avro: fixed 4 bytes, no varint
						case "double": r.type = "double"; r.value = true; break; // Avro: fixed 8 bytes, no varint
						case "string": r.type = "string"; break;
						case "bytes": r.type = "Binary[,,]"; r.collection = true; break;
						default: throw new IllegalStateException("unknown primitive " + t.prim);
					}
					break;
				case "named": {
					Named n = resolve(t.ref);
					if (n == null) {
						System.err.println("WARNING " + fileName + ": unresolved type `" + t.ref + "`, emitted as Binary[,,]");
						r.type = "Binary[,,]";
						r.collection = true;
						r.attrs.add("Unresolved(" + str(t.ref) + ")");
						break;
					}
					n.referenced = true;
					r.type = n.cs;
					r.value = n.kind.equals("enum") && 2 <= n.symbols.size();
					if (n.kind.equals("enum") && n.symbols.size() < 2) { // constants container, not a type
						r.type = "int";
						r.value = true;
						r.attrs.add("EnumRef(" + str(n.cs) + ")");
					}
					break;
				}
				case "array": {
					Ref item = cs(t.items, ctx + "_item", true);
					if (item.type == null) { r.type = null; return r; }
					// a collection item nests natively: `string[,,][,,]` is a list of lists, `Map<…>[,,]` a list of maps
					r.type = item.type + "[,,]";
					r.collection = true;
					r.attrs.addAll(item.attrs);      // an attribute on an array applies to its elements
					r.valAttrs.addAll(item.valAttrs);
					break;
				}
				case "map": {
					Ref val = cs(t.values, ctx + "_value", true);
					if (val.type == null) { r.type = null; return r; }
					// a Map as the value of a Map travels in a one-field sub-pack
					if (val.type.startsWith("Map<")) val.type = wrapPack(val, ctx + "_value");
					r.type = "Map<string, " + val.type + ">";
					r.collection = true;
					// the Avro map key is always a plain string; attributes of the value need the [Val:] target
					r.valAttrs.addAll(val.attrs);
					break;
				}
				case "union": {
					List<T> nonNull = new ArrayList<>();
					boolean hasNull = false;
					for (T a : t.alts)
						if (a.kind.equals("prim") && a.prim.equals("null")) hasNull = true;
						else nonNull.add(a);
					if (nonNull.isEmpty()) { r.type = null; return r; }
					if (nonNull.size() == 1) {
						r = cs(nonNull.get(0), ctx, inCollection);
						if (r.type == null) return r;
						// optional: `?` on value types always; on reference types only inside a collection
						// (a reference-typed field is optional by itself)
						if (hasNull && (r.value || inCollection && !r.collection)) r.type += "?";
						return r;
					}
					r.type = unionPack(nonNull, ctx);
					break;
				}
				default: throw new IllegalStateException("unknown type kind " + t.kind);
			}
			// a logical type AdHoc has no concept for (decimal, uuid, duration, …) stays metadata on the base type
			if (t.logical != null)
				r.attrs.add("LogicalType(" + str(t.logical) + (t.precision != null ? ", " + t.precision + ", " + (t.scale == null ? 0 : t.scale) : "") + ")");
			return r;
		}

		/**
		 * The physics question `[X]` raises, asked at the field where it belongs.
		 *
		 * <p>Avro's own choice of zigzag varint for `int` / `long` is a statement that the values cluster around
		 * zero, and that is why `[X]` is emitted. But varint wins only while the typical distance from the base
		 * stays under about two million, and past 268 435 455 it always loses a byte. Avro cannot say which of its
		 * `long` fields is a millisecond timestamp and which is a small delta, so where the field's name or doc
		 * says the values are systematically large - or that they sit at a floor - the converter names the doubt
		 * rather than dropping it. No attribute is invented: the decision stays with the developer.
		 *
		 * @return the comment to append to the field line, or "" when the source says nothing about magnitude.
		 */
		static String physics(Field f, Ref r) {
			if (!r.attrs.contains("X") && !r.valAttrs.contains("X")) return ""; // only varint-carrying integers
			String hay = (f.name + " " + (f.doc == null ? "" : f.doc)).toLowerCase();

			// systematically large: every value sits far from zero, so the varint spells out the full magnitude
			if (hay.matches(".*\\b(timestamp|epoch|unix|millis|micros|nanos|msec|usec)\\w*\\b.*")
			    || hay.matches(".*\\b\\w*time\\b.*") && !hay.contains("timeout") && !hay.contains("elapsed")
			    || hay.matches(".*\\b(hash|crc|checksum|digest|fingerprint|nonce|random|seed)\\w*\\b.*")
			    || hay.matches(".*\\b(latitude|longitude)\\w*\\b.*") || hay.matches(".*\\b(lat|lon|lng)\\b.*"))
				return " // physics: values look systematically large (a timestamp, hash or scaled coordinate) and"
				       + " varint loses past 268 435 455 - [X] likely costs a byte here, measure and drop it if so";

			// a non-negative counter: zigzag spends a bit on a sign that never appears, [A(0)] does not
			if (hay.matches(".*\\b(count|counter|num|number|length|size|index|offset|sequence|seq|total|bytes)\\w*\\b.*"))
				return " // physics: a non-negative counter clustering at its floor → consider [A(0)] instead of [X]";

			return "";
		}

		/** A one-field pack wrapping a Map that is the value of another Map. */
		String wrapPack(Ref inner, String ctx) {
			String name = AdHocWriter.unique(ctx + "_map", taken);
			packCount++;
			dashboard.put(name, null);
			unionPacks.append('\n').append(I2).append("/** Wrapper: the inner Avro map (the value of a map) travels as a sub-pack. */\n");
			unionPacks.append(I2).append("class ").append(name).append(" { ").append(inner.prefix()).append(inner.type).append(" value; }\n");
			inner.attrs.clear();
			inner.valAttrs.clear();
			return name;
		}

		/** A union of several non-null types → a pack with one optional field per alternative. */
		String unionPack(List<T> alts, String ctx) {
			String name = AdHocWriter.unique(ctx + "_union", taken);
			packCount++;
			dashboard.put(name, null);
			StringBuilder sb = new StringBuilder();
			sb.append('\n').append(I2).append("/** Avro union: exactly one of the fields is set. */\n");
			sb.append(I2).append("class ").append(name).append(" {\n");
			Set<String> fieldNames = new HashSet<>();
			fieldNames.add(name);
			for (T a : alts) {
				Ref r = cs(a, ctx + "_alt", false);
				if (r.type == null) continue;
				String fname = AdHocWriter.unique(altName(a), fieldNames);
				String type = r.value ? r.type + "?" : r.type;
				sb.append(I3).append(r.prefix()).append(type).append(' ').append(fname).append(";\n");
			}
			sb.append(I2).append("}\n");
			unionPacks.append(sb);
			return name;
		}

		String altName(T a) {
			switch (a.kind) {
				case "prim": return a.prim;
				case "named": { Named n = resolve(a.ref); return n == null ? a.ref : n.simpleName; }
				case "array": return "array_of_" + altName(a.items);
				case "map": return "map_of_" + altName(a.values);
				default: return "alt";
			}
		}

		// ───────────────────────────── protocol messages ─────────────────────────────

		void rpc(StringBuilder conn, Message m, List<String> oneWay) {
			String base = AdHocWriter.unique(m.name, taken);
			String req = AdHocWriter.unique(base + "_Request", taken);
			packCount++;
			dashboard.put(req, null);
			rpcPacks.append('\n');
			doc(rpcPacks, I2, (m.doc == null ? "" : m.doc + "\n") + "Request of message " + m.name + (m.request.isEmpty() ? " (no arguments)" : ""));
			rpcPacks.append(I2).append("class ").append(req).append(" {\n");
			Set<String> fieldNames = new HashSet<>();
			fieldNames.add(req);
			for (Field f : m.request) rpcPacks.append(field(f, req, fieldNames, I3));
			rpcPacks.append(I2).append("}\n");

			if (m.oneWay) {
				oneWay.add(req);
				return;
			}
			rpcCount++;
			List<String> responses = new ArrayList<>();
			Ref r = cs(m.response, base + "_response", false);
			if (r.type != null && m.response.kind.equals("named") && !r.value && !r.hasAttrs()) responses.add(r.type); // a record: used directly
			else {
				String resp = AdHocWriter.unique(base + "_Response", taken);
				packCount++;
				dashboard.put(resp, null);
				rpcPacks.append('\n');
				doc(rpcPacks, I2, "Response of message " + m.name + (r.type == null ? " (void: an empty acknowledgement)" : ""));
				rpcPacks.append(I2).append("class ").append(resp).append(" {");
				if (r.type != null) rpcPacks.append(' ').append(r.prefix()).append(r.type).append(" value; ");
				else rpcPacks.append(' ');
				rpcPacks.append("}\n");
				responses.add(resp);
			}
			for (String e : m.errors) {
				Named n = resolve(e);
				if (n != null && (n.kind.equals("error") || n.kind.equals("record"))) responses.add(n.cs);
				else System.err.println("WARNING " + fileName + ": error type `" + e + "` of message " + m.name + " is not a record, skipped");
			}
			if (m.doc != null) conn.append(I3).append("// ").append(m.doc.replace('\n', ' ')).append('\n');
			conn.append(I3).append("(L____________, ").append(String.join(", ", responses)).append(") ").append(base).append("(").append(req).append(" req);\n");
		}

		// ───────────────────────────── time-type aliases ─────────────────────────────

		/** The `DateTimeDef` / `Duration` aliases the Avro logical types of this file resolved to. */
		void timeAliasDeclarations(StringBuilder sb) {
			if (timeAliases.isEmpty()) return;
			sb.append('\n').append(I2).append("// ═════════════════════════ time types ═════════════════════════\n\n");
			sb.append(I2).append("// Avro stores these as plain integers; AdHoc has first-class time types, so the raw number never\n");
			sb.append(I2).append("// reaches the generated API and the wire carries only the bits the declared range needs.\n");
			if (timeAliases.contains("DaysSinceEpoch")) {
				sb.append('\n').append(I2).append("/** Avro logical type `date`: days since 1970-01-01, no time of day. */\n");
				sb.append(I2).append("class DaysSinceEpoch : DateTimeDef {\n");
				sb.append(I3).append("public DateTime min       => new DateTime(1970, 1, 1);\n");
				sb.append(I3).append("public TimeSpan precision => TimeSpan.FromDays(1);\n");
				sb.append(I2).append("}\n");
			}
			if (timeAliases.contains("TimeOfDayMillis")) {
				sb.append('\n').append(I2).append("/** Avro logical type `time-millis`: milliseconds after midnight, 0 .. 86 399 999. */\n");
				sb.append(I2).append("class TimeOfDayMillis : Duration {\n");
				sb.append(I3).append("public long     max       => 86_400_000;\n");
				sb.append(I3).append("public TimeSpan precision => TimeSpan.FromMilliseconds(1);\n");
				sb.append(I2).append("}\n");
			}
			if (timeAliases.contains("TimeOfDayMicros")) {
				sb.append('\n').append(I2).append("/** Avro logical type `time-micros`: microseconds after midnight. AdHoc's Duration precision floor is\n");
				sb.append(I2).append("1 ms, so sub-millisecond resolution is dropped here; widen by hand if the payload needs it. */\n");
				sb.append(I2).append("class TimeOfDayMicros : Duration {\n");
				sb.append(I3).append("public long     max       => 86_400_000;\n");
				sb.append(I3).append("public TimeSpan precision => TimeSpan.FromMilliseconds(1);\n");
				sb.append(I2).append("}\n");
			}
		}

		// ───────────────────────────── custom attribute declarations ─────────────────────────────

		void attributes(StringBuilder sb) {
			sb.append('\n').append(I2).append("// ═════════════════════════ Avro metadata attributes ═════════════════════════\n\n");
			sb.append(I2).append("// AdHoc custom attributes: the generator carries each one into the generated code as constants attached\n");
			sb.append(I2).append("// to the field. They live inside the project interface; the branches above list packs explicitly, so the\n");
			sb.append(I2).append("// attribute classes never become packs.\n\n");
			AdHocWriter.attribute(sb, I2, "Default", "Avro default value: JSON scalars keep their type, complex defaults are JSON text.", "string value", "long value", "double value", "bool value");
			AdHocWriter.attribute(sb, I2, "DefaultNull", "Avro default value null.");
			AdHocWriter.attribute(sb, I2, "LogicalType", "Avro logical type of the field (decimal carries precision and scale).", "string name", "string name, long precision, long scale");
			AdHocWriter.attribute(sb, I2, "Order", "Avro sort order when it is not the default ascending.", "string order");
			AdHocWriter.attribute(sb, I2, "Aliases", "Avro field aliases, comma separated.", "string aliases");
			AdHocWriter.attribute(sb, I2, "EnumRef", "The field holds a value of an Avro enum with fewer than two symbols (emitted as a constants container).", "string container");
			AdHocWriter.attribute(sb, I2, "Unresolved", "The Avro type name could not be resolved in this file; the payload is kept as raw bytes.", "string avroType");
		}
	}
}
