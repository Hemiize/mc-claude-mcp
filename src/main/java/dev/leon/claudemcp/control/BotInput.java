package dev.leon.claudemcp.control;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Movement input injected into the player's KeyboardInput (see KeyboardInputMixin). While active it
 * fully replaces the keyboard movement state, so physics (sprint, sneak, jump) behave like a real player.
 */
public final class BotInput {
	private static volatile boolean active;
	public static volatile boolean forward, backward, left, right, jump, sneak, sprint;

	private BotInput() {}

	public static boolean active() {
		return active;
	}

	public static void activate() {
		active = true;
	}

	public static void release() {
		forward = backward = left = right = jump = sneak = sprint = false;
		active = false;
	}

	public static void clearKeys() {
		forward = backward = left = right = jump = sneak = sprint = false;
	}

	public static Input toInput() {
		return new Input(forward, backward, left, right, jump, sneak, sprint);
	}

	public static Vec2 moveVector() {
		float x = (left ? 1 : 0) - (right ? 1 : 0);
		float y = (forward ? 1 : 0) - (backward ? 1 : 0);
		Vec2 v = new Vec2(x, y);
		return (x != 0 && y != 0) ? v.normalized() : v;
	}
}
