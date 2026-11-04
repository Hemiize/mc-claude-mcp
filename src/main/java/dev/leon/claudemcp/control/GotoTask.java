package dev.leon.claudemcp.control;

import dev.leon.claudemcp.core.TaskManager;
import dev.leon.claudemcp.path.Pathfinder;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Supplier;

/** Walks to a target using the A* pathfinder and simulated keyboard input. */
public final class GotoTask implements TaskManager.TickTask {
	private Supplier<BlockPos> target;
	private final double range;
	private final boolean allowBreak;
	private final boolean sprint;
	private int maxReplans;
	private final int timeoutTicks;
	private int maxNodes = 20000;
	private boolean sub;
	private BlockPos goal;
	private boolean surfacing;

	private List<Pathfinder.Step> path;
	private boolean complete;
	private int idx;
	private int replans;
	private double bestDist = Double.MAX_VALUE;
	private int noProgress;
	private BlockPos plannedFor;
	private BlockPos breaking;
	private int breakTicks;
	private int ticks;

	public GotoTask(BlockPos target, double range, boolean allowBreak, boolean sprint, int timeoutTicks) {
		this(() -> target, range, allowBreak, sprint, timeoutTicks);
	}

	public GotoTask(Supplier<BlockPos> target, double range, boolean allowBreak, boolean sprint, int timeoutTicks) {
		this.target = target;
		this.range = range;
		this.allowBreak = allowBreak;
		this.sprint = sprint;
		this.maxReplans = 8;
		this.timeoutTicks = timeoutTicks;
	}

	/** Limits the search effort (used for cheap sub-goals like item pickup). */
	public GotoTask budget(int nodes, int replans) {
		this.maxNodes = nodes;
		this.maxReplans = replans;
		return this;
	}

	/** Used inside another task: don't overwrite the parent's result. */
	public GotoTask sub() {
		this.sub = true;
		return this;
	}

	/** For exact targets inside the ground/air: nearest standable position in the same column. */
	private static BlockPos adjust(McPathWorld w, Pathfinder pf, BlockPos t) {
		for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5}) {
			if (pf.standable(t.getX(), t.getY() + dy, t.getZ())) return t.offset(0, dy, 0);
		}
		return t;
	}

	public static boolean within(LocalPlayer p, BlockPos t, double range) {
		Vec3 feet = p.position();
		double dx = feet.x - (t.getX() + 0.5), dy = feet.y - t.getY(), dz = feet.z - (t.getZ() + 0.5);
		return Math.sqrt(dx * dx + dy * dy + dz * dz) <= Math.max(range, 0.0) + 0.6;
	}

	private void plan(LocalPlayer p, TaskManager.Task task) {
		BlockPos t = target.get();
		BlockPos start = p.blockPosition();
		// standing on a slab etc.: blockPosition is the block of the feet, good enough
		Pathfinder.Options o = new Pathfinder.Options();
		o.allowBreak = allowBreak;
		o.range = range;
		o.maxNodes = maxNodes;
		McPathWorld w = new McPathWorld(Mc.level(), p);
		Pathfinder pf = new Pathfinder(w, o);
		goal = range <= 0 ? adjust(w, pf, t) : t;
		if (range <= 0 && !pf.standable(goal.getX(), goal.getY(), goal.getZ()) && w.kind(goal.getX(), goal.getY(), goal.getZ()) != dev.leon.claudemcp.path.PathWorld.Kind.UNKNOWN && !allowBreak) {
			throw new IllegalStateException("target " + t.toShortString() + " is inside blocks or in mid-air (no standable block within 5 above/below). "
					+ "Use range > 0, allow_break, or pick a surface position (find_blocks/get_blocks).");
		}
		Pathfinder.Result r = pf.find(start.getX(), start.getY(), start.getZ(), goal.getX(), goal.getY(), goal.getZ());
		path = r.steps();
		complete = r.complete();
		idx = 1;
		bestDist = Double.MAX_VALUE;
		noProgress = 0;
		plannedFor = t;
		task.progress("path " + (path.size() - 1) + " steps" + (complete ? "" : " (partial)") + ", explored " + r.explored()
				+ (replans > 0 ? ", replan " + replans : ""));
	}

	@Override
	public boolean tick(TaskManager.Task task) {
		LocalPlayer p = Mc.player();
		if (++ticks > timeoutTicks) throw new IllegalStateException("goto timed out after " + timeoutTicks + " ticks");
		BlockPos t = target.get();
		if (t == null) throw new IllegalStateException("target disappeared");
		if (path == null || (plannedFor != null && plannedFor.distSqr(t) > 4)) plan(p, task);
		if (within(p, goal, range) && (p.onGround() || p.isInWater())) {
			BotInput.clearKeys();
			if (!sub) {
				java.util.Map<String, Object> res = new java.util.LinkedHashMap<>();
				res.put("reached", true);
				res.put("pos", Mc.vec(p.position()));
				if (!goal.equals(t)) res.put("adjusted_target", Mc.pos(goal));
				task.setResult(res);
			}
			return true;
		}
		if (path.size() <= 1 && !complete) {
			if (replans++ >= 2) throw new IllegalStateException("no path found to " + goal.toShortString() + " (unreachable, or not loaded; try range>0 or allow_break)");
			path = null;
			return false;
		}
		if (idx >= path.size()) {
			if (replans++ >= maxReplans) throw new IllegalStateException("could not reach " + t.toShortString()
					+ ", ended at " + p.blockPosition().toShortString());
			path = null;
			return false;
		}

		BotInput.activate();
		// safety: never drown or die while walking
		if (p.isUnderWater() && p.getAirSupply() < 120) surfacing = true;
		if (surfacing) {
			BotInput.clearKeys();
			BotInput.jump = true;
			task.progress("surfacing for air (" + p.getAirSupply() + ")");
			if (!p.isUnderWater() && p.getAirSupply() >= p.getMaxAirSupply() - 30) {
				surfacing = false;
				path = null;
			}
			return false;
		}
		if (p.getHealth() <= 5f && p.hurtTime > 0) {
			throw new IllegalStateException("stopped: low health (" + Mc.r2(p.getHealth()) + ") while taking damage");
		}
		Pathfinder.Step step = path.get(idx);

		// break blocks in the way first
		for (int[] b : step.breaks()) {
			BlockPos bp = new BlockPos(b[0], b[1], b[2]);
			if (!Mc.level().getBlockState(bp).getCollisionShape(Mc.level(), bp).isEmpty()) {
				BotInput.clearKeys();
				mine(p, bp);
				return false;
			}
		}
		if (breaking != null) {
			Mc.gameMode().stopDestroyBlock();
			breaking = null;
		}

		Vec3 pos = p.position();
		Vec3 c = new Vec3(step.x() + 0.5, step.y(), step.z() + 0.5);
		double dx = c.x - pos.x, dz = c.z - pos.z;
		double hd = Math.sqrt(dx * dx + dz * dz);
		double dy = step.y() - pos.y;
		boolean last = idx == path.size() - 1;
		double tol = last ? 0.25 : 0.45;
		boolean vertical = step.move() == Pathfinder.Move.SWIM || step.move() == Pathfinder.Move.CLIMB;

		if (hd < tol && Math.abs(dy) < (vertical ? 0.5 : 0.8) && (p.onGround() || p.isInWater() || p.onClimbable() || dy > -0.5)) {
			idx++;
			bestDist = Double.MAX_VALUE;
			noProgress = 0;
			if (idx >= path.size()) BotInput.clearKeys();
			return false;
		}

		// progress / stuck detection
		double dist = hd + Math.abs(dy);
		if (dist < bestDist - 0.05) {
			bestDist = dist;
			noProgress = 0;
		} else if (++noProgress > 40) {
			if (replans++ >= maxReplans) throw new IllegalStateException("stuck at " + p.blockPosition().toShortString());
			path = null;
			BotInput.clearKeys();
			return false;
		}

		float yaw = Look.yawTo(pos, c);
		if (hd > 0.15) Look.turnTo(p, yaw, Mth.clamp(p.getXRot(), -20f, 30f));
		float err = Math.abs(Mth.wrapDegrees(yaw - p.getYRot()));
		BotInput.clearKeys();
		BotInput.forward = hd > 0.1 && err < 50;
		boolean needUp = dy > 0.5;
		if (p.isInWater()) {
			BotInput.jump = dy > -0.3; // swim up or keep at surface
		} else if (p.onClimbable()) {
			BotInput.jump = needUp;
			BotInput.sneak = false;
		} else {
			BotInput.jump = needUp && p.onGround() && hd < 1.6;
		}
		BotInput.sprint = sprint && hd > 1.2 && !p.isInWater() && err < 15 && !last;
		task.progress("step " + idx + "/" + (path.size() - 1) + (complete ? "" : " (partial)") + " -> " + step);
		return false;
	}

	private void mine(LocalPlayer p, BlockPos bp) {
		Look.turnTowards(p, Vec3.atCenterOf(bp), true);
		Direction face = Mc.faceTowards(bp, p.getEyePosition());
		if (!bp.equals(breaking)) {
			breaking = bp;
			breakTicks = 0;
			Tools.selectBestTool(p, Mc.level().getBlockState(bp));
			Mc.gameMode().startDestroyBlock(bp, face);
		} else {
			Mc.gameMode().continueDestroyBlock(bp, face);
		}
		Mc.swing(p, net.minecraft.world.InteractionHand.MAIN_HAND);
		if (++breakTicks > 400) throw new IllegalStateException("could not break block in the way at " + bp.toShortString());
	}

	@Override
	public void stop(TaskManager.Task task) {
		BotInput.clearKeys();
		if (breaking != null && Mc.inWorld()) Mc.gameMode().stopDestroyBlock();
	}
}
