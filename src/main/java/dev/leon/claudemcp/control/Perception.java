package dev.leon.claudemcp.control;

import dev.leon.claudemcp.ClaudeMcp;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;

import java.util.*;

/** Structured views of the game state. Client thread only. */
public final class Perception {
	private Perception() {}

	public static String worldId() {
		Minecraft mc = Mc.mc();
		if (mc.level == null) return "global";
		if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
			return "sp-" + mc.getSingleplayerServer().getWorldData().getLevelName();
		}
		ServerData sd = mc.getCurrentServer();
		if (sd != null) return "mp-" + sd.ip;
		return "unknown";
	}

	public static Map<String, Object> state() {
		Minecraft mc = Mc.mc();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("in_world", Mc.inWorld());
		m.put("world_id", worldId());
		Screen screen = mc.gui.screen();
		m.put("screen", screen == null ? null : screen.getClass().getSimpleName() + ": " + screen.getTitle().getString());
		m.put("fps", mc.getFps());
		if (!Mc.inWorld()) return m;
		LocalPlayer p = mc.player;
		ClientLevel l = mc.level;
		m.put("name", p.getName().getString());
		m.put("pos", Mc.vec(p.position()));
		m.put("block_pos", Mc.pos(p.blockPosition()));
		m.put("yaw", Mc.r2(net.minecraft.util.Mth.wrapDegrees(p.getYRot())));
		m.put("pitch", Mc.r2(p.getXRot()));
		m.put("facing", p.getDirection().getName());
		m.put("health", Mc.r2(p.getHealth()));
		m.put("max_health", Mc.r2(p.getMaxHealth()));
		m.put("food", p.getFoodData().getFoodLevel());
		m.put("saturation", Mc.r2(p.getFoodData().getSaturationLevel()));
		m.put("xp_level", p.experienceLevel);
		m.put("armor", p.getArmorValue());
		m.put("air", p.getAirSupply());
		m.put("dimension", l.dimension().identifier().toString());
		m.put("gamemode", mc.gameMode == null ? null : mc.gameMode.getPlayerMode().getName());
		m.put("on_ground", p.onGround());
		m.put("in_water", p.isInWater());
		m.put("sprinting", p.isSprinting());
		m.put("time_of_day", l.getOverworldClockTime() % 24000);
		m.put("raining", l.isRaining());
		m.put("thundering", l.isThundering());
		m.put("biome", l.getBiome(p.blockPosition()).unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
		m.put("light", l.getMaxLocalRawBrightness(p.blockPosition()));
		m.put("selected_slot", p.getInventory().getSelectedSlot());
		m.put("main_hand", Mc.stack(p.getMainHandItem()));
		m.put("off_hand", Mc.stack(p.getOffhandItem()));
		m.put("looking_at", lookingAt());
		m.put("server", mc.hasSingleplayerServer() ? "singleplayer" : mc.getCurrentServer() == null ? null : mc.getCurrentServer().ip);
		m.put("players_online", l.players().size());
		m.put("active_tasks", ClaudeMcp.tasks().running().size());
		return m;
	}

	public static Map<String, Object> lookingAt() {
		HitResult hr = Mc.mc().hitResult;
		if (hr == null || hr.getType() == HitResult.Type.MISS) return null;
		Map<String, Object> m = new LinkedHashMap<>();
		if (hr instanceof BlockHitResult bh) {
			m.put("type", "block");
			m.put("pos", Mc.pos(bh.getBlockPos()));
			m.put("face", bh.getDirection().getName());
			m.put("block", Mc.id(Mc.level().getBlockState(bh.getBlockPos()).getBlock()));
		} else if (hr instanceof EntityHitResult eh) {
			m.put("type", "entity");
			m.put("id", eh.getEntity().getId());
			m.put("entity", Mc.id(eh.getEntity()));
		}
		return m;
	}

	/** Blocks in a box around a center, grouped by id. */
	public static Map<String, Object> blocks(BlockPos c, int rx, int ry, int rz, Set<String> filter, boolean includeAir, int limit) {
		ClientLevel l = Mc.level();
		Map<String, List<List<Integer>>> groups = new TreeMap<>();
		int count = 0;
		boolean truncated = false;
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		outer:
		for (int y = c.getY() + ry; y >= c.getY() - ry; y--) {
			for (int x = c.getX() - rx; x <= c.getX() + rx; x++) {
				for (int z = c.getZ() - rz; z <= c.getZ() + rz; z++) {
					mp.set(x, y, z);
					BlockState s = l.getBlockState(mp);
					if (!includeAir && s.isAir()) continue;
					String id = Mc.id(s.getBlock());
					if (filter != null && !filter.isEmpty() && !filter.contains(id) && !filter.contains(id.replace("minecraft:", ""))) continue;
					if (count >= limit) {
						truncated = true;
						break outer;
					}
					groups.computeIfAbsent(id, k -> new ArrayList<>()).add(List.of(x, y, z));
					count++;
				}
			}
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("center", Mc.pos(c));
		m.put("radius", List.of(rx, ry, rz));
		m.put("count", count);
		m.put("truncated", truncated);
		m.put("blocks", groups);
		return m;
	}

	/** Nearest blocks of the given ids. */
	public static List<Map<String, Object>> findBlocks(Set<Block> blocks, int radius, int limit) {
		LocalPlayer p = Mc.player();
		ClientLevel l = Mc.level();
		BlockPos c = p.blockPosition();
		record Hit(BlockPos pos, double d, Block b) {}
		List<Hit> hits = new ArrayList<>();
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		int minY = Math.max(l.getMinY(), c.getY() - radius), maxY = Math.min(l.getMaxY() - 1, c.getY() + radius);
		for (int x = c.getX() - radius; x <= c.getX() + radius; x++) {
			for (int z = c.getZ() - radius; z <= c.getZ() + radius; z++) {
				for (int y = minY; y <= maxY; y++) {
					mp.set(x, y, z);
					Block b = l.getBlockState(mp).getBlock();
					if (!blocks.contains(b)) continue;
					hits.add(new Hit(mp.immutable(), Math.sqrt(mp.distSqr(c)), b));
				}
			}
		}
		hits.sort(Comparator.comparingDouble(Hit::d));
		List<Map<String, Object>> out = new ArrayList<>();
		for (int i = 0; i < Math.min(limit, hits.size()); i++) {
			Hit h = hits.get(i);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("pos", Mc.pos(h.pos));
			m.put("block", Mc.id(h.b));
			m.put("distance", Mc.r2(h.d));
			out.add(m);
		}
		return out;
	}

	public static List<Map<String, Object>> entities(double radius, Set<String> types, int limit) {
		LocalPlayer p = Mc.player();
		List<Map<String, Object>> out = new ArrayList<>();
		List<Entity> list = new ArrayList<>();
		for (Entity e : Mc.level().entitiesForRendering()) {
			if (e == p || !e.isAlive()) continue;
			if (e.distanceTo(p) > radius) continue;
			String id = Mc.id(e);
			if (types != null && !types.isEmpty() && !types.contains(id) && !types.contains(id.replace("minecraft:", ""))) continue;
			list.add(e);
		}
		list.sort(Comparator.comparingDouble(e -> e.distanceTo(p)));
		for (Entity e : list) {
			if (out.size() >= limit) break;
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", e.getId());
			m.put("type", Mc.id(e));
			m.put("name", e.getName().getString());
			m.put("pos", Mc.vec(e.position()));
			m.put("distance", Mc.r2(e.distanceTo(p)));
			if (e instanceof LivingEntity le) m.put("health", Mc.r2(le.getHealth()));
			if (e instanceof Player pl) m.put("player", pl.getGameProfile().name());
			if (e instanceof ItemEntity ie) m.put("item", Mc.stack(ie.getItem()));
			out.add(m);
		}
		return out;
	}

	public static Map<String, Object> inventory() {
		LocalPlayer p = Mc.player();
		Inventory inv = p.getInventory();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("selected_slot", inv.getSelectedSlot());
		List<Map<String, Object>> items = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			Map<String, Object> s = Mc.stack(inv.getItem(i));
			if (s == null) continue;
			s.put("slot", i);
			s.put("where", i < 9 ? "hotbar" : i < 36 ? "main" : i < 40 ? "armor" : i == 40 ? "offhand" : "other");
			items.add(s);
		}
		m.put("items", items);
		m.put("free_main_slots", (int) java.util.stream.IntStream.range(0, 36).filter(i -> inv.getItem(i).isEmpty()).count());
		m.put("note", "slot = inventory index (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand). click_slot uses MENU slot indices, see get_screen.");
		return m;
	}

	public static Map<String, Object> screen() {
		Minecraft mc = Mc.mc();
		Screen s = mc.gui.screen();
		Map<String, Object> m = new LinkedHashMap<>();
		if (s == null) {
			m.put("screen", null);
			if (Mc.inWorld()) {
				m.put("menu", menu(Mc.player().inventoryMenu));
				m.put("note", "No screen open; menu shows the player inventory menu (container id 0)");
			}
			return m;
		}
		m.put("screen", s.getClass().getName());
		m.put("title", s.getTitle().getString());
		List<Map<String, Object>> widgets = new ArrayList<>();
		List<? extends GuiEventListener> children = s.children();
		for (int i = 0; i < children.size(); i++) {
			if (!(children.get(i) instanceof AbstractWidget w)) continue;
			Map<String, Object> wm = new LinkedHashMap<>();
			wm.put("index", i);
			wm.put("type", w.getClass().getSimpleName());
			wm.put("text", w.getMessage().getString());
			if (w instanceof EditBox eb) wm.put("value", eb.getValue());
			wm.put("x", w.getX());
			wm.put("y", w.getY());
			wm.put("w", w.getWidth());
			wm.put("h", w.getHeight());
			wm.put("active", w.isActive());
			wm.put("visible", w.visible);
			widgets.add(wm);
		}
		m.put("widgets", widgets);
		if (s instanceof AbstractContainerScreen<?> cs) m.put("menu", menu(cs.getMenu()));
		return m;
	}

	public static Map<String, Object> menu(AbstractContainerMenu menu) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("container_id", menu.containerId);
		m.put("type", menu.getClass().getSimpleName());
		List<Map<String, Object>> slots = new ArrayList<>();
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot sl = menu.getSlot(i);
			Map<String, Object> st = Mc.stack(sl.getItem());
			if (st == null) {
				continue;
			}
			st.put("menu_slot", i);
			st.put("container", sl.container instanceof Inventory ? "player" : sl.container.getClass().getSimpleName());
			st.put("container_slot", sl.getContainerSlot());
			slots.add(st);
		}
		m.put("slot_count", menu.slots.size());
		m.put("items", slots);
		m.put("carried", Mc.stack(menu.getCarried()));
		return m;
	}
}
