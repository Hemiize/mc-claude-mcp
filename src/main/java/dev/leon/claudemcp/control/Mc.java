package dev.leon.claudemcp.control;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Small helpers around the Minecraft client. Client thread only. */
public final class Mc {
	private Mc() {}

	public static Minecraft mc() {
		return Minecraft.getInstance();
	}

	public static LocalPlayer player() {
		LocalPlayer p = mc().player;
		if (p == null) throw new IllegalStateException("Not in a world (no player). Join a world first.");
		return p;
	}

	public static ClientLevel level() {
		ClientLevel l = mc().level;
		if (l == null) throw new IllegalStateException("Not in a world (no level). Join a world first.");
		return l;
	}

	public static MultiPlayerGameMode gameMode() {
		MultiPlayerGameMode g = mc().gameMode;
		if (g == null) throw new IllegalStateException("Not in a world (no game mode).");
		return g;
	}

	public static boolean inWorld() {
		return mc().player != null && mc().level != null;
	}

	public static String id(Block b) {
		return BuiltInRegistries.BLOCK.getKey(b).toString();
	}

	public static String id(Item i) {
		return BuiltInRegistries.ITEM.getKey(i).toString();
	}

	public static String id(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
	}

	public static Identifier ident(String id) {
		Identifier r = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		if (r == null) throw new IllegalArgumentException("Invalid id: " + id);
		return r;
	}

	public static Item item(String id) {
		Identifier r = ident(id);
		Optional<Item> i = BuiltInRegistries.ITEM.getOptional(r);
		if (i.isEmpty()) throw new IllegalArgumentException("Unknown item: " + id);
		return i.get();
	}

	public static Block block(String id) {
		Identifier r = ident(id);
		Optional<Block> b = BuiltInRegistries.BLOCK.getOptional(r);
		if (b.isEmpty()) throw new IllegalArgumentException("Unknown block: " + id);
		return b.get();
	}

	public static double r2(double d) {
		return Math.round(d * 100.0) / 100.0;
	}

	public static List<Object> vec(Vec3 v) {
		return List.of(r2(v.x), r2(v.y), r2(v.z));
	}

	public static List<Object> pos(BlockPos p) {
		return List.of(p.getX(), p.getY(), p.getZ());
	}

	public static Map<String, Object> stack(ItemStack s) {
		if (s == null || s.isEmpty()) return null;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id(s.getItem()));
		m.put("count", s.getCount());
		String name = s.getHoverName().getString();
		m.put("name", name);
		if (s.isDamageableItem()) {
			m.put("damage", s.getDamageValue());
			m.put("max_damage", s.getMaxDamage());
		}
		if (s.isEnchanted()) m.put("enchanted", true);
		return m;
	}

	/** Swings the arm like vanilla (animation + packet). */
	public static void swing(LocalPlayer p, net.minecraft.world.InteractionHand hand) {
		p.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
	}

	/** Face of the block that points towards the given eye position. */
	public static Direction faceTowards(BlockPos pos, Vec3 eye) {
		Vec3 c = Vec3.atCenterOf(pos);
		double dx = eye.x - c.x, dy = eye.y - c.y, dz = eye.z - c.z;
		double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
		if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
		if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	public static double eyeDistance(LocalPlayer p, BlockPos pos) {
		return p.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
	}

	public static Entity entity(int id) {
		Entity e = level().getEntity(id);
		if (e == null) throw new IllegalArgumentException("No entity with id " + id + " (out of range or removed)");
		return e;
	}
}
