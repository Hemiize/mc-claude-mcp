package dev.leon.claudemcp.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.leon.claudemcp.ClaudeMcp;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filters and rewrites chat: incoming game/system messages and player chat, outgoing chat and commands.
 * Rules (regex based) plus Groovy hooks "chat_in" / "chat_out" (return false = block, a String = replace).
 */
public final class ChatFilter {
	public static final class Rule {
		public String id;
		/** in | out | both */
		public String direction = "in";
		/** chat | game | command | all (command only for direction out) */
		public String kind = "all";
		public String pattern;
		/** hide | replace | notify */
		public String action = "hide";
		public String replacement = "";
		public boolean persist;
		public transient Pattern compiled;
		public transient long hits;

		public Map<String, Object> describe() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", id);
			m.put("direction", direction);
			m.put("kind", kind);
			m.put("pattern", pattern);
			m.put("action", action);
			if ("replace".equals(action)) m.put("replacement", replacement);
			m.put("persist", persist);
			m.put("hits", hits);
			return m;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final List<Rule> rules = new CopyOnWriteArrayList<>();
	private final Path file;
	private int nextId = 1;
	/** Set while we re-inject a rewritten player chat line, so it is not filtered twice. */
	private boolean reinjecting;

	public ChatFilter(Path file) {
		this.file = file;
		load();
	}

	public void register() {
		ClientReceiveMessageEvents.ALLOW_GAME.register((msg, overlay) -> overlay || allowIncoming("game", msg.getString()));
		ClientReceiveMessageEvents.MODIFY_GAME.register((msg, overlay) -> {
			if (overlay || reinjecting) return msg;
			String out = rewriteIncoming("game", msg.getString());
			if (out == null) return msg;
			ClaudeMcp.events().emit("chat_rewritten", "kind", "game", "original", msg.getString(), "text", out);
			return Component.literal(out);
		});
		ClientReceiveMessageEvents.ALLOW_CHAT.register((msg, signed, sender, params, ts) -> {
			String text = msg.getString();
			if (!allowIncoming("chat", text)) return false;
			String out = rewriteIncoming("chat", text);
			if (out != null) {
				ClaudeMcp.events().emit("chat_rewritten", "kind", "chat", "original", text, "text", out, "sender", sender == null ? null : sender.name());
				// signed player chat cannot be modified in place: hide it and show the rewritten line locally
				Minecraft.getInstance().execute(() -> {
					reinjecting = true;
					try {
						Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal(out));
					} finally {
						reinjecting = false;
					}
				});
				return false;
			}
			return true;
		});
		ClientSendMessageEvents.ALLOW_CHAT.register(msg -> allowOutgoing("chat", msg));
		ClientSendMessageEvents.MODIFY_CHAT.register(msg -> {
			String out = rewriteOutgoing("chat", msg);
			return out == null ? msg : out;
		});
		ClientSendMessageEvents.ALLOW_COMMAND.register(cmd -> allowOutgoing("command", cmd));
		ClientSendMessageEvents.MODIFY_COMMAND.register(cmd -> {
			String out = rewriteOutgoing("command", cmd);
			return out == null ? cmd : out;
		});
	}

	// ------------------------------------------------------------ evaluation

	private boolean matches(Rule r, String dir, String kind) {
		if (!r.direction.equals("both") && !r.direction.equals(dir)) return false;
		return r.kind.equals("all") || r.kind.equals(kind);
	}

	private boolean allowIncoming(String kind, String text) {
		if (reinjecting) return true;
		for (Rule r : rules) {
			if (!matches(r, "in", kind) || !r.compiled.matcher(text).find()) continue;
			if (r.action.equals("hide")) {
				r.hits++;
				ClaudeMcp.events().emit("chat_filtered", "direction", "in", "kind", kind, "rule", r.id, "text", text);
				return false;
			}
			if (r.action.equals("notify")) {
				r.hits++;
				ClaudeMcp.events().emit("chat_match", "rule", r.id, "kind", kind, "text", text);
			}
		}
		Object h = hook("chat_in", kind, text);
		if (Boolean.FALSE.equals(h)) {
			ClaudeMcp.events().emit("chat_filtered", "direction", "in", "kind", kind, "rule", "hook", "text", text);
			return false;
		}
		return true;
	}

	/** Returns the rewritten text, or null if unchanged. */
	private String rewriteIncoming(String kind, String text) {
		String cur = text;
		boolean changed = false;
		for (Rule r : rules) {
			if (!matches(r, "in", kind) || !r.action.equals("replace")) continue;
			Matcher m = r.compiled.matcher(cur);
			if (m.find()) {
				r.hits++;
				cur = m.replaceAll(r.replacement);
				changed = true;
			}
		}
		Object h = hook("chat_in", kind, cur);
		if (h instanceof CharSequence s && !s.toString().equals(cur)) {
			cur = s.toString();
			changed = true;
		}
		return changed ? cur : null;
	}

	private boolean allowOutgoing(String kind, String text) {
		for (Rule r : rules) {
			if (!matches(r, "out", kind) || !r.compiled.matcher(text).find()) continue;
			if (r.action.equals("hide")) {
				r.hits++;
				ClaudeMcp.events().emit("chat_filtered", "direction", "out", "kind", kind, "rule", r.id, "text", text);
				return false;
			}
		}
		Object h = hook("chat_out", kind, text);
		return !Boolean.FALSE.equals(h);
	}

	private String rewriteOutgoing(String kind, String text) {
		String cur = text;
		boolean changed = false;
		for (Rule r : rules) {
			if (!matches(r, "out", kind) || !r.action.equals("replace")) continue;
			Matcher m = r.compiled.matcher(cur);
			if (m.find()) {
				r.hits++;
				cur = m.replaceAll(r.replacement);
				changed = true;
			}
		}
		Object h = hook("chat_out", kind, cur);
		if (h instanceof CharSequence s && !s.toString().equals(cur)) {
			cur = s.toString();
			changed = true;
		}
		return changed ? cur : null;
	}

	private Object hook(String event, String kind, String text) {
		if (ClaudeMcp.hooks() == null || !ClaudeMcp.hooks().has(event)) return null;
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("text", text);
		d.put("kind", kind);
		return ClaudeMcp.hooks().filter(event, d);
	}

	// ------------------------------------------------------------ management

	public synchronized Rule add(String direction, String kind, String pattern, String action, String replacement, String id, boolean persist) {
		Rule r = new Rule();
		r.direction = check(direction == null ? "in" : direction, "in", "out", "both");
		r.kind = check(kind == null ? "all" : kind, "all", "chat", "game", "command");
		r.action = check(action == null ? "hide" : action, "hide", "replace", "notify");
		r.pattern = pattern;
		r.replacement = replacement == null ? "" : replacement;
		r.compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
		r.persist = persist;
		r.id = id == null || id.isBlank() ? "rule" + (nextId++) : id;
		rules.removeIf(x -> x.id.equals(r.id));
		rules.add(r);
		if (persist) save();
		return r;
	}

	static String check(String v, String... allowed) {
		for (String a : allowed) if (a.equals(v)) return v;
		throw new IllegalArgumentException("'" + v + "' must be one of " + String.join(", ", allowed));
	}

	public synchronized boolean remove(String id) {
		boolean r = rules.removeIf(x -> x.id.equals(id));
		save();
		return r;
	}

	public List<Map<String, Object>> list() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Rule r : rules) out.add(r.describe());
		return out;
	}

	private void load() {
		try {
			if (!Files.exists(file)) return;
			List<Rule> l = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Rule>>() {}.getType());
			if (l == null) return;
			for (Rule r : l) {
				r.compiled = Pattern.compile(r.pattern, Pattern.CASE_INSENSITIVE);
				rules.add(r);
			}
		} catch (Throwable t) {
			ClaudeMcp.LOG.warn("Cannot load chat rules", t);
		}
	}

	private void save() {
		try {
			List<Rule> p = new ArrayList<>();
			for (Rule r : rules) if (r.persist) p.add(r);
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(p), StandardCharsets.UTF_8);
		} catch (IOException e) {
			ClaudeMcp.LOG.warn("Cannot save chat rules", e);
		}
	}
}
