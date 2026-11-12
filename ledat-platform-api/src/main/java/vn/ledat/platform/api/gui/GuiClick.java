package vn.ledat.platform.api.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public record GuiClick(Player player, GuiSession session, int rawSlot, ClickType clickType, ItemStack currentItem, InventoryClickEvent event) {
    public boolean leftClick() { return clickType != null && clickType.isLeftClick(); }
    public boolean rightClick() { return clickType != null && clickType.isRightClick(); }
    public boolean shiftClick() { return clickType != null && clickType.isShiftClick(); }
    public ItemStack currentItemCopy() { return currentItem == null ? null : currentItem.clone(); }
}
