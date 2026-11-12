package vn.ledat.platform.api.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;

public record GuiClose(Player player, GuiSession session, InventoryCloseEvent event, boolean cleanup) {
}
