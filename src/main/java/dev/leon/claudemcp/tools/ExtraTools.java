package dev.leon.claudemcp.tools;

import dev.leon.claudemcp.ClaudeMcp;
import dev.leon.claudemcp.mcp.Args;
import dev.leon.claudemcp.mcp.Tool;
import dev.leon.claudemcp.nbt.NbtEditor;
import net.minecraft.core.BlockPos;

import java.util.*;

/** Runtime method hooks, chat filter rules and the NBT editor. */
final class ExtraTools {
	private ExtraTools() {}

	static void add(List<Tool> t) {
		hooks(t);
		chat(t);
		nbt(t);
	}

	// =========================================================== java hooks

	static void hooks(List<Tool> t) {
		t.add(Tool.of("java_hook", "Hooks any game/mod method at runtime (Java agent + Byte Buddy retransformation). Without code the hook "
				+ "only traces calls (read them with java_hook_calls). 'before' Groovy runs before the method: variables self, args (Object[], "
				+ "assign args[i] to change arguments), method, hook; call hook.cancel(value) to skip the original and return value. 'after' runs "
				+ "after it with result/thrown; hook.setResult(v) replaces the return value. Runs on the thread that calls the method, "
				+ "50 ms limit, auto-disabled after 5 errors. Not possible for JDK classes or the mod itself.").group("hook")
				.str("class", "Class name (Mojang name, e.g. net.minecraft.client.player.LocalPlayer or LocalPlayer)", true)
				.str("method", "Method name", true)
				.str("signature", "Parameter types if overloaded, e.g. \"(float)\"", false)
				.bool("all_overloads", "Hook every overload of the name", false)
				.str("before", "Groovy code run before the method", false)
				.str("after", "Groovy code run after the method", false)
				.integer("max_calls", "Disable the hook after this many calls (0 = unlimited)", false)
				.str("id", "Hook id (replaces an existing hook with that id)", false)
				.handler(a -> {
					Class<?> cls = ClaudeMcp.reflection().loadClass(a.str("class"));
					List<Map<String, Object>> r = ClaudeMcp.javaHooks().add(cls, a.str("method"), a.str("signature", null),
							a.bool("all_overloads", false), null, a.str("before", null), a.str("after", null), a.integer("max_calls", 0), a.str("id", null));
					return Map.of("installed", r);
				}));

		t.add(Tool.of("java_hook_calls", "Recorded calls of a tracing hook (args, result, thread), newest last.").group("hook")
				.str("id", "Hook id", true)
				.integer("since", "Only entries with seq > since", false)
				.integer("limit", "Max entries (default 50)", false)
				.handler(a -> {
					var h = ClaudeMcp.javaHooks().get(a.str("id"));
					return Map.of("hook", h.describe(), "calls", h.traceSince(a.integer("since", 0), a.integer("limit", 50)));
				}));

		t.add(Tool.of("java_hook_list", "Lists installed method hooks.").group("hook")
				.handler(a -> Map.of("agent_attached", ClaudeMcp.javaHooks().attached(), "hooks", ClaudeMcp.javaHooks().list())));

		t.add(Tool.of("java_hook_remove", "Removes a method hook (restores the original bytecode when the last hook of a method is removed). id=\"all\" removes all.").group("hook")
				.str("id", "Hook id or all", true)
				.handler(a -> "all".equals(a.str("id"))
						? Map.of("removed", ClaudeMcp.javaHooks().removeAll())
						: Map.of("removed", ClaudeMcp.javaHooks().remove(a.str("id")))));

		t.add(Tool.of("java_hook_enable", "Enables or disables a method hook without removing it.").group("hook")
				.str("id", "Hook id", true)
				.bool("enabled", "true/false", true)
				.handler(a -> {
					ClaudeMcp.javaHooks().setEnabled(a.str("id"), a.bool("enabled", true));
					return ClaudeMcp.javaHooks().get(a.str("id")).describe();
				}));
	}

	// =========================================================== chat filter

	static void chat(List<Tool> t) {
		t.add(Tool.of("chat_rule_add", "Adds a chat filter rule (regex, case-insensitive). direction in = received messages, out = messages/commands "
				+ "you send. action hide = suppress, replace = rewrite with replacement ($1 groups allowed), notify = only emit a chat_match event. "
				+ "Rewritten player chat is shown as a local line (signed chat cannot be edited in place). For logic use hook_register with "
				+ "event chat_in/chat_out: return false to block or a String to replace.").group("chat")
				.str("pattern", "Regex", true)
				.enumStr("direction", "Direction (default in)", false, "in", "out", "both")
				.enumStr("kind", "Message kind (default all)", false, "all", "chat", "game", "command")
				.enumStr("action", "Action (default hide)", false, "hide", "replace", "notify")
				.str("replacement", "Replacement text for action=replace", false)
				.str("id", "Rule id", false)
				.bool("persist", "Keep after restart", false)
				.handler(a -> ClaudeMcp.chatFilter().add(a.str("direction", "in"), a.str("kind", "all"), a.str("pattern"), a.str("action", "hide"),
						a.str("replacement", ""), a.str("id", null), a.bool("persist", false)).describe()));

		t.add(Tool.of("chat_rule_list", "Lists chat filter rules with hit counters.").group("chat")
				.handler(a -> ClaudeMcp.chatFilter().list()));

		t.add(Tool.of("chat_rule_remove", "Removes a chat filter rule.").group("chat")
				.str("id", "Rule id", true)
				.handler(a -> Map.of("removed", ClaudeMcp.chatFilter().remove(a.str("id")))));
	}

	// =========================================================== nbt

	static Tool target(Tool t) {
		return t.enumStr("target", "What to edit", true, "block", "entity", "player", "item")
				.xyz("Block position (target=block)", false)
				.integer("entity_id", "Entity id (target=entity)", false)
				.str("slot", "Item slot (target=item): hand, offhand, head, chest, legs, feet or inventory index 0-40 (default hand)", false);
	}

	static NbtEditor.Target parse(Args a) {
		NbtEditor.Kind k = NbtEditor.Kind.valueOf(a.str("target").toUpperCase(Locale.ROOT));
		return switch (k) {
			case BLOCK -> new NbtEditor.Target(k, new BlockPos(a.integer("x"), a.integer("y"), a.integer("z")), 0, null);
			case ENTITY -> new NbtEditor.Target(k, null, a.integer("entity_id"), null);
			case PLAYER -> new NbtEditor.Target(k, null, 0, null);
			case ITEM -> new NbtEditor.Target(k, null, 0, a.str("slot", "hand"));
		};
	}

	static void nbt(List<Tool> t) {
		String how = " Singleplayer edits the real server-side data; on servers block/entity edits are sent as /data commands "
				+ "(needs permission) and items only work in creative mode.";
		t.add(target(Tool.of("nbt_get", "Reads NBT as SNBT: block entities (chest, sign, spawner...), entities, the player, or an item "
				+ "(data components). Optional path in /data syntax, e.g. Items[0].components or Inventory[{Slot:0b}].").group("nbt"))
				.str("path", "NBT path (vanilla /data syntax)", false)
				.bool("client", "Force reading the client copy instead of the integrated server", false)
				.handler(a -> NbtEditor.read(parse(a), a.str("path", null), a.bool("client", false))));

		t.add(target(Tool.of("nbt_set", "Edits NBT. Either merge a compound (snbt = {...}), set one path (path + snbt value) or replace "
				+ "everything (mode=replace, singleplayer)." + how).group("nbt"))
				.str("snbt", "SNBT compound for merge/replace, or the value for path", true)
				.str("path", "NBT path to set (with snbt as value)", false)
				.enumStr("mode", "merge (default) or replace", false, "merge", "replace")
				.handler(a -> {
					NbtEditor.Op op = a.has("path") ? NbtEditor.Op.SET_PATH : "replace".equals(a.str("mode", "merge")) ? NbtEditor.Op.REPLACE : NbtEditor.Op.MERGE;
					return NbtEditor.write(parse(a), op, a.str("path", null), a.str("snbt"));
				}));

		t.add(target(Tool.of("nbt_remove", "Removes the element(s) at an NBT path." + how).group("nbt"))
				.str("path", "NBT path", true)
				.handler(a -> NbtEditor.write(parse(a), NbtEditor.Op.REMOVE_PATH, a.str("path"), null)));

		t.add(Tool.of("nbt_give", "Creates an item from SNBT and puts it into a slot, e.g. "
				+ "{id:\"minecraft:diamond_sword\",count:1,components:{\"minecraft:custom_name\":\"Excalibur\"}}." + how).group("nbt")
				.str("snbt", "Item SNBT with id, count and optional components", true)
				.str("slot", "Slot (default: first empty inventory slot)", false)
				.handler(a -> NbtEditor.give(a.str("snbt"), a.str("slot", null))));
	}
}
