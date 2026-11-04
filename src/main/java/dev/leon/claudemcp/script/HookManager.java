package dev.leon.claudemcp.script;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.leon.claudemcp.ClaudeMcp;
import groovy.lang.Script;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Groovy hooks bound to game events. Hooks run synchronously on the client thread with a short
 * time limit; the variable {@code event} holds the event data ({@code g}/{@code delta} for "hud").
 */
public final class HookManager {
	public static final Set<String> EVENTS = Set.of("tick", "hud", "chat", "damage", "death", "key", "join", "leave",
			"screen", "task_end", "log_error", "respawn", "dimension", "chat_in", "chat_out");

	public static final class HookDef {
		public String id;
		public String event;
		public String code;
		public String description = "";
		public boolean persist;
		public boolean autoload = true;
	}

	static final class Hook {
		final HookDef def;
		final Class<? extends Script> cls;
		int calls, errors, consecutiveErrors;
		String lastError;
		boolean enabled = true;

		Hook(HookDef def, Class<? extends Script> cls) {
			this.def = def;
			this.cls = cls;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final ScriptManager scripts;
	private final Path file;
	private final List<Hook> hooks = new CopyOnWriteArrayList<>();
	private final List<HookDef> persisted = new ArrayList<>();
	private int nextId = 1;
	public long hookTimeoutMs = 50;

	public HookManager(ScriptManager scripts, Path file) {
		this.scripts = scripts;
		this.file = file;
	}

	public HookDef register(String event, String code, String id, String description, boolean persist, boolean autoload) throws IOException {
		if (!ClaudeMcp.config().enableScripts) throw new IllegalStateException("Scripts are disabled (enableScripts=false)");
		if (!EVENTS.contains(event)) throw new IllegalArgumentException("Unknown event '" + event + "'. Valid: " + new TreeSet<>(EVENTS));
		HookDef d = new HookDef();
		d.id = id == null || id.isBlank() ? event + "-" + (nextId++) : ScriptManager.checkName(id);
		d.event = event;
		d.code = code;
		d.description = description == null ? "" : description;
		d.persist = persist;
		d.autoload = autoload;
		Class<? extends Script> cls = scripts.compile(code);
		remove(d.id);
		hooks.add(new Hook(d, cls));
		if (persist) {
			synchronized (persisted) {
				persisted.removeIf(p -> p.id.equals(d.id));
				persisted.add(d);
			}
			savePersisted();
		}
		return d;
	}

	public boolean remove(String id) throws IOException {
		boolean r = hooks.removeIf(h -> h.def.id.equals(id));
		boolean p;
		synchronized (persisted) {
			p = persisted.removeIf(d -> d.id.equals(id));
		}
		if (p) savePersisted();
		return r || p;
	}

	public int count() {
		return hooks.size();
	}

	public boolean has(String event) {
		for (Hook h : hooks) if (h.enabled && h.def.event.equals(event)) return true;
		return false;
	}

	public List<Map<String, Object>> list() {
		List<Map<String, Object>> out = new ArrayList<>();
		Set<String> active = new HashSet<>();
		for (Hook h : hooks) {
			active.add(h.def.id);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", h.def.id);
			m.put("event", h.def.event);
			m.put("description", h.def.description);
			m.put("enabled", h.enabled);
			m.put("persist", h.def.persist);
			m.put("calls", h.calls);
			m.put("errors", h.errors);
			if (h.lastError != null) m.put("last_error", h.lastError);
			out.add(m);
		}
		synchronized (persisted) {
			for (HookDef d : persisted) {
				if (active.contains(d.id)) continue;
				out.add(Map.of("id", d.id, "event", d.event, "persist", true, "loaded", false, "autoload", d.autoload));
			}
		}
		return out;
	}

	/** Fires all hooks for an event. Must be called on the client thread. */
	public void fire(String event, Map<String, Object> data) {
		for (Hook h : hooks) {
			if (!h.enabled || !h.def.event.equals(event)) continue;
			h.calls++;
			try {
				Map<String, Object> vars = new HashMap<>();
				vars.put("event", data);
				if (data != null) vars.putAll(data);
				scripts.run(h.cls, vars, h.calls <= 3 ? 500 : hookTimeoutMs, null);
				h.consecutiveErrors = 0;
			} catch (Throwable t) {
				h.errors++;
				h.consecutiveErrors++;
				h.lastError = t instanceof InterruptedException ? "exceeded " + hookTimeoutMs + " ms" : t.toString();
				if (h.consecutiveErrors >= 5) {
					h.enabled = false;
					ClaudeMcp.events().emit("hook_disabled", "id", h.def.id, "error", h.lastError);
				}
			}
		}
	}

	/**
	 * Runs filter hooks (chat_in/chat_out) on the current thread. The script result decides:
	 * false = block, a String = replacement text (passed on to the next hook), anything else = unchanged.
	 */
	public Object filter(String event, Map<String, Object> data) {
		Object result = null;
		for (Hook h : hooks) {
			if (!h.enabled || !h.def.event.equals(event)) continue;
			h.calls++;
			try {
				Map<String, Object> vars = new HashMap<>();
				vars.put("event", data);
				vars.putAll(data);
				ScriptManager.Result r = scripts.run(h.cls, vars, h.calls <= 3 ? 500 : hookTimeoutMs, null);
				h.consecutiveErrors = 0;
				if (Boolean.FALSE.equals(r.value)) return false;
				if (r.value instanceof CharSequence cs) {
					result = cs.toString();
					data.put("text", result);
				}
			} catch (Throwable t) {
				h.errors++;
				h.consecutiveErrors++;
				h.lastError = t instanceof InterruptedException ? "exceeded time limit" : t.toString();
				if (h.consecutiveErrors >= 5) {
					h.enabled = false;
					ClaudeMcp.events().emit("hook_disabled", "id", h.def.id, "error", h.lastError);
				}
			}
		}
		return result;
	}

	private void savePersisted() throws IOException {
		synchronized (persisted) {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(persisted), StandardCharsets.UTF_8);
		}
	}

	public void loadPersisted() {
		try {
			if (!Files.exists(file)) return;
			List<HookDef> l = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<HookDef>>() {}.getType());
			if (l == null) return;
			synchronized (persisted) {
				persisted.clear();
				persisted.addAll(l);
			}
			for (HookDef d : l) {
				if (!d.autoload) continue;
				try {
					hooks.add(new Hook(d, scripts.compile(d.code)));
				} catch (Throwable t) {
					ClaudeMcp.LOG.warn("Cannot load hook {}: {}", d.id, t.toString());
				}
			}
		} catch (Throwable t) {
			ClaudeMcp.LOG.warn("Cannot load hooks", t);
		}
	}
}
