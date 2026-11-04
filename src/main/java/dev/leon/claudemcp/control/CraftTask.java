package dev.leon.claudemcp.control;

import dev.leon.claudemcp.core.TaskManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;

import java.util.*;

/**
 * Crafts an item through the recipe book: places the recipe into the open crafting grid (2x2 player
 * inventory or an opened crafting table) and shift-clicks the result.
 */
public final class CraftTask implements TaskManager.TickTask {
	private final Item item;
	private final int count;
	private int crafted;
	private int phase;
	private int wait;
	private RecipeDisplayEntry recipe;

	public CraftTask(Item item, int count) {
		this.item = item;
		this.count = Math.max(1, count);
	}

	@Override
	public boolean usesBody() {
		return true;
	}

	public static List<RecipeDisplayEntry> findRecipes(LocalPlayer p, Item item) {
		ContextMap ctx = SlotDisplayContext.fromLevel(Mc.level());
		List<RecipeDisplayEntry> out = new ArrayList<>();
		for (RecipeCollection col : p.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry e : col.getRecipes()) {
				for (ItemStack s : e.resultItems(ctx)) {
					if (s.is(item)) {
						out.add(e);
						break;
					}
				}
			}
		}
		return out;
	}

	static boolean needsTable(RecipeDisplayEntry e) {
		if (e.display() instanceof ShapedCraftingRecipeDisplay sh) return sh.width() > 2 || sh.height() > 2;
		// shapeless: more than 4 ingredients need a table
		return e.craftingRequirements().map(l -> l.size() > 4).orElse(false);
	}

	@Override
	public boolean tick(TaskManager.Task task) {
		LocalPlayer p = Mc.player();
		AbstractContainerMenu menu = p.containerMenu;
		boolean table = menu instanceof CraftingMenu;
		if (!table && !(menu instanceof InventoryMenu)) {
			throw new IllegalStateException("Open container is not a crafting grid (" + menu.getClass().getSimpleName() + "). Close it or open a crafting table.");
		}
		if (recipe == null) {
			List<RecipeDisplayEntry> rs = findRecipes(p, item);
			if (rs.isEmpty()) throw new IllegalStateException("No unlocked recipe for " + Mc.id(item)
					+ " in the recipe book (recipes unlock when you obtain an ingredient)");
			StackedItemContents have = new StackedItemContents();
			p.getInventory().fillStackedContents(have);
			RecipeDisplayEntry pick = null;
			for (RecipeDisplayEntry e : rs) {
				if (!table && needsTable(e)) continue;
				if (e.canCraft(have)) {
					pick = e;
					break;
				}
			}
			if (pick == null) {
				boolean anySmall = rs.stream().anyMatch(e -> !needsTable(e));
				if (!table && !anySmall) throw new IllegalStateException("Recipe for " + Mc.id(item)
						+ " needs a crafting table: open one with interact_block first");
				throw new IllegalStateException("Missing ingredients for " + Mc.id(item));
			}
			recipe = pick;
		}
		switch (phase) {
			case 0 -> {
				Mc.gameMode().handlePlaceRecipe(menu.containerId, recipe.id(), false);
				phase = 1;
				wait = 0;
			}
			case 1 -> {
				ItemStack result = menu.getSlot(0).getItem();
				if (!result.isEmpty() && result.is(item)) {
					int n = result.getCount();
					Mc.gameMode().handleContainerInput(menu.containerId, 0, 0, ContainerInput.PICKUP, p);
					// put the picked stack into the inventory via quick move fallback: click result with quick move instead
					phase = 2;
					task.state.put("got", n);
				} else if (++wait > 30) {
					throw new IllegalStateException("Recipe placement produced no result (missing ingredients?), crafted " + crafted);
				}
			}
			case 2 -> {
				// carried stack -> drop into inventory
				ItemStack carried = menu.getCarried();
				if (!carried.isEmpty()) {
					int slot = freeSlotFor(menu, carried);
					if (slot < 0) throw new IllegalStateException("Inventory full");
					Mc.gameMode().handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, p);
				}
				crafted += (Integer) task.state.getOrDefault("got", 1);
				task.progress("crafted " + crafted + "/" + count);
				if (crafted >= count) {
					clearGrid(menu, p);
					task.setResult(Map.of("item", Mc.id(item), "crafted", crafted));
					return true;
				}
				phase = 0;
			}
		}
		return false;
	}

	/** Finds a player-inventory slot of the menu that can take the carried stack. */
	static int freeSlotFor(AbstractContainerMenu menu, ItemStack carried) {
		int empty = -1;
		for (int i = 0; i < menu.slots.size(); i++) {
			var s = menu.getSlot(i);
			if (!(s.container instanceof net.minecraft.world.entity.player.Inventory)) continue;
			if (s.getContainerSlot() >= 36) continue; // armor/offhand
			ItemStack st = s.getItem();
			if (!st.isEmpty() && ItemStack.isSameItemSameComponents(st, carried) && st.getCount() < st.getMaxStackSize()) return i;
			if (st.isEmpty() && empty < 0) empty = i;
		}
		return empty;
	}

	static void clearGrid(AbstractContainerMenu menu, LocalPlayer p) {
		int gridEnd = menu instanceof CraftingMenu ? 9 : 4;
		for (int i = 1; i <= gridEnd; i++) {
			if (!menu.getSlot(i).getItem().isEmpty()) {
				Mc.gameMode().handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, p);
			}
		}
	}

	@Override
	public void stop(TaskManager.Task task) {}
}
