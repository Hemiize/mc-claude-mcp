package dev.leon.claudemcp.nbt;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.leon.claudemcp.control.Mc;
import dev.leon.claudemcp.core.MainThread;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads and edits NBT of block entities, entities, the player and items.
 * <ul>
 *   <li>Singleplayer: reads/writes the authoritative server-side objects of the integrated server.</li>
 *   <li>Multiplayer: reads the client copy; writes are sent as /data commands (needs permission), items via
 *       the creative inventory packet (creative mode only).</li>
 * </ul>
 */
public final class NbtEditor {
	public enum Kind { BLOCK, ENTITY, PLAYER, ITEM }

	public record Target(Kind kind, BlockPos pos, int entityId, String slot) {
		String describe() {
			return switch (kind) {
				case BLOCK -> "block " + pos.toShortString();
				case ENTITY -> "entity " + entityId;
				case PLAYER -> "player";
				case ITEM -> "item " + slot;
			};
		}
	}

	private NbtEditor() {}

	// ------------------------------------------------------------ helpers

	static IntegratedServer server() {
		Minecraft mc = Minecraft.getInstance();
		return mc.hasSingleplayerServer() ? mc.getSingleplayerServer() : null;
	}

	static <T> T onServer(IntegratedServer s, MainThread.ThrowingSupplier<T> f) throws Exception {
		CompletableFuture<T> cf = new CompletableFuture<>();
		s.execute(() -> {
			try {
				cf.complete(f.get());
			} catch (Throwable t) {
				cf.completeExceptionally(t);
			}
		});
		try {
			return cf.get(10, TimeUnit.SECONDS);
		} catch (java.util.concurrent.ExecutionException e) {
			if (e.getCause() instanceof Exception ex) throw ex;
			throw new RuntimeException(e.getCause());
		}
	}

	public static Tag parseValue(String snbt) {
		try {
			return TagParser.create(NbtOps.INSTANCE).parseFully(snbt);
		} catch (CommandSyntaxException e) {
			throw new IllegalArgumentException("Invalid SNBT: " + e.getMessage());
		}
	}

	public static CompoundTag parseCompound(String snbt) {
		try {
			return TagParser.parseCompoundFully(snbt);
		} catch (CommandSyntaxException e) {
			throw new IllegalArgumentException("Invalid SNBT compound: " + e.getMessage());
		}
	}

	static NbtPathArgument.NbtPath path(String p) {
		try {
			return NbtPathArgument.NbtPath.of(p);
		} catch (CommandSyntaxException e) {
			throw new IllegalArgumentException("Invalid NBT path '" + p + "': " + e.getMessage());
		}
	}

	/** Inventory index for a slot spec: hand, offhand, head/chest/legs/feet or 0-40. */
	static int slotIndex(Player p, String slot) {
		String s = slot == null ? "hand" : slot.toLowerCase(Locale.ROOT);
		return switch (s) {
			case "hand", "mainhand", "main_hand" -> p.getInventory().getSelectedSlot();
			case "offhand", "off_hand" -> 40;
			case "feet", "boots" -> 36;
			case "legs", "leggings" -> 37;
			case "chest", "chestplate" -> 38;
			case "head", "helmet" -> 39;
			default -> {
				int i = Integer.parseInt(s);
				if (i < 0 || i > 40) throw new IllegalArgumentException("slot must be 0-40, hand, offhand, head, chest, legs or feet");
				yield i;
			}
		};
	}

	/** Player-inventory index -> slot number in the player's inventory menu (for the creative packet). */
	static int menuSlot(int inv) {
		if (inv >= 0 && inv <= 8) return 36 + inv;
		if (inv >= 9 && inv <= 35) return inv;
		if (inv >= 36 && inv <= 39) return 8 - (inv - 36);
		if (inv == 40) return 45;
		throw new IllegalArgumentException("bad slot " + inv);
	}

	static CompoundTag encodeItem(ItemStack st, HolderLookup.Provider reg) {
		if (st.isEmpty()) return new CompoundTag();
		Tag t = ItemStack.CODEC.encodeStart(reg.createSerializationContext(NbtOps.INSTANCE), st).getOrThrow(IllegalStateException::new);
		return (CompoundTag) t;
	}

	static ItemStack decodeItem(CompoundTag t, HolderLookup.Provider reg) {
		if (t.isEmpty()) return ItemStack.EMPTY;
		return ItemStack.CODEC.parse(reg.createSerializationContext(NbtOps.INSTANCE), t)
				.getOrThrow(msg -> new IllegalArgumentException("Invalid item data: " + msg));
	}

	static CompoundTag saveEntity(Entity e, HolderLookup.Provider reg) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, reg);
		e.saveWithoutId(out);
		CompoundTag t = out.buildResult();
		var key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
		if (!(e instanceof Player)) t.putString("id", key.toString());
		return t;
	}

	// ------------------------------------------------------------ read

	/** Reads the NBT of a target. Call from a non-client thread. */
	public static Map<String, Object> read(Target t, String pathStr, boolean preferClient) throws Exception {
		IntegratedServer s = preferClient ? null : server();
		CompoundTag tag;
		String source;
		if (s != null) {
			source = "server";
			var dim = MainThread.call(() -> Mc.level().dimension());
			UUID uuid = MainThread.call(() -> Mc.player().getUUID());
			tag = onServer(s, () -> {
				ServerLevel sl = s.getLevel(dim);
				return switch (t.kind) {
					case BLOCK -> readBlock(sl, t.pos);
					case ENTITY -> {
						Entity e = sl.getEntity(t.entityId);
						if (e == null) throw new IllegalArgumentException("No entity " + t.entityId + " on the server");
						yield saveEntity(e, sl.registryAccess());
					}
					case PLAYER -> saveEntity(s.getPlayerList().getPlayer(uuid), sl.registryAccess());
					case ITEM -> {
						ServerPlayer sp = s.getPlayerList().getPlayer(uuid);
						yield encodeItem(sp.getInventory().getItem(slotIndex(sp, t.slot)), sl.registryAccess());
					}
				};
			});
		} else {
			source = "client";
			tag = MainThread.call(() -> {
				Level l = Mc.level();
				return switch (t.kind) {
					case BLOCK -> readBlock(l, t.pos);
					case ENTITY -> saveEntity(Mc.entity(t.entityId), l.registryAccess());
					case PLAYER -> saveEntity(Mc.player(), l.registryAccess());
					case ITEM -> encodeItem(Mc.player().getInventory().getItem(slotIndex(Mc.player(), t.slot)), l.registryAccess());
				};
			});
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("target", t.describe());
		m.put("source", source);
		if (source.equals("client")) m.put("note", "client copy - servers only send part of the data (e.g. no chest contents until opened)");
		if (pathStr != null && !pathStr.isBlank()) {
			List<Tag> found;
			try {
				found = path(pathStr).get(tag);
			} catch (CommandSyntaxException e) {
				throw new IllegalArgumentException("Path not found: " + e.getMessage());
			}
			m.put("path", pathStr);
			List<String> vals = new ArrayList<>();
			for (Tag x : found) vals.add(x.toString());
			m.put("values", vals);
		} else {
			String snbt = tag.toString();
			m.put("size", snbt.length());
			m.put("snbt", snbt.length() > 60000 ? snbt.substring(0, 60000) + "...(truncated, use path)" : snbt);
		}
		return m;
	}

	static CompoundTag readBlock(Level l, BlockPos pos) {
		BlockEntity be = l.getBlockEntity(pos);
		BlockState st = l.getBlockState(pos);
		if (be == null) {
			CompoundTag t = new CompoundTag();
			t.putString("block", Mc.id(st.getBlock()));
			t.putString("state", st.toString());
			t.putString("note", "no block entity at this position (only blocks like chests, signs, spawners, furnaces have NBT)");
			return t;
		}
		return be.saveWithFullMetadata(l.registryAccess());
	}

	// ------------------------------------------------------------ write

	public enum Op { MERGE, SET_PATH, REMOVE_PATH, REPLACE }

	/** Applies an edit to a tag copy. */
	public static CompoundTag apply(CompoundTag current, Op op, String pathStr, String snbt) throws CommandSyntaxException {
		CompoundTag t = current.copy();
		switch (op) {
			case MERGE -> t.merge(parseCompound(snbt));
			case REPLACE -> t = parseCompound(snbt);
			case SET_PATH -> {
				int n = path(pathStr).set(t, parseValue(snbt));
				if (n == 0) throw new IllegalArgumentException("Path " + pathStr + " matched nothing (or value unchanged)");
			}
			case REMOVE_PATH -> {
				int n = path(pathStr).remove(t);
				if (n == 0) throw new IllegalArgumentException("Path " + pathStr + " matched nothing");
			}
		}
		return t;
	}

	/** Writes an edit. Call from a non-client thread. */
	public static Map<String, Object> write(Target t, Op op, String pathStr, String snbt) throws Exception {
		IntegratedServer s = server();
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("target", t.describe());
		if (s != null) {
			var dim = MainThread.call(() -> Mc.level().dimension());
			UUID uuid = MainThread.call(() -> Mc.player().getUUID());
			String after = onServer(s, () -> {
				ServerLevel sl = s.getLevel(dim);
				var reg = sl.registryAccess();
				switch (t.kind) {
					case BLOCK -> {
						BlockEntity be = sl.getBlockEntity(t.pos);
						if (be == null) throw new IllegalArgumentException("No block entity at " + t.pos.toShortString());
						CompoundTag nt = apply(be.saveWithFullMetadata(reg), op, pathStr, snbt);
						be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, reg, nt));
						be.setChanged();
						BlockState st = sl.getBlockState(t.pos);
						sl.sendBlockUpdated(t.pos, st, st, 3);
						return be.saveWithFullMetadata(reg).toString();
					}
					case ENTITY, PLAYER -> {
						Entity e = t.kind == Kind.PLAYER ? s.getPlayerList().getPlayer(uuid) : sl.getEntity(t.entityId);
						if (e == null) throw new IllegalArgumentException("No entity " + t.entityId + " on the server");
						CompoundTag cur = saveEntity(e, reg);
						CompoundTag nt = apply(cur, op, pathStr, snbt);
						UUID keep = e.getUUID();
						e.load(TagValueInput.create(ProblemReporter.DISCARDING, reg, nt));
						if (!e.getUUID().equals(keep)) e.setUUID(keep);
						if (e instanceof ServerPlayer sp) sp.inventoryMenu.broadcastChanges();
						return saveEntity(e, reg).toString();
					}
					case ITEM -> {
						ServerPlayer sp = s.getPlayerList().getPlayer(uuid);
						int idx = slotIndex(sp, t.slot);
						CompoundTag nt = apply(encodeItem(sp.getInventory().getItem(idx), reg), op, pathStr, snbt);
						ItemStack ns = decodeItem(nt, reg);
						sp.getInventory().setItem(idx, ns);
						sp.inventoryMenu.broadcastChanges();
						sp.containerMenu.broadcastChanges();
						return encodeItem(ns, reg).toString();
					}
				}
				return null;
			});
			res.put("applied_via", "integrated server");
			res.put("snbt", after != null && after.length() > 20000 ? after.substring(0, 20000) + "..." : after);
			return res;
		}
		// multiplayer
		return MainThread.call(() -> {
			Level l = Mc.level();
			var reg = l.registryAccess();
			switch (t.kind) {
				case ITEM -> {
					if (!Mc.gameMode().getPlayerMode().isCreative()) {
						throw new IllegalStateException("On servers items can only be edited in creative mode (creative inventory packet). Switch to creative or use commands.");
					}
					var p = Mc.player();
					int idx = slotIndex(p, t.slot);
					CompoundTag nt = apply(encodeItem(p.getInventory().getItem(idx), reg), op, pathStr, snbt);
					ItemStack ns = decodeItem(nt, reg);
					Mc.gameMode().handleCreativeModeItemAdd(ns, menuSlot(idx));
					res.put("applied_via", "creative inventory packet");
					res.put("snbt", encodeItem(ns, reg).toString());
					return res;
				}
				case PLAYER -> throw new IllegalStateException("Player data cannot be changed on a server (/data does not modify players)");
				default -> {
					String sel = t.kind == Kind.BLOCK ? "block " + t.pos.getX() + " " + t.pos.getY() + " " + t.pos.getZ()
							: "entity " + Mc.entity(t.entityId).getUUID();
					String cmd = switch (op) {
						case MERGE -> "data merge " + sel + " " + parseCompound(snbt);
						case SET_PATH -> "data modify " + sel + " " + pathStr + " set value " + parseValue(snbt);
						case REMOVE_PATH -> "data remove " + sel + " " + pathStr;
						case REPLACE -> throw new IllegalStateException("replace is only supported in singleplayer; use merge on servers");
					};
					Mc.player().connection.sendCommand(cmd);
					res.put("applied_via", "command (needs permission level 2)");
					res.put("command", "/" + cmd);
					res.put("hint", "check get_events/get_log for the server's answer");
					return res;
				}
			}
		});
	}

	/** Gives a new item from SNBT ({id:"minecraft:diamond_sword",count:1,components:{...}}) into a slot. */
	public static Map<String, Object> give(String snbt, String slot) throws Exception {
		Target t = new Target(Kind.ITEM, null, 0, slot == null ? firstEmpty() : slot);
		return write(t, Op.REPLACE, null, snbt);
	}

	static String firstEmpty() {
		return MainThread.call(() -> {
			var inv = Mc.player().getInventory();
			for (int i = 0; i < 36; i++) if (inv.getItem(i).isEmpty()) return Integer.toString(i);
			throw new IllegalStateException("Inventory full; pass a slot");
		});
	}
}
