package dev.leon.claudemcp;

import dev.leon.claudemcp.control.BotInput;
import dev.leon.claudemcp.control.Mc;
import dev.leon.claudemcp.control.Perception;
import dev.leon.claudemcp.core.*;
import dev.leon.claudemcp.hud.ClaudeHud;
import dev.leon.claudemcp.mcp.McpHost;
import dev.leon.claudemcp.mcp.Tool;
import dev.leon.claudemcp.memory.MemoryStore;
import dev.leon.claudemcp.reflect.Handles;
import dev.leon.claudemcp.reflect.JsonBridge;
import dev.leon.claudemcp.reflect.Reflection;
import dev.leon.claudemcp.script.HookManager;
import dev.leon.claudemcp.script.ScriptApi;
import dev.leon.claudemcp.script.ScriptManager;
import dev.leon.claudemcp.script.TickClock;
import dev.leon.claudemcp.tools.AllTools;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

public class ClaudeMcp implements ClientModInitializer {
	public static final String MOD_ID = "claudemcp";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	private static Config config;
	private static EventBuffer events;
	private static TaskManager tasks;
	private static MemoryStore memory;
	private static Handles handles;
	private static JsonBridge json;
	private static Reflection reflection;
	private static ScriptManager scripts;
	private static HookManager hooks;
	private static ScriptApi scriptApi;
	private static McpHost host;
	private static StateWatcher watcher;
	private static KeyMapping stopKey;
	private static String startError;
	private static dev.leon.claudemcp.hook.JavaHooks javaHooks;
	private static dev.leon.claudemcp.chat.ChatFilter chatFilter;

	public static dev.leon.claudemcp.hook.JavaHooks javaHooks() { return javaHooks; }
	public static dev.leon.claudemcp.chat.ChatFilter chatFilter() { return chatFilter; }

	public static Config config() { return config; }
	public static EventBuffer events() { return events; }
	public static TaskManager tasks() { return tasks; }
	public static MemoryStore memory() { return memory; }
	public static Handles handles() { return handles; }
	public static JsonBridge json() { return json; }
	public static Reflection reflection() { return reflection; }
	public static ScriptManager scripts() { return scripts; }
	public static HookManager hooks() { return hooks; }
	public static ScriptApi scriptApi() { return scriptApi; }
	public static McpHost host() { return host; }
	public static String startError() { return startError; }

	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}

	@Override
	public void onInitializeClient() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
		config = Config.load(dir);
		events = new EventBuffer(2000);
		LogCapture.install(events);
		tasks = new TaskManager(events);
		tasks.setOnBodyReleased(BotInput::release);
		memory = new MemoryStore(dir.resolve("memory"));
		handles = new Handles();
		json = new JsonBridge(handles);
		registerRoots();
		List<Path> roots = new ArrayList<>();
		FabricLoader.getInstance().getModContainer("minecraft").ifPresent(c -> roots.addAll(c.getRootPaths()));
		reflection = new Reflection(handles, json, ClaudeMcp.class.getClassLoader(), roots);
		scriptApi = new ScriptApi();
		scripts = new ScriptManager(dir.resolve("scripts"));
		hooks = new HookManager(scripts, dir.resolve("scripts").resolve("hooks.json"));
		watcher = new StateWatcher();
		javaHooks = new dev.leon.claudemcp.hook.JavaHooks();
		chatFilter = new dev.leon.claudemcp.chat.ChatFilter(dir.resolve("chat_rules.json"));
		chatFilter.register();

		stopKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.claudemcp.stop", InputConstants.KEY_END,
				KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"))));

		ClientTickEvents.END_CLIENT_TICK.register(ClaudeMcp::onTick);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), new ClaudeHud());

		ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
			if (overlay) return;
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("text", msg.getString());
			events.emit("game_message", d);
		});
		ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) -> {
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("text", msg.getString());
			d.put("sender", sender == null ? null : sender.name());
			if (signed != null) d.put("content", signed.signedContent());
			events.emit("chat", d);
		});
		ClientPlayConnectionEvents.JOIN.register((h, s, mc) -> mc.execute(() -> {
			events.emit("join", "world_id", Perception.worldId());
			fireHook("join", Map.of("world_id", Perception.worldId()));
		}));
		ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> {
			stopAll("disconnected");
			handles.clear();
			events.emit("leave");
			mc.execute(() -> fireHook("leave", Map.of()));
		});
		events.addListener(e -> {
			String hookEvent = switch (e.type()) {
				case "chat", "game_message" -> "chat";
				case "task_end" -> "task_end";
				case "log_error" -> "log_error";
				default -> null;
			};
			if (hookEvent != null && hooks.has(hookEvent)) {
				Map<String, Object> d = new LinkedHashMap<>(e.data());
				d.put("type", e.type());
				Minecraft.getInstance().execute(() -> hooks.fire(hookEvent, d));
			}
		});

		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
			hooks.loadPersisted();
			scripts.runAutoload();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			tasks.shutdown();
			if (host != null) host.stop();
		});

		List<Tool> toolList = AllTools.build();
		host = new McpHost(config, toolList);
		try {
			host.start();
		} catch (Throwable t) {
			startError = t.toString();
			LOG.error("Claude MCP server failed to start on {}:{}", config.host, config.port, t);
		}
	}

	private void registerRoots() {
		handles.root("mc", Minecraft::getInstance);
		handles.root("player", () -> Minecraft.getInstance().player);
		handles.root("level", () -> Minecraft.getInstance().level);
		handles.root("gameMode", () -> Minecraft.getInstance().gameMode);
		handles.root("connection", () -> Minecraft.getInstance().getConnection());
		handles.root("screen", () -> Minecraft.getInstance().gui.screen());
		handles.root("options", () -> Minecraft.getInstance().options);
		handles.root("gui", () -> Minecraft.getInstance().gui);
	}

	public static void fireHook(String event, Map<String, Object> data) {
		if (hooks != null && hooks.has(event)) hooks.fire(event, data);
	}

	public static int stopAll(String reason) {
		int n = tasks.cancelAll(reason, false);
		Minecraft.getInstance().execute(() -> {
			BotInput.release();
			Minecraft mc = Minecraft.getInstance();
			mc.options.keyUse.setDown(false);
			mc.options.keyAttack.setDown(false);
			if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		});
		events.emit("stop_all", "reason", reason, "cancelled", n);
		return n;
	}

	/** Called by the input mixins when the real player presses a gameplay key. */
	public static void onUserInput(String what) {
		if (config == null || !config.userOverride || tasks == null) return;
		if (!tasks.bodyBusy()) return;
		int n = tasks.cancelAll("user_override (" + what + ")", true);
		BotInput.release();
		if (n > 0) events.emit("user_override", "input", what, "cancelled_tasks", n);
	}

	private static void onTick(Minecraft mc) {
		TickClock.tick();
		while (stopKey.consumeClick()) {
			int n = stopAll("stop key");
			if (mc.player != null) scriptApi.say("Stopped " + n + " task(s).");
		}
		tasks.tick();
		if (!tasks.bodyBusy() && BotInput.active()) BotInput.release();
		watcher.tick(mc);
		fireHook("tick", Map.of());
	}

	/** Emits damage/death/screen/dimension events by watching the state every tick. */
	static final class StateWatcher {
		private float lastHealth = -1;
		private boolean wasDead;
		private String lastScreen;
		private String lastDim;

		void tick(Minecraft mc) {
			String scr = mc.gui.screen() == null ? null : mc.gui.screen().getClass().getSimpleName();
			if (!Objects.equals(scr, lastScreen)) {
				Map<String, Object> d = new LinkedHashMap<>();
				d.put("screen", scr);
				d.put("title", mc.gui.screen() == null ? null : mc.gui.screen().getTitle().getString());
				d.put("previous", lastScreen);
				events.emit("screen", d);
				fireHook("screen", d);
				lastScreen = scr;
			}
			if (!Mc.inWorld()) {
				lastHealth = -1;
				lastDim = null;
				return;
			}
			var p = mc.player;
			float h = p.getHealth();
			if (lastHealth >= 0 && h < lastHealth - 0.01f) {
				Map<String, Object> d = new LinkedHashMap<>();
				d.put("amount", Mc.r2(lastHealth - h));
				d.put("health", Mc.r2(h));
				var src = p.getLastDamageSource();
				if (src != null) d.put("source", src.getMsgId());
				events.emit("damage", d);
				fireHook("damage", d);
			}
			lastHealth = h;
			boolean dead = p.isDeadOrDying();
			if (dead && !wasDead) {
				Map<String, Object> d = Map.of("pos", Mc.vec(p.position()), "dimension", mc.level.dimension().identifier().toString());
				events.emit("death", d);
				fireHook("death", d);
				tasks.cancelAll("player died", true);
			} else if (!dead && wasDead) {
				events.emit("respawn", "pos", Mc.vec(p.position()));
				fireHook("respawn", Map.of());
			}
			wasDead = dead;
			String dim = mc.level.dimension().identifier().toString();
			if (lastDim != null && !dim.equals(lastDim)) {
				events.emit("dimension", "from", lastDim, "to", dim);
				fireHook("dimension", Map.of("from", lastDim, "to", dim));
			}
			lastDim = dim;
		}
	}
}
