package dev.leon.claudemcp.control;

import dev.leon.claudemcp.path.PathWorld;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.HashMap;
import java.util.Map;

/** Adapts the client level to the pathfinder's {@link PathWorld}. Must be used on the client thread. */
public final class McPathWorld implements PathWorld {
	private final ClientLevel level;
	private final LocalPlayer player;
	private final Map<Long, Kind> cache = new HashMap<>();
	private final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();

	public McPathWorld(ClientLevel level, LocalPlayer player) {
		this.level = level;
		this.player = player;
	}

	@Override
	public Kind kind(int x, int y, int z) {
		long k = BlockPos.asLong(x, y, z);
		Kind c = cache.get(k);
		if (c == null) {
			c = compute(x, y, z);
			cache.put(k, c);
		}
		return c;
	}

	private Kind compute(int x, int y, int z) {
		if (y < level.getMinY() || y >= level.getMaxY()) return Kind.UNKNOWN;
		mp.set(x, y, z);
		if (!level.hasChunkAt(mp)) return Kind.UNKNOWN;
		BlockState s = level.getBlockState(mp);
		FluidState f = s.getFluidState();
		if (f.is(FluidTags.LAVA)) return Kind.DANGER;
		Block b = s.getBlock();
		if (b instanceof FireBlock || b instanceof SoulFireBlock || b instanceof CactusBlock || b instanceof MagmaBlock
				|| b instanceof SweetBerryBushBlock || b instanceof PowderSnowBlock || b instanceof WitherRoseBlock
				|| b instanceof CampfireBlock || b == Blocks.COBWEB) {
			return Kind.DANGER;
		}
		if (s.is(BlockTags.CLIMBABLE)) return Kind.CLIMBABLE;
		if (f.is(FluidTags.WATER) && s.getCollisionShape(level, mp).isEmpty()) return Kind.WATER;
		if (s.getCollisionShape(level, mp).isEmpty()) return Kind.PASSABLE;
		// low blocks like carpets/snow layers count as passable floor
		double h = s.getCollisionShape(level, mp).max(net.minecraft.core.Direction.Axis.Y);
		if (h <= 0.2) return Kind.PASSABLE;
		if (b instanceof DoorBlock && s.getValue(DoorBlock.OPEN)) return Kind.PASSABLE;
		return Kind.SOLID;
	}

	@Override
	public double breakCost(int x, int y, int z) {
		mp.set(x, y, z);
		BlockState s = level.getBlockState(mp);
		float hardness = s.getDestroySpeed(level, mp);
		if (hardness < 0 || hardness > 20) return -1;
		// don't break blocks that would let fluids or falling blocks in
		if (!level.getBlockState(mp.above()).getFluidState().isEmpty()) return -1;
		if (level.getBlockState(mp.above()).getBlock() instanceof FallingBlock && y + 1 > player.getBlockY() + 1) return -1;
		return 1 + hardness * 2;
	}
}
