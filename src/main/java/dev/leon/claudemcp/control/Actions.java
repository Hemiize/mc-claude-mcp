package dev.leon.claudemcp.control;

import dev.leon.claudemcp.core.TaskManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Tick tasks for interactions: mining, placing, using, attacking. */
public final class Actions {
	private Actions() {}

	static final double REACH = 4.4;

	/** Approach helper: walks into reach of a block if needed. Returns true once in reach. */
	static final class Approach {
		private GotoTask go;
		private final double range;

		Approach(double range) {
			this.range = range;
		}

		boolean inReach(LocalPlayer p, BlockPos pos, TaskManager.Task task, boolean allowed) {
			if (Mc.eyeDistance(p, pos) <= REACH) {
				if (go != null) {
					go.stop(task);
					go = null;
					BotInput.clearKeys();
				}
				return true;
			}
			if (!allowed) throw new IllegalStateException("Target " + pos.toShortString() + " is out of reach ("
					+ Mc.r2(Mc.eyeDistance(p, pos)) + " blocks) and approach=false");
			if (go == null) go = new GotoTask(pos, range, false, true, 20 * 120).sub();
			go.tick(task);
			return Mc.eyeDistance(p, pos) <= REACH;
		}

		void stop(TaskManager.Task task) {
			if (go != null) go.stop(task);
		}
	}

	// ------------------------------------------------------------------ mine

	public static final class Mine implements TaskManager.TickTask {
		private final BlockPos pos;
		private final boolean approach, pickup, autoTool;
		private final Approach app = new Approach(3);
		private int phase; // 0 approach, 1 mine, 2 pickup
		private int mineTicks;
		private boolean started;
		private String blockId;
		private GotoTask pickupGo;
		private int pickupTicks;

		public Mine(BlockPos pos, boolean approach, boolean pickup, boolean autoTool) {
			this.pos = pos;
			this.approach = approach;
			this.pickup = pickup;
			this.autoTool = autoTool;
		}

		@Override
		public boolean tick(TaskManager.Task task) {
			LocalPlayer p = Mc.player();
			BlockState s = Mc.level().getBlockState(pos);
			if (blockId == null) {
				if (s.isAir()) throw new IllegalStateException("Block at " + pos.toShortString() + " is already air");
				blockId = Mc.id(s.getBlock());
			}
			switch (phase) {
				case 0 -> {
					task.progress("approaching " + blockId);
					if (app.inReach(p, pos, task, approach)) phase = 1;
				}
				case 1 -> {
					if (s.isAir() || !s.getFluidState().isEmpty() && s.getCollisionShape(Mc.level(), pos).isEmpty()) {
						Mc.gameMode().stopDestroyBlock();
						task.setResult(Map.of("mined", blockId, "pos", Mc.pos(pos)));
						if (!pickup) return true;
						phase = 2;
						return false;
					}
					if (Mc.eyeDistance(p, pos) > REACH + 0.6) {
						phase = 0;
						return false;
					}
					BotInput.activate();
					BotInput.clearKeys();
					boolean looking = Look.turnTowards(p, Vec3.atCenterOf(pos), true);
					if (!looking && mineTicks < 10 && !started) {
						mineTicks++;
						return false;
					}
					Direction face = Mc.faceTowards(pos, p.getEyePosition());
					if (!started) {
						if (autoTool) Tools.selectBestTool(p, s);
						Mc.gameMode().startDestroyBlock(pos, face);
						started = true;
					} else {
						Mc.gameMode().continueDestroyBlock(pos, face);
					}
					Mc.swing(p, InteractionHand.MAIN_HAND);
					task.progress("mining " + blockId + " (" + Mc.gameMode().getDestroyStage() + "/10)");
					if (++mineTicks > 20 * 60) throw new IllegalStateException("mining took too long (wrong tool?)");
				}
				case 2 -> {
					// walk over nearby dropped items
					if (++pickupTicks > 20 * 8) return true;
					ItemEntity item = nearestItem(p, pos, 5);
					if (item == null) return pickupTicks > 10;
					if (pickupGo == null || pickupTicks % 20 == 0) {
						BlockPos ip = item.blockPosition();
						pickupGo = new GotoTask(ip, 0.5, false, false, 20 * 8).budget(1500, 1).sub();
					}
					task.progress("picking up " + Mc.id(item.getItem().getItem()));
					try {
						if (pickupGo.tick(task)) pickupGo = null;
					} catch (IllegalStateException e) {
						return true;
					}
				}
			}
			return false;
		}

		@Override
		public void stop(TaskManager.Task task) {
			app.stop(task);
			if (pickupGo != null) pickupGo.stop(task);
			if (started && Mc.inWorld()) Mc.gameMode().stopDestroyBlock();
			BotInput.clearKeys();
		}
	}

	static ItemEntity nearestItem(LocalPlayer p, BlockPos around, double r) {
		AABB box = new AABB(around).inflate(r);
		ItemEntity best = null;
		double bd = Double.MAX_VALUE;
		for (ItemEntity e : Mc.level().getEntitiesOfClass(ItemEntity.class, box, Entity::isAlive)) {
			double d = e.distanceToSqr(p);
			if (d < bd) {
				bd = d;
				best = e;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ place

	public static final class Place implements TaskManager.TickTask {
		private final BlockPos pos;
		private final Item item;
		private final boolean approach;
		private final Direction preferFace;
		private final Approach app = new Approach(3);
		private int phase, ticks;
		private BlockHitResult hit;

		public Place(BlockPos pos, Item item, boolean approach, Direction preferFace) {
			this.pos = pos;
			this.item = item;
			this.approach = approach;
			this.preferFace = preferFace;
		}

		@Override
		public boolean tick(TaskManager.Task task) {
			LocalPlayer p = Mc.player();
			if (++ticks > 20 * 150) throw new IllegalStateException("place timed out");
			switch (phase) {
				case 0 -> {
					task.progress("approaching");
					if (app.inReach(p, pos, task, approach)) phase = 1;
				}
				case 1 -> {
					BlockState s = Mc.level().getBlockState(pos);
					if (!s.canBeReplaced()) throw new IllegalStateException("Position " + pos.toShortString() + " is occupied by " + Mc.id(s.getBlock()));
					if (p.getBoundingBox().intersects(new AABB(pos))) throw new IllegalStateException("Player is standing in the target position");
					if (item != null) Tools.selectItem(p, item);
					if (p.getMainHandItem().isEmpty()) throw new IllegalStateException("Nothing in hand to place");
					hit = findSupport(pos, preferFace);
					if (hit == null) throw new IllegalStateException("No adjacent solid block to place against at " + pos.toShortString());
					phase = 2;
				}
				case 2 -> {
					BotInput.activate();
					BotInput.clearKeys();
					boolean looking = Look.turnTowards(p, hit.getLocation(), true);
					if (!looking && ticks < 400 && task.state.merge("look", 1, (a, b) -> (Integer) a + 1) instanceof Integer n && n < 12) return false;
					InteractionResult r = Mc.gameMode().useItemOn(p, InteractionHand.MAIN_HAND, hit);
					Mc.swing(p, InteractionHand.MAIN_HAND);
					task.state.put("result", r.toString());
					phase = 3;
				}
				case 3 -> {
					BlockState s = Mc.level().getBlockState(pos);
					if (!s.canBeReplaced()) {
						task.setResult(Map.of("placed", Mc.id(s.getBlock()), "pos", Mc.pos(pos)));
						return true;
					}
					if (task.state.merge("wait", 1, (a, b) -> (Integer) a + 1) instanceof Integer n && n > 10) {
						throw new IllegalStateException("placement had no effect (" + task.state.get("result") + ")");
					}
				}
			}
			return false;
		}

		@Override
		public void stop(TaskManager.Task task) {
			app.stop(task);
			BotInput.clearKeys();
		}
	}

	static BlockHitResult findSupport(BlockPos pos, Direction prefer) {
		List<Direction> order = new ArrayList<>(List.of(Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP));
		if (prefer != null) {
			order.remove(prefer);
			order.addFirst(prefer);
		}
		for (Direction d : order) {
			BlockPos n = pos.relative(d);
			BlockState ns = Mc.level().getBlockState(n);
			if (ns.canBeReplaced() || ns.getCollisionShape(Mc.level(), n).isEmpty()) continue;
			Vec3 faceCenter = Vec3.atCenterOf(pos).add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
			return new BlockHitResult(faceCenter, d.getOpposite(), n, false);
		}
		return null;
	}

	// ------------------------------------------------------------------ interact with block

	public static final class InteractBlock implements TaskManager.TickTask {
		private final BlockPos pos;
		private final boolean approach;
		private final Approach app = new Approach(3);
		private int phase, look;

		public InteractBlock(BlockPos pos, boolean approach) {
			this.pos = pos;
			this.approach = approach;
		}

		@Override
		public boolean tick(TaskManager.Task task) {
			LocalPlayer p = Mc.player();
			if (phase == 0) {
				task.progress("approaching");
				if (app.inReach(p, pos, task, approach)) phase = 1;
				return false;
			}
			BotInput.activate();
			BotInput.clearKeys();
			Direction face = Mc.faceTowards(pos, p.getEyePosition());
			Vec3 at = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			if (!Look.turnTowards(p, at, true) && look++ < 12) return false;
			InteractionResult r = Mc.gameMode().useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(at, face, pos, false));
			Mc.swing(p, InteractionHand.MAIN_HAND);
			task.setResult(Map.of("result", r.toString(), "block", Mc.id(Mc.level().getBlockState(pos).getBlock())));
			return true;
		}

		@Override
		public void stop(TaskManager.Task task) {
			app.stop(task);
			BotInput.clearKeys();
		}
	}

	// ------------------------------------------------------------------ use item (air)

	public static final class UseItem implements TaskManager.TickTask {
		private final InteractionHand hand;
		private final int duration;
		private boolean started;

		public UseItem(InteractionHand hand, int durationTicks) {
			this.hand = hand;
			this.duration = durationTicks;
		}

		@Override
		public boolean tick(TaskManager.Task task) {
			LocalPlayer p = Mc.player();
			KeyMapping use = Mc.mc().options.keyUse;
			if (!started) {
				started = true;
				InteractionResult r = Mc.gameMode().useItem(p, hand);
				task.state.put("result", r.toString());
				if (duration <= 0) {
					task.setResult(Map.of("result", r.toString()));
					return true;
				}
				use.setDown(true);
			}
			task.progress("using item " + task.ticks() + "/" + duration);
			if (task.ticks() >= duration) {
				task.setResult(Map.of("result", String.valueOf(task.state.get("result")), "still_using", p.isUsingItem()));
				return true;
			}
			return false;
		}

		@Override
		public void stop(TaskManager.Task task) {
			Mc.mc().options.keyUse.setDown(false);
			if (Mc.inWorld() && Mc.player().isUsingItem()) Mc.gameMode().releaseUsingItem(Mc.player());
		}
	}

	// ------------------------------------------------------------------ entities

	static final class Chase {
		private GotoTask go;
		private BlockPos last;

		/** Moves towards the entity until within dist. Returns true when close enough. */
		boolean near(LocalPlayer p, Entity e, double dist, TaskManager.Task task) {
			if (p.distanceTo(e) <= dist) {
				if (go != null) {
					go.stop(task);
					go = null;
				}
				BotInput.clearKeys();
				return true;
			}
			BlockPos ep = e.blockPosition();
			if (go == null || last == null || last.distSqr(ep) > 4) {
				go = new GotoTask(ep, Math.max(1, dist - 1), false, true, 20 * 60).budget(6000, 3).sub();
				last = ep;
			}
			go.tick(task);
			return false;
		}

		void stop(TaskManager.Task task) {
			if (go != null) go.stop(task);
		}
	}

	public static final class InteractEntity implements TaskManager.TickTask {
		private final int entityId;
		private final InteractionHand hand;
		private final Chase chase = new Chase();

		public InteractEntity(int entityId, InteractionHand hand) {
			this.entityId = entityId;
			this.hand = hand;
		}

		@Override
		public boolean tick(TaskManager.Task task) {
			LocalPlayer p = Mc.player();
			Entity e = Mc.entity(entityId);
			BotInput.activate();
			if (!chase.near(p, e, 3.0, task)) return false;
			Look.turnTowards(p, e.getBoundingBox().getCenter(), true);
			InteractionResult r = Mc.gameMode().interact(p, e, new EntityHitResult(e, e.getBoundingBox().getCenter()), hand);
			Mc.swing(p, hand);
			task.setResult(Map.of("result", r.toString(), "entity", Mc.id(e)));
			return true;
		}

		@Override
		public void stop(TaskManager.Task task) {
			chase.stop(task);
			BotInput.clearKeys();
		}
	}

	public static final class Attack implements TaskManager.TickTask {
		private final int entityId;
		private final boolean untilDead;
		private final int maxTicks;
		private final Chase chase = new Chase();
		private int hits;

		public Attack(int entityId, boolean untilDead, int maxTicks) {
			this.entityId = entityId;
			this.untilDead = untilDead;
			this.maxTicks = maxTicks;
		}

		@Override
		public boolean tick(TaskManager.Task task) {
			LocalPlayer p = Mc.player();
			Entity e = Mc.level().getEntity(entityId);
			if (e == null || !e.isAlive()) {
				task.setResult(Map.of("hits", hits, "target_dead_or_gone", true));
				return true;
			}
			if (task.ticks() > maxTicks) {
				task.setResult(Map.of("hits", hits, "timeout", true));
				return true;
			}
			BotInput.activate();
			if (!chase.near(p, e, 2.8, task)) {
				task.progress("chasing " + Mc.id(e));
				return false;
			}
			Vec3 aim = e.getBoundingBox().getCenter().add(0, e.getBbHeight() * 0.2, 0);
			Look.turnTowards(p, aim, true);
			if (p.getAttackStrengthScale(0.5f) >= 0.95f) {
				Mc.gameMode().attack(p, e);
				Mc.swing(p, InteractionHand.MAIN_HAND);
				hits++;
				String hp = e instanceof LivingEntity le ? " hp=" + Mc.r2(le.getHealth()) : "";
				task.progress("hit " + hits + hp);
				if (!untilDead) {
					task.setResult(Map.of("hits", hits));
					return true;
				}
			}
			return false;
		}

		@Override
		public void stop(TaskManager.Task task) {
			chase.stop(task);
			BotInput.clearKeys();
		}
	}
}
