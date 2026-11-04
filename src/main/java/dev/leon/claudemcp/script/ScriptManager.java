package dev.leon.claudemcp.script;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.leon.claudemcp.ClaudeMcp;
import dev.leon.claudemcp.core.MainThread;
import dev.leon.claudemcp.core.TaskManager;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import net.minecraft.client.Minecraft;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.runtime.InvokerHelper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Compiles and runs Groovy scripts inside the client, stores named scripts on disk. */
public final class ScriptManager {
	public static final class Meta {
		public String description = "";
		public boolean autoload = false;
		public long updated;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Path dir;
	private final ClassLoader parent;
	private final CompilerConfiguration cfg;
	private final Map<String, Meta> index = new ConcurrentHashMap<>();
	private final Map<String, Class<? extends Script>> compiledCache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Class<? extends Script>> e) {
			return size() > 200;
		}
	});
	private final GroovyShell shell;

	public ScriptManager(Path dir) {
		this.dir = dir;
		this.parent = ScriptManager.class.getClassLoader();
		cfg = new CompilerConfiguration();
		ImportCustomizer imports = new ImportCustomizer();
		imports.addStarImports(
				"net.minecraft.client", "net.minecraft.client.player", "net.minecraft.client.multiplayer",
				"net.minecraft.client.gui.screens", "net.minecraft.core", "net.minecraft.core.registries",
				"net.minecraft.world.entity", "net.minecraft.world.entity.player", "net.minecraft.world.item",
				"net.minecraft.world.level", "net.minecraft.world.level.block", "net.minecraft.world.phys",
				"net.minecraft.network.chat", "net.minecraft.resources", "net.minecraft.world",
				"dev.leon.claudemcp.control");
		cfg.addCompilationCustomizers(imports);
		shell = new GroovyShell(parent, new Binding(), cfg);
		load();
	}

	// ------------------------------------------------------------ compile & run

	@SuppressWarnings("unchecked")
	public Class<? extends Script> compile(String code) {
		String key = code;
		Class<? extends Script> c = compiledCache.get(key);
		if (c != null) return c;
		String name = "ClaudeScript" + Integer.toHexString(code.hashCode() & 0x7fffffff) + "_" + System.nanoTime() % 100000;
		synchronized (shell) {
			c = (Class<? extends Script>) shell.parse(Guard.PREFIX + code, name + ".groovy").getClass();
		}
		compiledCache.put(key, c);
		return c;
	}

	public static final class Result {
		public Object value;
		public String output;
	}

	/** Runs a script in the current thread with a fresh binding. */
	public Result run(Class<? extends Script> cls, Map<String, Object> vars, long timeoutMs, java.util.function.BooleanSupplier cancelled) throws Exception {
		StringWriter sw = new StringWriter();
		PrintWriter out = new PrintWriter(sw, true);
		Binding b = new LiveBinding(out);
		if (vars != null) vars.forEach(b::setVariable);
		Script s = InvokerHelper.createScript(cls, b);
		Guard.enter(timeoutMs, cancelled);
		Result r = new Result();
		try {
			r.value = s.run();
		} finally {
			Guard.exit();
			out.flush();
			r.output = sw.toString();
		}
		return r;
	}

	/** Starts a script as a worker-thread task. */
	public TaskManager.Task startAsync(String label, String code, Map<String, Object> args, int timeoutSeconds) {
		if (!ClaudeMcp.config().enableScripts) throw new IllegalStateException("Scripts are disabled in config/claudemcp/config.json (enableScripts)");
		Class<? extends Script> cls = compile(code);
		return ClaudeMcp.tasks().startThread("script", label, task -> {
			Map<String, Object> vars = new HashMap<>();
			vars.put("args", args == null ? Map.of() : args);
			vars.put("task", task);
			Result r = run(cls, vars, timeoutSeconds * 1000L, task::cancelled);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("value", ClaudeMcp.json().toJson(r.value, true));
			if (!r.output.isEmpty()) m.put("output", tail(r.output, 20000));
			task.setResult(m);
			return m;
		});
	}

	/** Runs a short script synchronously on the client thread with a hard time limit. */
	public Map<String, Object> runSync(String code, Map<String, Object> args, long timeoutMs) {
		if (!ClaudeMcp.config().enableScripts) throw new IllegalStateException("Scripts are disabled in config/claudemcp/config.json (enableScripts)");
		Class<? extends Script> cls = compile(code);
		return MainThread.call(() -> {
			Map<String, Object> vars = new HashMap<>();
			vars.put("args", args == null ? Map.of() : args);
			try {
				Result r = run(cls, vars, timeoutMs, null);
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("value", ClaudeMcp.json().toJson(r.value, true));
				if (!r.output.isEmpty()) m.put("output", tail(r.output, 20000));
				return m;
			} catch (Throwable t) {
				if (t instanceof InterruptedException) throw new IllegalStateException("sync script exceeded " + timeoutMs + " ms (use sync=false for longer scripts)");
				if (t instanceof Exception e) throw e;
				throw new RuntimeException(t);
			}
		}, timeoutMs + 15_000);
	}

	static String tail(String s, int n) {
		return s.length() <= n ? s : "..." + s.substring(s.length() - n);
	}

	/** Binding whose player/level/gameMode variables always reflect the current game objects. */
	static final class LiveBinding extends Binding {
		LiveBinding(PrintWriter out) {
			super();
			setVariable("out", out);
			setVariable("mc", Minecraft.getInstance());
			setVariable("api", ClaudeMcp.scriptApi());
			setVariable("memory", ClaudeMcp.scriptApi().memory());
		}

		@Override
		public Object getVariable(String name) {
			Minecraft mc = Minecraft.getInstance();
			return switch (name) {
				case "player" -> mc.player;
				case "level" -> mc.level;
				case "gameMode" -> mc.gameMode;
				case "screen" -> mc.gui.screen();
				default -> super.getVariable(name);
			};
		}

		@Override
		public boolean hasVariable(String name) {
			return switch (name) {
				case "player", "level", "gameMode", "screen" -> true;
				default -> super.hasVariable(name);
			};
		}
	}

	// ------------------------------------------------------------ storage

	private Path indexFile() {
		return dir.resolve("scripts.json");
	}

	private void load() {
		try {
			Files.createDirectories(dir);
			if (Files.exists(indexFile())) {
				Map<String, Meta> m = GSON.fromJson(Files.readString(indexFile(), StandardCharsets.UTF_8), new TypeToken<Map<String, Meta>>() {}.getType());
				if (m != null) index.putAll(m);
			}
		} catch (IOException e) {
			ClaudeMcp.LOG.warn("Cannot load script index", e);
		}
	}

	private synchronized void saveIndex() {
		try {
			Files.writeString(indexFile(), GSON.toJson(new TreeMap<>(index)), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	static String checkName(String name) {
		if (!name.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Script name must match [A-Za-z0-9_-]{1,64}");
		return name;
	}

	public void save(String name, String code, String description, boolean autoload) throws IOException {
		checkName(name);
		compile(code); // fail early on syntax errors
		Files.writeString(dir.resolve(name + ".groovy"), code, StandardCharsets.UTF_8);
		Meta m = index.computeIfAbsent(name, k -> new Meta());
		if (description != null) m.description = description;
		m.autoload = autoload;
		m.updated = System.currentTimeMillis();
		saveIndex();
	}

	public String code(String name) throws IOException {
		Path p = dir.resolve(checkName(name) + ".groovy");
		if (!Files.exists(p)) throw new IllegalArgumentException("No saved script '" + name + "'");
		return Files.readString(p, StandardCharsets.UTF_8);
	}

	public boolean delete(String name) throws IOException {
		boolean existed = Files.deleteIfExists(dir.resolve(checkName(name) + ".groovy"));
		index.remove(name);
		saveIndex();
		return existed;
	}

	public List<Map<String, Object>> list() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (var e : new TreeMap<>(index).entrySet()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", e.getKey());
			m.put("description", e.getValue().description);
			m.put("autoload", e.getValue().autoload);
			m.put("updated", e.getValue().updated);
			out.add(m);
		}
		return out;
	}

	/** Starts all scripts marked autoload. */
	public void runAutoload() {
		for (var e : index.entrySet()) {
			if (!e.getValue().autoload) continue;
			try {
				startAsync("autoload:" + e.getKey(), code(e.getKey()), Map.of(), Integer.MAX_VALUE / 1000);
				ClaudeMcp.LOG.info("Autoloaded script {}", e.getKey());
			} catch (Throwable t) {
				ClaudeMcp.LOG.warn("Autoload of script {} failed: {}", e.getKey(), t.toString());
			}
		}
	}
}
