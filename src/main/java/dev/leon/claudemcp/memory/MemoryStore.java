package dev.leon.claudemcp.memory;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Persistent memory for Claude: notes, waypoints and key/value entries, stored as JSON per world
 * (config/claudemcp/memory/&lt;world-id&gt;/memory.json) plus a "global" scope.
 */
public final class MemoryStore {
	public static final class Entry {
		public String id;
		public String kind; // note | waypoint | kv
		public String key;
		public String text;
		public List<String> tags = new ArrayList<>();
		public Map<String, Object> data = new LinkedHashMap<>();
		public long created;
		public long updated;

		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", id);
			m.put("kind", kind);
			if (key != null) m.put("key", key);
			if (text != null) m.put("text", text);
			if (!tags.isEmpty()) m.put("tags", tags);
			if (!data.isEmpty()) m.put("data", data);
			m.put("created", created);
			m.put("updated", updated);
			return m;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private final Path root;
	private final Map<String, List<Entry>> cache = new HashMap<>();

	public MemoryStore(Path root) {
		this.root = root;
	}

	public static String sanitize(String s) {
		String t = s.replaceAll("[^A-Za-z0-9._-]+", "_");
		return t.length() > 80 ? t.substring(0, 80) : t;
	}

	private Path file(String scope) {
		return root.resolve(sanitize(scope)).resolve("memory.json");
	}

	private synchronized List<Entry> entries(String scope) {
		return cache.computeIfAbsent(scope, s -> {
			Path f = file(s);
			if (!Files.exists(f)) return new ArrayList<>();
			try {
				List<Entry> l = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<List<Entry>>() {}.getType());
				return l == null ? new ArrayList<>() : new ArrayList<>(l);
			} catch (IOException | JsonParseException e) {
				throw new RuntimeException("Cannot read memory " + f + ": " + e.getMessage(), e);
			}
		});
	}

	private synchronized void save(String scope) {
		Path f = file(scope);
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling("memory.json.tmp");
			Files.writeString(tmp, GSON.toJson(entries(scope)), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			throw new RuntimeException("Cannot write memory " + f + ": " + e.getMessage(), e);
		}
	}

	/** Creates or updates an entry. For kv/waypoint the key is unique (replaces existing). */
	public synchronized Entry put(String scope, String kind, String key, String text, List<String> tags, Map<String, Object> data) {
		if (kind == null || kind.isBlank()) kind = "note";
		if (!kind.equals("note") && !kind.equals("waypoint") && !kind.equals("kv")) throw new IllegalArgumentException("kind must be note, waypoint or kv");
		if ((kind.equals("kv") || kind.equals("waypoint")) && (key == null || key.isBlank())) throw new IllegalArgumentException(kind + " needs a key/name");
		List<Entry> l = entries(scope);
		long now = System.currentTimeMillis();
		Entry e = null;
		if (key != null) {
			for (Entry x : l) if (key.equals(x.key) && x.kind.equals(kind)) e = x;
		}
		if (e == null) {
			e = new Entry();
			e.id = Long.toString(now, 36) + Integer.toString(new Random().nextInt(1296), 36);
			e.kind = kind;
			e.key = key;
			e.created = now;
			l.add(e);
		}
		if (text != null) e.text = text;
		if (tags != null && !tags.isEmpty()) e.tags = new ArrayList<>(tags);
		if (data != null && !data.isEmpty()) e.data = new LinkedHashMap<>(data);
		e.updated = now;
		save(scope);
		return e;
	}

	public synchronized Entry get(String scope, String idOrKey) {
		for (Entry e : entries(scope)) if (idOrKey.equals(e.id) || idOrKey.equals(e.key)) return e;
		return null;
	}

	public synchronized boolean delete(String scope, String idOrKey) {
		boolean r = entries(scope).removeIf(e -> idOrKey.equals(e.id) || idOrKey.equals(e.key));
		if (r) save(scope);
		return r;
	}

	public synchronized List<Entry> search(String scope, String query, List<String> tags, String kind, int limit) {
		List<String> words = new ArrayList<>();
		if (query != null) for (String w : query.toLowerCase(Locale.ROOT).split("\\s+")) if (!w.isBlank()) words.add(w);
		record Scored(Entry e, int score) {}
		List<Scored> out = new ArrayList<>();
		for (Entry e : entries(scope)) {
			if (kind != null && !kind.isBlank() && !kind.equals(e.kind)) continue;
			if (tags != null && !tags.isEmpty() && !e.tags.containsAll(tags)) continue;
			int score = 0;
			if (!words.isEmpty()) {
				String hay = ((e.key == null ? "" : e.key) + " " + (e.text == null ? "" : e.text) + " " + String.join(" ", e.tags)
						+ " " + e.data).toLowerCase(Locale.ROOT);
				for (String w : words) if (hay.contains(w)) score++;
				if (score == 0) continue;
			}
			out.add(new Scored(e, score));
		}
		out.sort(Comparator.comparingInt(Scored::score).reversed().thenComparing(s -> -s.e.updated));
		List<Entry> res = new ArrayList<>();
		for (int i = 0; i < Math.min(limit, out.size()); i++) res.add(out.get(i).e);
		return res;
	}

	public synchronized List<String> scopes() {
		List<String> out = new ArrayList<>();
		if (Files.isDirectory(root)) {
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
				for (Path p : ds) if (Files.exists(p.resolve("memory.json"))) out.add(p.getFileName().toString());
			} catch (IOException ignored) {
			}
		}
		return out;
	}
}
