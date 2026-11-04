package dev.leon.claudemcp.tools;

import dev.leon.claudemcp.ClaudeMcp;
import dev.leon.claudemcp.control.*;
import dev.leon.claudemcp.core.EventBuffer;
import dev.leon.claudemcp.core.LogCapture;
import dev.leon.claudemcp.core.MainThread;
import dev.leon.claudemcp.core.TaskManager;
import dev.leon.claudemcp.mcp.Args;
import dev.leon.claudemcp.mcp.Tool;
import dev.leon.claudemcp.memory.MemoryStore;
import dev.leon.claudemcp.reflect.Reflection;
import dev.leon.claudemcp.script.HookManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.Block;

import java.util.*;

/** All MCP tools. */
public final class AllTools {
	private AllTools() {}

	public static List<Tool> build() {
		List<Tool> t = new ArrayList<>();
		state(t);
		actions(t);
		tasks(t);
		events(t);
		memory(t);
		reflection(t);
		scripts(t);
		ExtraTools.add(t);
		return t;
	}

	// =========================================================== helpers

	static BlockPos pos(Args a) {
		return new BlockPos(a.integer("x"), a.integer("y"), a.integer("z"));
	}

	static BlockPos posOrPlayer(Args a) {
		if (a.has("x") && a.has("y") && a.has("z")) return pos(a);
		return MainThread.call(() -> Mc.player().blockPosition());
	}

	/** Returns the task map, waiting up to 'wait' seconds if requested. */
	static Object taskResult(TaskManager.Task task, Args a, double defaultWait) throws InterruptedException {
		double w = Math.min(a.num("wait", defaultWait), 120);
		if (w > 0) ClaudeMcp.tasks().await(task, (long) (w * 1000));
		Map<String, Object> m = task.toMap();
		if (task.status() == TaskManager.Status.RUNNING) m.put("hint", "still running: poll task_status/task_wait with id " + task.id);
		return m;
	}

	static Tool waitParam(Tool t, String def) {
		return t.num("wait", "Seconds to wait for completion before returning (0 = return task id immediately, max 120). Default " + def, false);
	}

	static InteractionHand hand(Args a) {
		return "off".equalsIgnoreCase(a.str("hand", "main")) || "off_hand".equalsIgnoreCase(a.str("hand", "main"))
				? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
	}

	static Set<String> set(List<String> l) {
		Set<String> s = new HashSet<>();
		for (String x : l) s.add(x.contains(":") ? x : "minecraft:" + x);
		return s;
	}

	static String scope(Args a) {
		String s = a.str("scope", "world");
		if (s.equals("global")) return "global";
		if (s.equals("world")) return MainThread.call(Perception::worldId);
		return s;
	}

	// =========================================================== state

	static void state(List<Tool> t) {
		t.add(Tool.of("get_state", "Player and world state: position, rotation, health, food, dimension, time, weather, held items, "
				+ "what the crosshair targets, open screen, world id. Call this first.").group("state")
				.handler(a -> MainThread.call(Perception::state)));

		t.add(Tool.of("get_blocks", "Blocks in a box around a center (default: player position), grouped by block id. "
				+ "Air is omitted unless include_air. Radius max 16 (each axis).").group("state")
				.xyz("Center", false)
				.integer("radius", "Radius in all axes (default 4)", false)
				.integer("ry", "Vertical radius override", false)
				.strList("filter", "Only these block ids", false)
				.bool("include_air", "Include air blocks", false)
				.integer("limit", "Max blocks (default 3000)", false)
				.handler(a -> {
					int r = Math.min(a.integer("radius", 4), 16);
					int ry = Math.min(a.integer("ry", r), 16);
					BlockPos c = posOrPlayer(a);
					return MainThread.call(() -> Perception.blocks(c, r, ry, r, set(a.strings("filter")), a.bool("include_air", false), a.integer("limit", 3000)));
				}));

		t.add(Tool.of("find_blocks", "Finds the nearest blocks of given ids around the player (radius max 64).").group("state")
				.strList("blocks", "Block ids, e.g. [\"oak_log\",\"birch_log\"]", true)
				.integer("radius", "Search radius (default 32, max 64)", false)
				.integer("limit", "Max results (default 20)", false)
				.handler(a -> {
					Set<Block> bs = new HashSet<>();
					for (String id : a.strings("blocks")) bs.add(Mc.block(id));
					int r = Math.min(a.integer("radius", 32), 64);
					return MainThread.call(() -> Perception.findBlocks(bs, r, a.integer("limit", 20)));
				}));

		t.add(Tool.of("get_entities", "Entities near the player (mobs, players, items, ...), nearest first.").group("state")
				.num("radius", "Radius (default 32, max 128)", false)
				.strList("types", "Only these entity types, e.g. [\"cow\",\"item\"]", false)
				.integer("limit", "Max results (default 50)", false)
				.handler(a -> MainThread.call(() -> Perception.entities(Math.min(a.num("radius", 32), 128), set(a.strings("types")), a.integer("limit", 50)))));

		t.add(Tool.of("get_inventory", "Player inventory (hotbar 0-8, main 9-35, armor 36-39, offhand 40).").group("state")
				.handler(a -> MainThread.call(Perception::inventory)));

		t.add(Tool.of("get_screen", "The open GUI screen: class, title, widgets (buttons, text fields with index) and for containers "
				+ "all non-empty menu slots (menu_slot index for click_slot) plus the carried stack.").group("state")
				.handler(a -> MainThread.call(Perception::screen)));

		t.add(Tool.of("screenshot", "Captures the game view as an image.").group("state")
				.integer("width", "Output width in pixels (default from config, 960)", false)
				.bool("hide_hud", "Hide HUD/GUI for the shot", false)
				.enumStr("format", "Image format", false, "jpeg", "png")
				.handler(a -> Screenshots.capture(a.integer("width", ClaudeMcp.config().screenshotWidth), a.bool("hide_hud", false),
						a.str("format", ClaudeMcp.config().screenshotFormat))));
	}

	// =========================================================== actions

	static void actions(List<Tool> t) {
		t.add(waitParam(Tool.of("goto", "Walks to a block position using A* pathfinding (walk, jump, fall, swim, climb; optionally "
				+ "breaks blocks in the way). Starts a task.").group("action")
				.xyz("Target", true)
				.num("range", "Stop when within this distance (default 0 = exact)", false)
				.bool("allow_break", "Allow breaking blocks in the way (default false)", false)
				.bool("sprint", "Sprint (default true)", false)
				.integer("timeout", "Timeout in seconds (default 300)", false), "0")
				.handler(a -> taskResult(Bot.goTo(pos(a), a.num("range", 0), a.bool("allow_break", false), a.bool("sprint", true),
						a.integer("timeout", 300)), a, 0)));

		t.add(waitParam(Tool.of("follow", "Follows an entity (player/mob) keeping a distance. Runs until cancelled or timeout.").group("action")
				.integer("entity_id", "Entity id from get_entities", true)
				.num("distance", "Distance to keep (default 2)", false)
				.integer("timeout", "Seconds (default 600)", false), "0")
				.handler(a -> taskResult(Bot.follow(a.integer("entity_id"), a.num("distance", 2), a.integer("timeout", 600)), a, 0)));

		t.add(Tool.of("look_at", "Turns the view towards a point (smoothly).").group("action")
				.num("x", "X", true).num("y", "Y", true).num("z", "Z", true)
				.handler(a -> taskResult(Bot.lookAt(a.num("x"), a.num("y"), a.num("z")), a, 3)));

		t.add(Tool.of("set_rotation", "Sets yaw/pitch instantly (degrees; yaw 0 = south, 90 = west, 180 = north, -90 = east).").group("action")
				.num("yaw", "Yaw", false).num("pitch", "Pitch (-90 up .. 90 down)", false)
				.handler(a -> MainThread.call(() -> {
					LocalPlayer p = Mc.player();
					if (a.has("yaw")) {
						p.setYRot((float) a.num("yaw"));
						p.setYHeadRot((float) a.num("yaw"));
					}
					if (a.has("pitch")) p.setXRot((float) Math.max(-90, Math.min(90, a.num("pitch"))));
					return Map.of("yaw", Mc.r2(p.getYRot()), "pitch", Mc.r2(p.getXRot()));
				})));

		t.add(Tool.of("move", "Low-level movement: holds the given keys for some ticks (forward, backward, left, right, jump, sneak, sprint).").group("action")
				.strList("keys", "Keys to hold", true)
				.integer("ticks", "Duration in ticks (20 = 1s, default 20)", false)
				.handler(a -> {
					Set<String> keys = new HashSet<>(a.strings("keys"));
					int ticks = a.integer("ticks", 20);
					TaskManager.Task task = MainThread.call(() -> ClaudeMcp.tasks().startTick("move", "move " + keys + " " + ticks + "t", new TaskManager.TickTask() {
						@Override
						public boolean tick(TaskManager.Task tk) {
							BotInput.activate();
							BotInput.forward = keys.contains("forward");
							BotInput.backward = keys.contains("backward");
							BotInput.left = keys.contains("left");
							BotInput.right = keys.contains("right");
							BotInput.jump = keys.contains("jump");
							BotInput.sneak = keys.contains("sneak");
							BotInput.sprint = keys.contains("sprint");
							return tk.ticks() >= ticks;
						}

						@Override
						public void stop(TaskManager.Task tk) {
							BotInput.clearKeys();
						}
					}));
					return taskResult(task, a, Math.min(ticks / 20.0 + 1, 30));
				}));

		t.add(waitParam(Tool.of("mine_block", "Breaks a block (walks into reach if needed, picks the best tool, picks up drops).").group("action")
				.xyz("Block", true)
				.bool("approach", "Walk into reach if needed (default true)", false)
				.bool("pickup", "Collect dropped items afterwards (default true)", false)
				.bool("auto_tool", "Select best hotbar tool (default true)", false), "0")
				.handler(a -> taskResult(Bot.mine(pos(a), a.bool("approach", true), a.bool("pickup", true), a.bool("auto_tool", true)), a, 0)));

		t.add(waitParam(Tool.of("place_block", "Places a block at a position against an adjacent solid block.").group("action")
				.xyz("Target position", true)
				.str("item", "Item id to place (default: held item)", false)
				.enumStr("face", "Preferred support direction (side of the target where the support block is)", false, "down", "up", "north", "south", "east", "west")
				.bool("approach", "Walk into reach if needed (default true)", false), "10")
				.handler(a -> taskResult(Bot.place(pos(a), a.has("item") ? Mc.item(a.str("item")) : null, a.bool("approach", true),
						a.has("face") ? Direction.byName(a.str("face")) : null), a, 10)));

		t.add(waitParam(Tool.of("interact_block", "Right-clicks a block (open chest/crafting table/door, press button, ...).").group("action")
				.xyz("Block", true)
				.bool("approach", "Walk into reach if needed (default true)", false), "10")
				.handler(a -> taskResult(Bot.interactBlock(pos(a), a.bool("approach", true)), a, 10)));

		t.add(waitParam(Tool.of("interact_entity", "Right-clicks an entity (trade, mount, feed, ...).").group("action")
				.integer("entity_id", "Entity id", true)
				.enumStr("hand", "Hand", false, "main", "off"), "10")
				.handler(a -> taskResult(Bot.interactEntity(a.integer("entity_id"), hand(a)), a, 10)));

		t.add(waitParam(Tool.of("use_item", "Uses the held item in the air (eat, drink, throw, shoot bow...). For eating use ticks=40.").group("action")
				.enumStr("hand", "Hand", false, "main", "off")
				.integer("ticks", "Hold right-click for this many ticks (0 = single click)", false), "5")
				.handler(a -> taskResult(Bot.useItem(hand(a), a.integer("ticks", 0)), a, Math.min(a.integer("ticks", 0) / 20.0 + 2, 30))));

		t.add(waitParam(Tool.of("attack", "Attacks an entity (chases it, respects attack cooldown).").group("action")
				.integer("entity_id", "Entity id", true)
				.bool("until_dead", "Keep attacking until it dies (default true)", false)
				.integer("timeout", "Seconds (default 30)", false), "0")
				.handler(a -> taskResult(Bot.attack(a.integer("entity_id"), a.bool("until_dead", true), a.integer("timeout", 30)), a, 0)));

		t.add(Tool.of("select_slot", "Selects a hotbar slot (0-8) or the slot holding an item.").group("action")
				.integer("slot", "Hotbar slot 0-8", false)
				.str("item", "Item id to bring into the hand", false)
				.handler(a -> MainThread.call(() -> {
					LocalPlayer p = Mc.player();
					if (a.has("item")) Tools.selectItem(p, Mc.item(a.str("item")));
					else Tools.select(p, a.integer("slot"));
					return Map.of("selected_slot", p.getInventory().getSelectedSlot(), "item", String.valueOf(Mc.stack(p.getMainHandItem())));
				})));

		t.add(Tool.of("click_slot", "Clicks a slot of the open container menu (or the player inventory menu if none is open). "
				+ "Use menu_slot indices from get_screen. Modes: pickup (left/right click), quick_move (shift-click), swap (button = hotbar 0-8, 40 offhand), "
				+ "throw (button 0 one / 1 stack), pickup_all (double click), clone, quick_craft. slot -999 = outside (drop carried).").group("action")
				.integer("slot", "Menu slot index", true)
				.integer("button", "Mouse button / hotbar number (default 0)", false)
				.enumStr("mode", "Click mode (default pickup)", false, "pickup", "quick_move", "swap", "clone", "throw", "quick_craft", "pickup_all")
				.handler(a -> MainThread.call(() -> {
					LocalPlayer p = Mc.player();
					AbstractContainerMenu menu = p.containerMenu;
					ContainerInput mode = ContainerInput.valueOf(a.str("mode", "pickup").toUpperCase(Locale.ROOT));
					Mc.gameMode().handleContainerInput(menu.containerId, a.integer("slot"), a.integer("button", 0), mode, p);
					return Map.of("container_id", menu.containerId, "carried", String.valueOf(Mc.stack(menu.getCarried())));
				})));

		t.add(Tool.of("click_widget", "Clicks a widget (button, checkbox, text field) of the open screen by index or by its text.").group("action")
				.integer("index", "Widget index from get_screen", false)
				.str("text", "Widget text (case-insensitive, exact or contains)", false)
				.enumStr("button", "Mouse button", false, "left", "right")
				.num("rel_x", "For sliders: click position 0..1 across the widget", false)
				.handler(a -> MainThread.call(() -> {
					Screen s = Mc.mc().gui.screen();
					if (s == null) throw new IllegalStateException("No screen open");
					AbstractWidget w = findWidget(s, a);
					if (!w.isActive()) throw new IllegalStateException("Widget '" + w.getMessage().getString() + "' is not active");
					int btn = "right".equals(a.str("button", "left")) ? 1 : 0;
					String how;
					if (w instanceof net.minecraft.client.gui.components.AbstractButton b && btn == 0) {
						// synthetic mouse clicks are ignored by some screens; press like keyboard activation does
						w.playDownSound(Mc.mc().getSoundManager());
						b.onPress(new KeyEvent(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.enter").getValue(), 0, 0));
						how = "press";
					} else {
						double cx = w.getX() + w.getWidth() / 2.0, cy = w.getY() + w.getHeight() / 2.0;
						if (a.has("rel_x")) cx = w.getX() + a.num("rel_x") * w.getWidth();
						MouseButtonEvent ev = new MouseButtonEvent(cx, cy, new MouseButtonInfo(btn, 0));
						s.mouseClicked(ev, false);
						s.mouseReleased(ev);
						if (w instanceof net.minecraft.client.gui.components.EditBox) s.setFocused(w);
						how = "mouse";
					}
					Screen now = Mc.mc().gui.screen();
					return Map.of("clicked", w.getMessage().getString(), "via", how, "screen_now", now == null ? "none" : now.getClass().getSimpleName());
				})));

		t.add(Tool.of("click_at", "Mouse click at GUI coordinates of the open screen (same coordinate system as widget x/y in get_screen), "
				+ "e.g. to select an entry in a list. double=true for a double click.").group("action")
				.num("x", "GUI x", true).num("y", "GUI y", true)
				.enumStr("button", "Mouse button", false, "left", "right")
				.bool("double", "Double click", false)
				.handler(a -> MainThread.call(() -> {
					Screen s = Mc.mc().gui.screen();
					if (s == null) throw new IllegalStateException("No screen open");
					int btn = "right".equals(a.str("button", "left")) ? 1 : 0;
					MouseButtonEvent ev = new MouseButtonEvent(a.num("x"), a.num("y"), new MouseButtonInfo(btn, 0));
					boolean h = s.mouseClicked(ev, false);
					s.mouseReleased(ev);
					if (a.bool("double", false)) {
						h |= s.mouseClicked(ev, true);
						s.mouseReleased(ev);
					}
					Screen now = Mc.mc().gui.screen();
					return Map.of("handled", h, "screen_now", now == null ? "none" : now.getClass().getSimpleName());
				})));

		t.add(Tool.of("type_text", "Types text into the focused text field of the open screen (e.g. sign, anvil, chat screen, search).").group("action")
				.str("text", "Text to type", true)
				.bool("enter", "Press Enter afterwards", false)
				.handler(a -> MainThread.call(() -> {
					Screen s = Mc.mc().gui.screen();
					if (s == null) throw new IllegalStateException("No screen open");
					for (int cp : a.str("text").codePoints().toArray()) s.charTyped(new CharacterEvent(cp));
					if (a.bool("enter", false)) s.keyPressed(new KeyEvent(keyCode("enter"), 0, 0));
					return Map.of("ok", true);
				})));

		t.add(Tool.of("press_key", "Sends a key press to the open screen. Key names like enter, escape, backspace, tab, up, down, left, right, "
				+ "a-z, 0-9, f1 (or full names like key.keyboard.enter).").group("action")
				.str("key", "Key name", true)
				.integer("modifiers", "Modifier bits (1 shift, 2 ctrl, 4 alt)", false)
				.handler(a -> MainThread.call(() -> {
					Screen s = Mc.mc().gui.screen();
					if (s == null) throw new IllegalStateException("No screen open");
					return Map.of("handled", s.keyPressed(new KeyEvent(keyCode(a.str("key")), 0, a.integer("modifiers", 0))));
				})));

		t.add(Tool.of("open_inventory", "Opens the player inventory screen.").group("action")
				.handler(a -> MainThread.call(() -> {
					Mc.mc().gui.setScreen(new InventoryScreen(Mc.player()));
					return Map.of("ok", true);
				})));

		t.add(Tool.of("close_screen", "Closes the open screen/container.").group("action")
				.handler(a -> MainThread.call(() -> {
					Minecraft mc = Mc.mc();
					if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
					else mc.gui.setScreen(null);
					return Map.of("ok", true);
				})));

		t.add(waitParam(Tool.of("craft", "Crafts an item via the recipe book. 2x2 recipes work anywhere; 3x3 recipes need an open crafting "
				+ "table (interact_block on it first). The recipe must be unlocked.").group("action")
				.str("item", "Result item id", true)
				.integer("count", "How many results (default 1)", false), "20")
				.handler(a -> taskResult(Bot.craft(Mc.item(a.str("item")), a.integer("count", 1)), a, 20)));

		t.add(Tool.of("list_recipes", "Lists unlocked recipes producing the item and whether they are craftable now.").group("action")
				.str("item", "Result item id", true)
				.handler(a -> MainThread.call(() -> {
					var item = Mc.item(a.str("item"));
					var p = Mc.player();
					var have = new net.minecraft.world.entity.player.StackedItemContents();
					p.getInventory().fillStackedContents(have);
					List<Map<String, Object>> out = new ArrayList<>();
					for (var e : CraftTask.findRecipes(p, item)) {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("display", e.display().getClass().getSimpleName());
						m.put("craftable_with_inventory", e.canCraft(have));
						m.put("ingredients", e.craftingRequirements().map(l -> l.stream().map(String::valueOf).toList()).orElse(List.of()));
						out.add(m);
					}
					return out;
				})));

		t.add(Tool.of("chat", "Sends a chat message to the server (visible to other players!).").group("action")
				.str("message", "Message", true)
				.handler(a -> MainThread.call(() -> {
					String msg = a.str("message");
					if (msg.startsWith("/")) Mc.player().connection.sendCommand(msg.substring(1));
					else Mc.player().connection.sendChat(msg);
					return Map.of("sent", msg);
				})));

		t.add(Tool.of("run_command", "Runs a slash command (without or with leading /), e.g. \"time set day\".").group("action")
				.str("command", "Command", true)
				.handler(a -> MainThread.call(() -> {
					String c = a.str("command");
					if (c.startsWith("/")) c = c.substring(1);
					Mc.player().connection.sendCommand(c);
					return Map.of("sent", "/" + c, "hint", "check get_events/get_log for the server's answer");
				})));

		t.add(Tool.of("client_message", "Shows a message in the local chat only (not sent to the server).").group("action")
				.str("message", "Message", true)
				.handler(a -> {
					ClaudeMcp.scriptApi().say(a.str("message"));
					return Map.of("ok", true);
				}));
	}

	/** Key code (Minecraft 26.x uses SDL scancodes internally) for a key name. */
	static int keyCode(String name) {
		String n = name.toLowerCase(Locale.ROOT).trim();
		if (!n.startsWith("key.")) {
			n = switch (n) {
				case "esc" -> "escape";
				case "return" -> "enter";
				case "del" -> "delete";
				default -> n;
			};
			n = "key.keyboard." + n;
		}
		var k = com.mojang.blaze3d.platform.InputConstants.getKey(n);
		if (k == null || k.getValue() < 0 || k.getName().equals("key.keyboard.unknown")) throw new IllegalArgumentException("Unknown key: " + name);
		return k.getValue();
	}

	static AbstractWidget findWidget(Screen s, Args a) {
		List<? extends GuiEventListener> ch = s.children();
		if (a.has("index")) {
			int i = a.integer("index");
			if (i < 0 || i >= ch.size() || !(ch.get(i) instanceof AbstractWidget w)) throw new IllegalArgumentException("No widget with index " + i);
			return w;
		}
		String text = a.str("text").toLowerCase(Locale.ROOT);
		AbstractWidget contains = null;
		for (GuiEventListener l : ch) {
			if (!(l instanceof AbstractWidget w)) continue;
			String m = w.getMessage().getString().toLowerCase(Locale.ROOT);
			if (m.equals(text)) return w;
			if (contains == null && m.contains(text)) contains = w;
		}
		if (contains == null) throw new IllegalArgumentException("No widget with text '" + a.str("text") + "'");
		return contains;
	}

	// =========================================================== tasks

	static void tasks(List<Tool> t) {
		t.add(Tool.of("task_status", "Status of a task, or of all running and recent tasks if no id is given.").group("task")
				.integer("id", "Task id", false)
				.handler(a -> {
					if (a.has("id")) {
						TaskManager.Task task = ClaudeMcp.tasks().get(a.integer("id"));
						if (task == null) throw new IllegalArgumentException("Unknown task " + a.integer("id"));
						return task.toMap();
					}
					List<Object> run = new ArrayList<>(), recent = new ArrayList<>();
					for (var x : ClaudeMcp.tasks().running()) run.add(x.toMap());
					for (var x : ClaudeMcp.tasks().recent(10)) if (x.status() != TaskManager.Status.RUNNING) recent.add(x.toMap());
					return Map.of("running", run, "recent", recent);
				}));

		t.add(Tool.of("task_wait", "Waits until a task finished (or the timeout) and returns its status/result.").group("task")
				.integer("id", "Task id", true)
				.num("timeout", "Seconds (default 60, max 300)", false)
				.handler(a -> {
					TaskManager.Task task = ClaudeMcp.tasks().get(a.integer("id"));
					if (task == null) throw new IllegalArgumentException("Unknown task " + a.integer("id"));
					ClaudeMcp.tasks().await(task, (long) (Math.min(a.num("timeout", 60), 300) * 1000));
					return task.toMap();
				}));

		t.add(Tool.of("task_cancel", "Cancels a task.").group("task")
				.integer("id", "Task id", true)
				.handler(a -> Map.of("cancelled", ClaudeMcp.tasks().cancel(a.integer("id"), "cancelled by Claude"))));

		t.add(Tool.of("stop_all", "Emergency stop: cancels all tasks and scripts, releases all keys.").group("task")
				.handler(a -> Map.of("cancelled", ClaudeMcp.stopAll("stop_all by Claude"))));
	}

	// =========================================================== events & log

	static void events(List<Tool> t) {
		t.add(Tool.of("get_events", "Game events since a sequence number: chat, game_message, damage, death, respawn, screen, join, leave, "
				+ "dimension, task_end, user_override, log_error, script:*. Long-poll with wait.").group("events")
				.integer("since", "Return events with seq > since (default 0)", false)
				.strList("types", "Filter by event types", false)
				.integer("limit", "Max events (default 100)", false)
				.num("wait", "Seconds to wait for new events if none (max 30)", false)
				.handler(a -> {
					List<Object> out = new ArrayList<>();
					for (EventBuffer.Event e : ClaudeMcp.events().since(a.integer("since", 0), new HashSet<>(a.strings("types")),
							a.integer("limit", 100), (long) (Math.min(a.num("wait", 0), 30) * 1000))) {
						out.add(e.toMap());
					}
					return Map.of("events", out, "last_seq", ClaudeMcp.events().lastSeq());
				}));

		t.add(Tool.of("get_log", "Recent lines of the game log (latest.log equivalent).").group("events")
				.integer("lines", "Number of lines (default 100)", false)
				.enumStr("level", "Minimum level", false, "TRACE", "DEBUG", "INFO", "WARN", "ERROR")
				.str("contains", "Only lines containing this text", false)
				.integer("since", "Only lines with seq > since", false)
				.handler(a -> LogCapture.query(Math.min(a.integer("lines", 100), 2000), a.str("level", null), a.str("contains", null), a.integer("since", 0))));
	}

	// =========================================================== memory

	static void memory(List<Tool> t) {
		String scopeDesc = "\"world\" (default: current world/server), \"global\", or an explicit world id";
		t.add(Tool.of("memory_put", "Stores a memory entry. kind note (free text), kv (unique key -> text), waypoint (use waypoint_set).").group("memory")
				.enumStr("kind", "Entry kind (default note)", false, "note", "kv", "waypoint")
				.str("key", "Key (required for kv)", false)
				.str("text", "Text", false)
				.strList("tags", "Tags", false)
				.obj("data", "Extra structured data", false)
				.str("scope", scopeDesc, false)
				.handler(a -> ClaudeMcp.memory().put(scope(a), a.str("kind", "note"), a.str("key", null), a.str("text", null),
						a.strings("tags"), a.map("data")).toMap()));

		t.add(Tool.of("memory_get", "Gets an entry by id or key.").group("memory")
				.str("id", "Entry id or key", true).str("scope", scopeDesc, false)
				.handler(a -> {
					MemoryStore.Entry e = ClaudeMcp.memory().get(scope(a), a.str("id"));
					if (e == null) throw new IllegalArgumentException("No memory entry " + a.str("id"));
					return e.toMap();
				}));

		t.add(Tool.of("memory_search", "Searches memory by words and/or tags (empty query lists all).").group("memory")
				.str("query", "Search words", false)
				.strList("tags", "Required tags", false)
				.enumStr("kind", "Only this kind", false, "note", "kv", "waypoint")
				.integer("limit", "Max results (default 50)", false)
				.str("scope", scopeDesc, false)
				.handler(a -> {
					List<Object> out = new ArrayList<>();
					for (var e : ClaudeMcp.memory().search(scope(a), a.str("query", null), a.strings("tags"), a.str("kind", null), a.integer("limit", 50))) out.add(e.toMap());
					return Map.of("scope", scope(a), "entries", out, "all_scopes", ClaudeMcp.memory().scopes());
				}));

		t.add(Tool.of("memory_delete", "Deletes an entry by id or key.").group("memory")
				.str("id", "Entry id or key", true).str("scope", scopeDesc, false)
				.handler(a -> Map.of("deleted", ClaudeMcp.memory().delete(scope(a), a.str("id")))));

		t.add(Tool.of("waypoint_set", "Saves a named waypoint (default: current player position).").group("memory")
				.str("name", "Waypoint name", true)
				.xyz("Position", false)
				.str("note", "Note", false)
				.strList("tags", "Tags", false)
				.handler(a -> {
					BlockPos p = posOrPlayer(a);
					Map<String, Object> d = new LinkedHashMap<>();
					d.put("x", p.getX());
					d.put("y", p.getY());
					d.put("z", p.getZ());
					d.put("dimension", MainThread.call(() -> Mc.level().dimension().identifier().toString()));
					return ClaudeMcp.memory().put(scope(a), "waypoint", a.str("name"), a.str("note", null), a.strings("tags"), d).toMap();
				}));

		t.add(Tool.of("waypoint_list", "Lists waypoints of the current world with distance.").group("memory")
				.handler(a -> {
					String sc = scope(a);
					var ppos = MainThread.call(() -> Mc.inWorld() ? Mc.player().blockPosition() : null);
					List<Object> out = new ArrayList<>();
					for (var e : ClaudeMcp.memory().search(sc, null, null, "waypoint", 500)) {
						Map<String, Object> m = e.toMap();
						if (ppos != null && e.data.get("x") instanceof Number x && e.data.get("y") instanceof Number y && e.data.get("z") instanceof Number z) {
							m.put("distance", Mc.r2(Math.sqrt(ppos.distSqr(new BlockPos(x.intValue(), y.intValue(), z.intValue())))));
						}
						out.add(m);
					}
					return out;
				}));

		t.add(Tool.of("waypoint_delete", "Deletes a waypoint.").group("memory")
				.str("name", "Waypoint name", true)
				.handler(a -> Map.of("deleted", ClaudeMcp.memory().delete(scope(a), a.str("name")))));

		t.add(waitParam(Tool.of("waypoint_goto", "Walks to a saved waypoint.").group("memory")
				.str("name", "Waypoint name", true)
				.num("range", "Stop distance (default 1)", false), "0")
				.handler(a -> {
					MemoryStore.Entry e = ClaudeMcp.memory().get(scope(a), a.str("name"));
					if (e == null || !"waypoint".equals(e.kind)) throw new IllegalArgumentException("No waypoint " + a.str("name"));
					BlockPos p = new BlockPos(((Number) e.data.get("x")).intValue(), ((Number) e.data.get("y")).intValue(), ((Number) e.data.get("z")).intValue());
					return taskResult(Bot.goTo(p, a.num("range", 1), false, true, 600), a, 0);
				}));
	}

	// =========================================================== reflection

	static void requireReflection() {
		if (!ClaudeMcp.config().enableReflection) throw new IllegalStateException("Reflection is disabled in config/claudemcp/config.json (enableReflection)");
	}

	static Object onMainMaybe(Args a, MainThread.ThrowingSupplier<Object> s) throws Exception {
		requireReflection();
		if (a.bool("on_main", true)) return MainThread.call(s);
		return s.get();
	}

	static void reflection(List<Tool> t) {
		String targetDesc = "Object handle (h:N), root ($mc, $player, $level, $gameMode, $connection, $screen, $options, $gui) or a class name for static access";
		t.add(Tool.of("java_find_class", "Searches game classes by (simple) name, substring or glob (Mojang names, e.g. LocalPlayer, *Screen).").group("reflect")
				.str("pattern", "Name, substring or glob", true)
				.integer("limit", "Max results (default 50)", false)
				.handler(a -> {
					requireReflection();
					return ClaudeMcp.reflection().findClasses(a.str("pattern"), a.integer("limit", 50));
				}));

		t.add(Tool.of("java_describe", "Lists fields, methods and constructors of a class or object.").group("reflect")
				.str("target", targetDesc, true)
				.str("filter", "Only members whose name contains this", false)
				.bool("inherited", "Include superclasses (default true)", false)
				.bool("private", "Include non-public members (default true)", false)
				.integer("limit", "Max members per kind (default 200)", false)
				.handler(a -> {
					requireReflection();
					return ClaudeMcp.reflection().describe(a.str("target"), a.str("filter", null), a.bool("inherited", true), a.bool("private", true), a.integer("limit", 200));
				}));

		t.add(Tool.of("java_get_field", "Reads a field (any visibility; dotted paths like 'options.fov' allowed). Objects come back as handles.").group("reflect")
				.str("target", targetDesc, true)
				.str("field", "Field name or dotted path", true)
				.bool("pin", "Keep returned objects alive (strong handle)", false)
				.bool("on_main", "Run on the client thread (default true)", false)
				.handler(a -> onMainMaybe(a, () -> ClaudeMcp.json().toJson(ClaudeMcp.reflection().getField(a.str("target"), a.str("field")), a.bool("pin", false)))));

		t.add(Tool.of("java_set_field", "Writes a field. Value: JSON primitive, string enum name, handle string h:N/$root, or list for arrays.").group("reflect")
				.str("target", targetDesc, true)
				.str("field", "Field name", true)
				.any("value", "New value", true)
				.bool("on_main", "Run on the client thread (default true)", false)
				.handler(a -> onMainMaybe(a, () -> Map.of("old", String.valueOf(ClaudeMcp.json().toJson(
						ClaudeMcp.reflection().setField(a.str("target"), a.str("field"), a.get("value")), false))))));

		t.add(Tool.of("java_invoke", "Invokes a method on an object or a static method on a class. Overloads are chosen by argument types; "
				+ "pass 'signature' like \"(int,java.lang.String)\" to force one. Args: JSON values, enum names, handles (h:N, $player).").group("reflect")
				.str("target", targetDesc, true)
				.str("method", "Method name", true)
				.anyList("args", "Arguments", false)
				.str("signature", "Explicit parameter types", false)
				.bool("pin", "Keep returned object alive", false)
				.bool("on_main", "Run on the client thread (default true)", false)
				.handler(a -> onMainMaybe(a, () -> {
					Object r = ClaudeMcp.reflection().invoke(a.str("target"), a.str("method"), a.list("args"), a.str("signature", null));
					if (r == Reflection.VOID) return Map.of("void", true);
					return ClaudeMcp.json().toJson(r, a.bool("pin", false));
				})));

		t.add(Tool.of("java_new", "Creates an object via a constructor. Result is a pinned handle.").group("reflect")
				.str("class", "Class name", true)
				.anyList("args", "Constructor arguments", false)
				.str("signature", "Explicit parameter types", false)
				.bool("on_main", "Run on the client thread (default true)", false)
				.handler(a -> onMainMaybe(a, () -> ClaudeMcp.json().toJson(ClaudeMcp.reflection().construct(a.str("class"), a.list("args"), a.str("signature", null)), true))));

		t.add(Tool.of("handle_release", "Releases handles (or all with all=true).").group("reflect")
				.strList("handles", "Handles to release", false)
				.bool("all", "Release all handles", false)
				.handler(a -> {
					if (a.bool("all", false)) return Map.of("released", ClaudeMcp.handles().clear());
					int n = 0;
					for (String h : a.strings("handles")) if (ClaudeMcp.handles().release(h)) n++;
					return Map.of("released", n, "remaining", ClaudeMcp.handles().size());
				}));
	}

	// =========================================================== scripts

	static void scripts(List<Tool> t) {
		t.add(Tool.of("script_eval", "Runs Groovy code inside the client. Variables: mc, player, level, gameMode, screen (live), api "
				+ "(walkTo/mine/place/craft/attack/chat/command/sleepTicks/onMain{}/state()/findBlocks/keys/...), memory, args, out (println). "
				+ "Default: worker thread task (touch game objects inside api.onMain { }), returns result if done within 'wait'. "
				+ "sync=true runs directly on the client thread (time limit timeout_ms, default 250 ms, no sleeping). The last expression is the result.").group("script")
				.str("code", "Groovy source", true)
				.bool("sync", "Run on the client thread (short code only)", false)
				.integer("timeout_ms", "Time limit for sync=true (default 250, max 2000)", false)
				.obj("args", "Variables passed as 'args'", false)
				.num("wait", "Seconds to wait for the result (default 10, max 120)", false)
				.integer("timeout", "Script timeout in seconds (default from config, 300)", false)
				.handler(a -> {
					if (a.bool("sync", false)) return ClaudeMcp.scripts().runSync(a.str("code"), a.map("args"), Math.max(10, Math.min(a.integer("timeout_ms", 250), 2000)));
					TaskManager.Task task = ClaudeMcp.scripts().startAsync("eval", a.str("code"), a.map("args"), a.integer("timeout", ClaudeMcp.config().scriptTimeoutSeconds));
					return taskResult(task, a, 10);
				}));

		t.add(Tool.of("script_save", "Saves a named Groovy script (config/claudemcp/scripts/<name>.groovy).").group("script")
				.str("name", "Name [A-Za-z0-9_-]", true)
				.str("code", "Groovy source", true)
				.str("description", "What it does", false)
				.bool("autoload", "Run automatically when the game starts (default false)", false)
				.handler(a -> {
					ClaudeMcp.scripts().save(a.str("name"), a.str("code"), a.str("description", null), a.bool("autoload", false));
					return Map.of("saved", a.str("name"));
				}));

		t.add(Tool.of("script_run", "Runs a saved script as a task.").group("script")
				.str("name", "Script name", true)
				.obj("args", "Variables passed as 'args'", false)
				.num("wait", "Seconds to wait (default 10)", false)
				.integer("timeout", "Timeout seconds", false)
				.handler(a -> taskResult(ClaudeMcp.scripts().startAsync("script:" + a.str("name"), ClaudeMcp.scripts().code(a.str("name")),
						a.map("args"), a.integer("timeout", ClaudeMcp.config().scriptTimeoutSeconds)), a, 10)));

		t.add(Tool.of("script_list", "Lists saved scripts.").group("script")
				.handler(a -> ClaudeMcp.scripts().list()));

		t.add(Tool.of("script_get", "Returns the source of a saved script.").group("script")
				.str("name", "Script name", true)
				.handler(a -> ClaudeMcp.scripts().code(a.str("name"))));

		t.add(Tool.of("script_delete", "Deletes a saved script.").group("script")
				.str("name", "Script name", true)
				.handler(a -> Map.of("deleted", ClaudeMcp.scripts().delete(a.str("name")))));

		t.add(Tool.of("hook_register", "Registers Groovy code that runs on the client thread whenever an event happens. Events: "
				+ String.join(", ", new TreeSet<>(HookManager.EVENTS)) + ". The variable 'event' holds the event data; for 'hud' "
				+ "the variables g (GuiGraphicsExtractor) and font are set (e.g. g.text(font, 'hi', 5, 5, (int) 0xFFFFFFFFL) - cast ARGB colors to int); for 'key' event.key/action. "
				+ "Hooks must be fast (50 ms limit, first calls 500 ms for warm-up), they are disabled after 5 consecutive errors.").group("script")
				.enumStr("event", "Event", true, HookManager.EVENTS.stream().sorted().toArray(String[]::new))
				.str("code", "Groovy source", true)
				.str("id", "Hook id (replaces an existing hook with that id)", false)
				.str("description", "What it does", false)
				.bool("persist", "Save the hook to disk (default false)", false)
				.bool("autoload", "When persisted: load it on game start (default true)", false)
				.handler(a -> {
					var d = ClaudeMcp.hooks().register(a.str("event"), a.str("code"), a.str("id", null), a.str("description", null),
							a.bool("persist", false), a.bool("autoload", true));
					return Map.of("id", d.id, "event", d.event, "persist", d.persist);
				}));

		t.add(Tool.of("hook_list", "Lists hooks with call/error counters.").group("script")
				.handler(a -> ClaudeMcp.hooks().list()));

		t.add(Tool.of("hook_remove", "Removes a hook (also from disk).").group("script")
				.str("id", "Hook id", true)
				.handler(a -> Map.of("removed", ClaudeMcp.hooks().remove(a.str("id")))));
	}
}
