package vn.ledat.platform.core.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import vn.ledat.platform.api.gui.GuiService;
import vn.ledat.platform.api.gui.GuiSession;

final class PlatformGuiHolder implements InventoryHolder {
    private final GuiService.PlatformGui gui;
    private final GuiSession session;
    private Inventory inventory;

    PlatformGuiHolder(GuiService.PlatformGui gui, GuiSession session) {
        this.gui = gui;
        this.session = session;
    }

    GuiService.PlatformGui gui() { return gui; }
    GuiSession session() { return session; }
    void inventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
