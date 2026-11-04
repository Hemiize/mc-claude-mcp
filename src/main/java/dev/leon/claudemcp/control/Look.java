package dev.leon.claudemcp.control;

import dev.leon.claudemcp.ClaudeMcp;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Rotation helpers. */
public final class Look {
	private Look() {}

	public static float yawTo(Vec3 from, Vec3 to) {
		double dx = to.x - from.x, dz = to.z - from.z;
		return (float) (Mth.atan2(dz, dx) * (180 / Math.PI)) - 90f;
	}

	public static float pitchTo(Vec3 from, Vec3 to) {
		double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		return (float) -(Mth.atan2(dy, h) * (180 / Math.PI));
	}

	/**
	 * Turns the player towards the target, limited by the configured speed.
	 * @return true when the player is (almost) looking at the target.
	 */
	public static boolean turnTowards(LocalPlayer p, Vec3 target, boolean includePitch) {
		Vec3 eye = p.getEyePosition();
		float wantYaw = yawTo(eye, target);
		float wantPitch = includePitch ? pitchTo(eye, target) : p.getXRot();
		return turnTo(p, wantYaw, wantPitch);
	}

	public static boolean turnTo(LocalPlayer p, float wantYaw, float wantPitch) {
		float max = ClaudeMcp.config().maxTurnPerTick;
		float dy = Mth.wrapDegrees(wantYaw - p.getYRot());
		float dp = wantPitch - p.getXRot();
		if (max > 0) {
			dy = Mth.clamp(dy, -max, max);
			dp = Mth.clamp(dp, -max, max);
		}
		p.setYRot(p.getYRot() + dy);
		p.setXRot(Mth.clamp(p.getXRot() + dp, -90f, 90f));
		p.setYHeadRot(p.getYRot());
		return Math.abs(Mth.wrapDegrees(wantYaw - p.getYRot())) < 2f && Math.abs(wantPitch - p.getXRot()) < 2f;
	}
}
