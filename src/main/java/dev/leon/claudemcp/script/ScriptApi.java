package dev.leon.claudemcp.script;

import dev.leon.claudemcp.ClaudeMcp;
import dev.leon.claudemcp.control.*;
import dev.leon.claudemcp.core.MainThread;
import dev.leon.claudemcp.core.TaskManager;
import dev.leon.claudemcp.memory.MemoryStore;
import groovy.lang.Closure;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;

import java.util.*;

/**
 * The {@code api} object available in Groovy scripts. High-level methods block until the action
 * finished (worker threads only); use {@code onMain { ... }} to touch game objects safely.
 */
public final class ScriptApi {
	/** Runs the closure on the client thread and returns its result. */
	public Object onMain(Closure<?> c) {
		return MainThread.call(c::call);
	}

	public void sleepTicks(long n) throws InterruptedException {
		TickClock.sleepTicks(n);
	}

	public void sleep(long ms) throws InterruptedException {
		long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end) {
			Guard.check();
			Thread.sleep(Math.min(50, Math.max(1, end - System.currentTimeMillis())));
		}
	}

	public void checkCancelled() {
		Guard.check();
	}

	public Map<String, Object> state() {
		return MainThread.call(Perception::state);
	}

	public Map<String, Object> inventory() {
		return MainThread.call(Perception::inventory);
	}

	public List<Map<String, Object>> entities(double radius) {
		return MainThread.call(() -> Perception.entities(radius, null, 100));
	}

	public List<Map<String, Object>> findBlocks(String id, int radius, int limit) {
		Block b = Mc.block(id);
		return MainThread.call(() -> Perception.findBlocks(Set.of(b), radius, limit));
	}

	public Map<String, Object> waitTask(TaskManager.Task t) throws InterruptedException {
		while (t.status() == TaskManager.Status.RUNNING) {
			Guard.check();
			ClaudeMcp.tasks().await(t, 200);
		}
		return t.toMap();
	}

	private Map<String, Object> run(TaskManager.Task t) throws InterruptedException {
		try {
			return waitTask(t);
		} catch (RuntimeException e) {
			ClaudeMcp.tasks().cancel(t.id, "script cancelled");
			throw e;
		}
	}

	public Map<String, Object> walkTo(int x, int y, int z) throws InterruptedException {
		return walkTo(x, y, z, 0);
	}

	public Map<String, Object> walkTo(int x, int y, int z, double range) throws InterruptedException {
		return run(Bot.goTo(new BlockPos(x, y, z), range, false, true, 300));
	}

	public Map<String, Object> mine(int x, int y, int z) throws InterruptedException {
		return run(Bot.mine(new BlockPos(x, y, z), true, true, true));
	}

	public Map<String, Object> place(int x, int y, int z, String item) throws InterruptedException {
		return run(Bot.place(new BlockPos(x, y, z), item == null ? null : Mc.item(item), true, null));
	}

	public Map<String, Object> use(int x, int y, int z) throws InterruptedException {
		return run(Bot.interactBlock(new BlockPos(x, y, z), true));
	}

	public Map<String, Object> useItem(int ticks) throws InterruptedException {
		return run(Bot.useItem(InteractionHand.MAIN_HAND, ticks));
	}

	public Map<String, Object> attack(int entityId) throws InterruptedException {
		return run(Bot.attack(entityId, true, 30));
	}

	public Map<String, Object> craft(String item, int count) throws InterruptedException {
		return run(Bot.craft(Mc.item(item), count));
	}

	public void select(int slot) {
		MainThread.run(() -> Tools.select(Mc.player(), slot));
	}

	public void selectItem(String item) {
		MainThread.run(() -> Tools.selectItem(Mc.player(), Mc.item(item)));
	}

	public void look(double x, double y, double z) {
		MainThread.run(() -> {
			var p = Mc.player();
			var eye = p.getEyePosition();
			var t = new net.minecraft.world.phys.Vec3(x, y, z);
			p.setYRot(Look.yawTo(eye, t));
			p.setXRot(Look.pitchTo(eye, t));
		});
	}

	/** Sets simulated movement keys (forward/backward/left/right/jump/sneak/sprint). */
	public void keys(Map<String, Boolean> k) {
		MainThread.run(() -> {
			BotInput.activate();
			BotInput.forward = k.getOrDefault("forward", false);
			BotInput.backward = k.getOrDefault("backward", false);
			BotInput.left = k.getOrDefault("left", false);
			BotInput.right = k.getOrDefault("right", false);
			BotInput.jump = k.getOrDefault("jump", false);
			BotInput.sneak = k.getOrDefault("sneak", false);
			BotInput.sprint = k.getOrDefault("sprint", false);
		});
	}

	public void releaseKeys() {
		MainThread.run(BotInput::release);
	}

	public void chat(String msg) {
		MainThread.run(() -> Mc.player().connection.sendChat(msg));
	}

	public void command(String cmd) {
		String c = cmd.startsWith("/") ? cmd.substring(1) : cmd;
		MainThread.run(() -> Mc.player().connection.sendCommand(c));
	}

	/** Shows a message in the local chat (not sent to the server). */
	public void say(String msg) {
		MainThread.run(() -> Mc.mc().gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal("[Claude] " + msg)));
	}

	public void emit(String type, Map<String, Object> data) {
		ClaudeMcp.events().emit("script:" + type, data);
	}

	/** Registers an object as handle so Claude can use it with the java_* tools. */
	public String handle(Object o) {
		return ClaudeMcp.handles().put(o, true);
	}

	public Object resolve(String handle) {
		return ClaudeMcp.handles().require(handle);
	}

	public MemoryApi memory() {
		return memoryApi;
	}

	private final MemoryApi memoryApi = new MemoryApi();

	public static final class MemoryApi {
		private String scope() {
			return MainThread.call(Perception::worldId);
		}

		public Map<String, Object> put(String key, String text) {
			return ClaudeMcp.memory().put(scope(), "kv", key, text, null, null).toMap();
		}

		public String get(String key) {
			MemoryStore.Entry e = ClaudeMcp.memory().get(scope(), key);
			return e == null ? null : e.text;
		}

		public Map<String, Object> note(String text, List<String> tags) {
			return ClaudeMcp.memory().put(scope(), "note", null, text, tags, null).toMap();
		}

		public List<Map<String, Object>> search(String query) {
			List<Map<String, Object>> out = new ArrayList<>();
			for (MemoryStore.Entry e : ClaudeMcp.memory().search(scope(), query, null, null, 50)) out.add(e.toMap());
			return out;
		}

		public Map<String, Object> waypoint(String name, int x, int y, int z) {
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("x", x);
			d.put("y", y);
			d.put("z", z);
			d.put("dimension", MainThread.call(() -> Mc.level().dimension().identifier().toString()));
			return ClaudeMcp.memory().put(scope(), "waypoint", name, null, null, d).toMap();
		}
	}
}
