package dev.leon.claudemcp.path;

import dev.leon.claudemcp.path.PathWorld.Kind;

import java.util.*;

/**
 * Simple A* over block positions ("feet" positions of the player).
 * Supports walking (incl. diagonals), 1-block jumps, falling, swimming, climbing and optionally
 * breaking blocks that are in the way.
 */
public final class Pathfinder {
	public enum Move { START, WALK, JUMP, FALL, SWIM, CLIMB, BREAK }

	public record Step(int x, int y, int z, Move move, List<int[]> breaks) {
		@Override
		public String toString() {
			return move + "(" + x + "," + y + "," + z + ")" + (breaks.isEmpty() ? "" : " breaks=" + breaks.size());
		}
	}

	public record Result(List<Step> steps, boolean complete, int explored, double cost) {}

	public static final class Options {
		public int maxNodes = 20000;
		public int maxFall = 3;
		public boolean allowBreak = false;
		/** Goal is reached when the feet position is within this euclidean distance of the target. */
		public double range = 0;
		public long timeoutMs = 250;
	}

	private static final int[][] DIRS = {
			{1, 0}, {-1, 0}, {0, 1}, {0, -1},
			{1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};

	private final PathWorld world;
	private final Options opt;

	public Pathfinder(PathWorld world, Options opt) {
		this.world = world;
		this.opt = opt;
	}

	private static final class Node {
		final int x, y, z;
		Node parent;
		double g, f;
		Move move = Move.START;
		List<int[]> breaks = List.of();
		boolean closed;

		Node(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	static long key(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
	}

	private static boolean body(Kind k) {
		return k == Kind.PASSABLE || k == Kind.WATER || k == Kind.CLIMBABLE;
	}

	public boolean standable(int x, int y, int z) {
		Kind feet = world.kind(x, y, z), head = world.kind(x, y + 1, z);
		if (!body(feet) || !body(head)) return false;
		if (feet == Kind.WATER || feet == Kind.CLIMBABLE) return true;
		Kind below = world.kind(x, y - 1, z);
		return below == Kind.SOLID || below == Kind.CLIMBABLE;
	}

	private double heuristic(int x, int y, int z, int tx, int ty, int tz) {
		int dx = Math.abs(x - tx), dz = Math.abs(z - tz), dy = Math.abs(y - ty);
		int mn = Math.min(dx, dz), mx = Math.max(dx, dz);
		return mn * 1.4142 + (mx - mn) + dy;
	}

	private boolean isGoal(Node n, int tx, int ty, int tz) {
		if (opt.range <= 0) return n.x == tx && n.y == ty && n.z == tz;
		double dx = n.x - tx, dy = n.y - ty, dz = n.z - tz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz) <= opt.range;
	}

	public Result find(int sx, int sy, int sz, int tx, int ty, int tz) {
		long deadline = System.currentTimeMillis() + opt.timeoutMs;
		Map<Long, Node> nodes = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
		Node start = new Node(sx, sy, sz);
		start.f = heuristic(sx, sy, sz, tx, ty, tz);
		nodes.put(key(sx, sy, sz), start);
		open.add(start);
		Node best = start;
		double bestH = start.f;
		int explored = 0;

		while (!open.isEmpty()) {
			Node cur = open.poll();
			if (cur.closed) continue;
			cur.closed = true;
			explored++;
			if (isGoal(cur, tx, ty, tz)) return build(cur, true, explored);
			double h = heuristic(cur.x, cur.y, cur.z, tx, ty, tz);
			if (h < bestH) {
				bestH = h;
				best = cur;
			}
			if (explored >= opt.maxNodes || (explored % 256 == 0 && System.currentTimeMillis() > deadline)) break;

			for (Candidate c : neighbours(cur)) {
				long k = key(c.x, c.y, c.z);
				Node n = nodes.get(k);
				double g = cur.g + c.cost;
				if (n == null) {
					n = new Node(c.x, c.y, c.z);
					nodes.put(k, n);
				} else if (n.closed || g >= n.g) {
					continue;
				}
				n.g = g;
				n.f = g + heuristic(c.x, c.y, c.z, tx, ty, tz) * 1.1;
				n.parent = cur;
				n.move = c.move;
				n.breaks = c.breaks;
				open.add(n);
			}
		}
		return build(best, false, explored);
	}

	private record Candidate(int x, int y, int z, double cost, Move move, List<int[]> breaks) {}

	private List<Candidate> neighbours(Node n) {
		List<Candidate> out = new ArrayList<>(12);
		int x = n.x, y = n.y, z = n.z;
		Kind feetHere = world.kind(x, y, z);
		boolean inFluid = feetHere == Kind.WATER;
		boolean climbing = feetHere == Kind.CLIMBABLE;
		double fluidPenalty = inFluid ? 1.0 : 0.0;

		for (int i = 0; i < DIRS.length; i++) {
			int dx = DIRS[i][0], dz = DIRS[i][1];
			boolean diag = i >= 4;
			int nx = x + dx, nz = z + dz;
			double base = (diag ? 1.4142 : 1.0) + fluidPenalty;
			if (diag) {
				// avoid cutting corners: both orthogonal neighbours must have body clearance
				if (!body(world.kind(x + dx, y, z)) || !body(world.kind(x + dx, y + 1, z))
						|| !body(world.kind(x, y, z + dz)) || !body(world.kind(x, y + 1, z + dz))) continue;
			}
			// 1) same level
			if (standable(nx, y, nz)) {
				Kind k = world.kind(nx, y, nz);
				out.add(new Candidate(nx, y, nz, base, k == Kind.WATER ? Move.SWIM : Move.WALK, List.of()));
				continue;
			}
			Kind f = world.kind(nx, y, nz), h = world.kind(nx, y + 1, nz);
			// 2) jump up one block (cardinal only)
			if (!diag && standable(nx, y + 1, nz) && body(world.kind(x, y + 2, z))) {
				out.add(new Candidate(nx, y + 1, nz, base + 1.0, Move.JUMP, List.of()));
				continue;
			}
			// 3) fall down
			if (body(f) && body(h)) {
				int limit = opt.maxFall;
				for (int d = 1; d <= Math.max(limit, 24); d++) {
					Kind k = world.kind(nx, y - d, nz);
					if (k == Kind.DANGER || k == Kind.UNKNOWN) break;
					if (standable(nx, y - d, nz)) {
						boolean water = k == Kind.WATER;
						if (d <= limit || water) out.add(new Candidate(nx, y - d, nz, base + d * 0.5, Move.FALL, List.of()));
						break;
					}
					if (!body(k)) break;
				}
				continue;
			}
			// 4) break through (cardinal only)
			if (opt.allowBreak && !diag) {
				List<int[]> br = new ArrayList<>(2);
				double cost = base + 1.0;
				boolean ok = true;
				for (int dy = 0; dy <= 1 && ok; dy++) {
					Kind k = dy == 0 ? f : h;
					if (k == Kind.SOLID) {
						double c = world.breakCost(nx, y + dy, nz);
						if (c < 0) ok = false;
						else {
							cost += c;
							br.add(new int[]{nx, y + dy, nz});
						}
					} else if (!body(k)) ok = false;
				}
				Kind below = world.kind(nx, y - 1, nz);
				if (ok && !br.isEmpty() && (below == Kind.SOLID || below == Kind.CLIMBABLE)) {
					out.add(new Candidate(nx, y, nz, cost, Move.BREAK, br));
				}
			}
		}
		// vertical: swim / climb up
		if (inFluid || climbing) {
			if (body(world.kind(x, y + 1, z)) && body(world.kind(x, y + 2, z))) {
				out.add(new Candidate(x, y + 1, z, 1.5, inFluid ? Move.SWIM : Move.CLIMB, List.of()));
			}
		}
		// vertical: sink / climb down
		Kind below = world.kind(x, y - 1, z);
		if ((below == Kind.WATER || below == Kind.CLIMBABLE) && standable(x, y - 1, z)) {
			out.add(new Candidate(x, y - 1, z, 1.2, below == Kind.WATER ? Move.SWIM : Move.CLIMB, List.of()));
		}
		// being fully under water costs air: strongly prefer staying at the surface
		for (int i = 0; i < out.size(); i++) {
			Candidate c = out.get(i);
			if (world.kind(c.x, c.y + 1, c.z) == Kind.WATER) out.set(i, new Candidate(c.x, c.y, c.z, c.cost + 3.0, c.move, c.breaks));
		}
		return out;
	}

	private Result build(Node end, boolean complete, int explored) {
		LinkedList<Step> steps = new LinkedList<>();
		for (Node n = end; n != null; n = n.parent) {
			steps.addFirst(new Step(n.x, n.y, n.z, n.move, n.breaks));
		}
		return new Result(new ArrayList<>(steps), complete, explored, end.g);
	}
}
