package dev.leon.claudemcp.control;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Inventory helpers (hotbar selection, tool choice). Client thread only. */
public final class Tools {
	private Tools() {}

	/** Picks the hotbar slot with the fastest tool for the block. */
	public static void selectBestTool(LocalPlayer p, BlockState state) {
		Inventory inv = p.getInventory();
		int best = inv.getSelectedSlot();
		float bestSpeed = inv.getItem(best).getDestroySpeed(state);
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			float sp = s.getDestroySpeed(state);
			if (sp > bestSpeed + 0.01f) {
				bestSpeed = sp;
				best = i;
			}
		}
		select(p, best);
	}

	public static void select(LocalPlayer p, int hotbarSlot) {
		if (hotbarSlot < 0 || hotbarSlot > 8) throw new IllegalArgumentException("hotbar slot must be 0-8");
		p.getInventory().setSelectedSlot(hotbarSlot);
	}

	/**
	 * Makes sure an item is in the hand: selects it in the hotbar or swaps it from the main inventory
	 * into the current hotbar slot. Returns the hotbar slot.
	 */
	public static int selectItem(LocalPlayer p, Item item) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).is(item)) {
				select(p, i);
				return i;
			}
		}
		for (int i = 9; i < 36; i++) {
			if (inv.getItem(i).is(item)) {
				int target = inv.getSelectedSlot();
				// player inventory menu: main inventory slots 9..35 keep their index, hotbar uses button = slot
				Mc.gameMode().handleContainerInput(p.inventoryMenu.containerId, i, target, ContainerInput.SWAP, p);
				return target;
			}
		}
		throw new IllegalArgumentException("Item not in inventory: " + Mc.id(item));
	}
}
