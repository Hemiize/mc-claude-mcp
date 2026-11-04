package dev.leon.claudemcp.control;

import dev.leon.claudemcp.ClaudeMcp;
import dev.leon.claudemcp.core.MainThread;
import dev.leon.claudemcp.core.TaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;

/** Starts the high-level action tasks. Thread safe (switches to the client thread). */
public final class Bot {
	private Bot() {}

	private static TaskManager tasks() {
		return ClaudeMcp.tasks();
	}

	public static TaskManager.Task goTo(BlockPos pos, double range, boolean allowBreak, boolean sprint, int timeoutSeconds) {
		return MainThread.call(() -> {
			Mc.player();
			return tasks().startTick("goto", "goto " + pos.toShortString() + (range > 0 ? " ~" + range : ""),
					new GotoTask(pos, range, allowBreak, sprint, timeoutSeconds * 20));
		});
	}

	public static TaskManager.Task follow(int entityId, double distance, int timeoutSeconds) {
		return MainThread.call(() -> {
			Mc.entity(entityId);
			GotoTask g = new GotoTask(() -> {
				var e = Mc.level().getEntity(entityId);
				if (e == null || !e.isAlive()) return null;
				return e.blockPosition();
			}, distance, false, true, Integer.MAX_VALUE).budget(6000, 1000).sub();
			TaskManager.TickTask t = new TaskManager.TickTask() {
				@Override
				public boolean tick(TaskManager.Task task) {
					var e = Mc.level().getEntity(entityId);
					if (e == null || !e.isAlive()) throw new IllegalStateException("entity gone");
					if (task.ticks() > timeoutSeconds * 20) return true;
					if (Mc.player().distanceTo(e) <= distance + 0.5) {
						BotInput.clearKeys();
						task.progress("near " + Mc.id(e) + ", waiting");
						return false;
					}
					BotInput.activate();
					try {
						g.tick(task);
					} catch (IllegalStateException ex) {
						// keep following even if one path failed
					}
					return false;
				}

				@Override
				public void stop(TaskManager.Task task) {
					g.stop(task);
				}
			};
			return tasks().startTick("follow", "follow entity " + entityId, t);
		});
	}

	public static TaskManager.Task mine(BlockPos pos, boolean approach, boolean pickup, boolean autoTool) {
		return MainThread.call(() -> tasks().startTick("mine", "mine " + pos.toShortString(), new Actions.Mine(pos, approach, pickup, autoTool)));
	}

	public static TaskManager.Task place(BlockPos pos, Item item, boolean approach, Direction face) {
		return MainThread.call(() -> tasks().startTick("place", "place " + (item == null ? "held item" : Mc.id(item)) + " at " + pos.toShortString(),
				new Actions.Place(pos, item, approach, face)));
	}

	public static TaskManager.Task interactBlock(BlockPos pos, boolean approach) {
		return MainThread.call(() -> tasks().startTick("interact_block", "use block " + pos.toShortString(), new Actions.InteractBlock(pos, approach)));
	}

	public static TaskManager.Task interactEntity(int id, InteractionHand hand) {
		return MainThread.call(() -> tasks().startTick("interact_entity", "use entity " + id, new Actions.InteractEntity(id, hand)));
	}

	public static TaskManager.Task useItem(InteractionHand hand, int ticks) {
		return MainThread.call(() -> tasks().startTick("use_item", "use item " + hand + (ticks > 0 ? " for " + ticks + " ticks" : ""), new Actions.UseItem(hand, ticks)));
	}

	public static TaskManager.Task attack(int id, boolean untilDead, int timeoutSeconds) {
		return MainThread.call(() -> tasks().startTick("attack", "attack entity " + id, new Actions.Attack(id, untilDead, timeoutSeconds * 20)));
	}

	public static TaskManager.Task craft(Item item, int count) {
		return MainThread.call(() -> tasks().startTick("craft", "craft " + count + "x " + Mc.id(item), new CraftTask(item, count)));
	}

	public static TaskManager.Task lookAt(double x, double y, double z) {
		return MainThread.call(() -> tasks().startTick("look_at", "look at " + x + "," + y + "," + z, new TaskManager.TickTask() {
			@Override
			public boolean tick(TaskManager.Task task) {
				return Look.turnTowards(Mc.player(), new net.minecraft.world.phys.Vec3(x, y, z), true) || task.ticks() > 40;
			}

			@Override
			public boolean usesBody() {
				return false;
			}
		}));
	}

	/** Waits (worker thread) until the task ends and returns its map. */
	public static java.util.Map<String, Object> await(TaskManager.Task t, long timeoutMs) throws InterruptedException {
		tasks().await(t, timeoutMs);
		return t.toMap();
	}
}
