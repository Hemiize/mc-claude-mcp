package dev.leon.claudemcp;

import dev.leon.claudemcp.path.PathWorld;
import dev.leon.claudemcp.path.Pathfinder;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PathfinderTest {
	/** Flat stone floor at y=0 (feet at y=1) from -20..20, with configurable extra blocks. */
	static final class Grid implements PathWorld {
		final Map<Long, Kind> blocks = new HashMap<>();
		final Map<Long, Double> hardness = new HashMap<>();

		static long k(int x, int y, int z) {
			return ((long) x & 0xFFFF) << 32 | ((long) y & 0xFFFF) << 16 | ((long) z & 0xFFFF);
		}

		Grid set(int x, int y, int z, Kind kind) {
			blocks.put(k(x, y, z), kind);
			return this;
		}

		@Override
		public Kind kind(int x, int y, int z) {
			if (Math.abs(x) > 20 || Math.abs(z) > 20 || y < -10 || y > 30) return Kind.UNKNOWN;
			Kind k = blocks.get(k(x, y, z));
			if (k != null) return k;
			return y <= 0 ? Kind.SOLID : Kind.PASSABLE;
		}

		@Override
		public double breakCost(int x, int y, int z) {
			return hardness.getOrDefault(k(x, y, z), 2.0);
		}
	}

	static Pathfinder.Result find(Grid g, int sx, int sy, int sz, int tx, int ty, int tz, boolean allowBreak) {
		Pathfinder.Options o = new Pathfinder.Options();
		o.allowBreak = allowBreak;
		o.timeoutMs = 5000;
		return new Pathfinder(g, o).find(sx, sy, sz, tx, ty, tz);
	}

	@Test
	void walksStraightOnFlatGround() {
		Pathfinder.Result r = find(new Grid(), 0, 1, 0, 10, 1, 0, false);
		assertTrue(r.complete());
		Pathfinder.Step last = r.steps().getLast();
		assertEquals(10, last.x());
		assertEquals(11, r.steps().size(), "start + 10 walk steps");
	}

	@Test
	void usesDiagonals() {
		Pathfinder.Result r = find(new Grid(), 0, 1, 0, 5, 1, 5, false);
		assertTrue(r.complete());
		assertEquals(6, r.steps().size(), "5 diagonal steps");
	}

	@Test
	void jumpsUpOneBlock() {
		Grid g = new Grid();
		for (int z = -20; z <= 20; z++) for (int x = 3; x <= 20; x++) g.set(x, 1, z, PathWorld.Kind.SOLID);
		Pathfinder.Result r = find(g, 0, 1, 0, 6, 2, 0, false);
		assertTrue(r.complete());
		assertTrue(r.steps().stream().anyMatch(s -> s.move() == Pathfinder.Move.JUMP));
	}

	@Test
	void cannotClimbTwoBlockWallWithoutBreaking() {
		Grid g = new Grid();
		for (int z = -20; z <= 20; z++) {
			g.set(3, 1, z, PathWorld.Kind.SOLID);
			g.set(3, 2, z, PathWorld.Kind.SOLID);
		}
		assertFalse(find(g, 0, 1, 0, 6, 1, 0, false).complete());
		Pathfinder.Result r = find(g, 0, 1, 0, 6, 1, 0, true);
		assertTrue(r.complete());
		assertTrue(r.steps().stream().anyMatch(s -> s.move() == Pathfinder.Move.BREAK && s.breaks().size() == 2));
	}

	@Test
	void avoidsDangerAndGoesAround() {
		Grid g = new Grid();
		for (int z = -2; z <= 2; z++) g.set(3, 1, z, PathWorld.Kind.DANGER);
		Pathfinder.Result r = find(g, 0, 1, 0, 6, 1, 0, false);
		assertTrue(r.complete());
		assertTrue(r.steps().stream().noneMatch(s -> s.x() == 3 && Math.abs(s.z()) <= 2));
	}

	@Test
	void fallsDownLimitedHeight() {
		Grid g = new Grid();
		// a pit 3 deep at x>=3
		for (int x = 3; x <= 20; x++) for (int z = -20; z <= 20; z++) for (int y = -2; y <= 0; y++) g.set(x, y, z, PathWorld.Kind.PASSABLE);
		Pathfinder.Result r = find(g, 0, 1, 0, 6, -2, 0, false);
		assertTrue(r.complete());
		assertTrue(r.steps().stream().anyMatch(s -> s.move() == Pathfinder.Move.FALL));
	}

	@Test
	void rangeGoal() {
		Pathfinder.Options o = new Pathfinder.Options();
		o.range = 3;
		Pathfinder.Result r = new Pathfinder(new Grid(), o).find(0, 1, 0, 10, 1, 0);
		assertTrue(r.complete());
		Pathfinder.Step last = r.steps().getLast();
		assertTrue(Math.abs(last.x() - 10) <= 3);
	}

	@Test
	void prefersSwimmingAtTheSurface() {
		Grid g = new Grid();
		// a lake 4 deep between x=2..8: water from y=-3..1 (surface at y=1, air above)
		for (int x = 2; x <= 8; x++) for (int z = -20; z <= 20; z++) for (int y = -3; y <= 1; y++) g.set(x, y, z, PathWorld.Kind.WATER);
		Pathfinder.Result r = find(g, 0, 1, 0, 10, 1, 0, false);
		assertTrue(r.complete());
		for (Pathfinder.Step s : r.steps()) {
			assertNotEquals(PathWorld.Kind.WATER, g.kind(s.x(), s.y() + 1, s.z()), "head under water at " + s);
		}
	}

	@Test
	void partialPathWhenUnreachable() {
		Grid g = new Grid();
		for (int z = -20; z <= 20; z++) for (int y = 1; y <= 30; y++) g.set(5, y, z, PathWorld.Kind.SOLID);
		Pathfinder.Result r = find(g, 0, 1, 0, 10, 1, 0, false);
		assertFalse(r.complete());
		assertEquals(4, r.steps().getLast().x(), "gets as close as possible");
	}
}
