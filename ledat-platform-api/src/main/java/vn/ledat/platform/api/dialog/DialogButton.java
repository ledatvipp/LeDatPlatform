package vn.ledat.platform.api.dialog;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Version-safe button definition used by both Paper native dialogs and the
 * inventory fallback renderer.
 */
public record DialogButton(
        String id,
        Component label,
        Component tooltip,
        int width,
        int fallbackSlot,
        ItemStack fallbackItem,
        boolean closeAfterClick,
        Consumer<DialogClick> action
) {
    public DialogButton {
        id = sanitize(id);
        label = label == null ? Component.text(id) : label;
        width = Math.max(1, Math.min(1024, width <= 0 ? 100 : width));
        fallbackSlot = Math.max(-1, Math.min(53, fallbackSlot));
        fallbackItem = fallbackItem == null ? defaultItem(id, label, tooltip) : fallbackItem.clone();
        action = action == null ? click -> { } : action;
    }

    public static DialogButton confirm(Component label, Component tooltip, Consumer<DialogClick> action) {
        return new DialogButton("confirm", label, tooltip, 100, 12, defaultColoredItem(Material.LIME_CONCRETE, label, tooltip), true, action);
    }

    public static DialogButton cancel(Component label, Component tooltip, Consumer<DialogClick> action) {
        return new DialogButton("cancel", label, tooltip, 100, 15, defaultColoredItem(Material.RED_CONCRETE, label, tooltip), true, action);
    }

    public static DialogButton action(String id, Component label, Component tooltip, int fallbackSlot, Material material, Consumer<DialogClick> action) {
        return new DialogButton(id, label, tooltip, 100, fallbackSlot, defaultColoredItem(material == null ? Material.PAPER : material, label, tooltip), true, action);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "button";
        }
        return value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    private static ItemStack defaultItem(String id, Component label, Component tooltip) {
        Material material = switch (id == null ? "" : id.toLowerCase(java.util.Locale.ROOT)) {
            case "confirm", "yes", "accept" -> Material.LIME_CONCRETE;
            case "cancel", "no", "deny", "back" -> Material.RED_CONCRETE;
            default -> Material.PAPER;
        };
        return defaultColoredItem(material, label, tooltip);
    }

    private static ItemStack defaultColoredItem(Material material, Component label, Component tooltip) {
        ItemStack item = new ItemStack(material == null ? Material.PAPER : material);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(label == null ? Component.text("Button") : label);
            if (tooltip != null && !Objects.equals(tooltip, Component.empty())) {
                meta.lore(List.of(tooltip));
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
