package org.unirail.adhoc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Where an input comes from. {@code fetch-samples.sh} writes {@code sources.txt} next to what it downloads, one line
 * per file: {@code <path relative to that folder> <page of the original>}; a path that ends with {@code /} stands for
 * every file under that folder, the rest of the path is added to the page. The header of a description links the
 * originals it was made from ({@link AdHocWriter#fileHeader(StringBuilder, String, String, List, String...)}). An
 * input with no such list in a folder above it gets no link.
 */
public final class Originals {
	public static final String LIST = "sources.txt";

	private static final Map<Path, List<String[]>> lists = new HashMap<>(); // a folder -> its list, or null: it has none

	private Originals() { }

	/** The page of the original of a file or a folder, or null. The nearest list above it decides. */
	public static String of(Path input) {
		if (input == null || !Files.exists(input)) return null;
		input = input.toAbsolutePath().normalize();
		for (Path dir = input.getParent(); dir != null; dir = dir.getParent()) {
			List<String[]> list = read(dir);
			if (list == null) continue;
			String path = dir.relativize(input).toString().replace('\\', '/') + (Files.isDirectory(input) ? "/" : "");
			String page = null;
			int best = -1;
			for (String[] e : list)
				if (best < e[0].length() && (e[0].equals(path) || e[0].endsWith("/") && path.startsWith(e[0]))) {
					page = e[0].equals(path) ? e[1] : e[1] + path.substring(e[0].length());
					best = e[0].length();
				}
			return page;
		}
		return null;
	}

	/** The pages of the originals of several inputs, in their order, each once; inputs without one are left out. */
	public static List<String> of(Collection<Path> inputs) {
		LinkedHashSet<String> pages = new LinkedHashSet<>();
		for (Path p : inputs) {
			String page = of(p);
			if (page != null) pages.add(page);
		}
		return new ArrayList<>(pages);
	}

	private static List<String[]> read(Path dir) {
		if (lists.containsKey(dir)) return lists.get(dir);
		List<String[]> list = null;
		Path file = dir.resolve(LIST);
		if (Files.isRegularFile(file)) {
			list = new ArrayList<>();
			try {
				for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					String[] words = line.trim().split("\\s+");
					if (words.length == 2 && !words[0].startsWith("#") && (words[1].startsWith("https://") || words[1].startsWith("http://")))
						list.add(new String[]{words[0].replace('\\', '/'), words[1]});
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		lists.put(dir, list);
		return list;
	}
}
