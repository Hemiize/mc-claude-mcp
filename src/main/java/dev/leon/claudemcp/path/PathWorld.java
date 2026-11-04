package dev.leon.claudemcp.path;

/**
 * Minimal, Minecraft-free view of the world used by the pathfinder, so it can be unit tested.
 */
public interface PathWorld {
	enum Kind {
		/** Air, flowers, grass, open doors: the player can stand in it. */
		PASSABLE,
		/** Full collision block. */
		SOLID,
		/** Water: the player swims. */
		WATER,
		/** Ladder/vine/scaffolding: climbable and passable. */
		CLIMBABLE,
		/** Lava, fire, cactus, magma, sweet berries...: never step into. */
		DANGER,
		/** Chunk not loaded or outside the world. */
		UNKNOWN
	}

	Kind kind(int x, int y, int z);

	/**
	 * Cost (in "walk steps") to break the block, or a negative value if it cannot / should not be broken.
	 * Only asked for SOLID blocks when breaking is allowed.
	 */
	default double breakCost(int x, int y, int z) {
		return -1;
	}
}
