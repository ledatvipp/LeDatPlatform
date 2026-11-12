package vn.ledat.platform.api.gui;

import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;

public record GuiButton(String id, ItemStack item, Consumer<GuiClick> action) {
    public GuiButton {
        id = id == null || id.isBlank() ? "button" : id;
        item = GuiService.nonItalicItem(item);
    }

    @Override
    public ItemStack item() {
        return GuiService.nonItalicItem(item);
    }
}
