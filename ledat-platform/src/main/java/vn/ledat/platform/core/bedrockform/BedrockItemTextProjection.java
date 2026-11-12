package vn.ledat.platform.core.bedrockform;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class BedrockItemTextProjection {
    private BedrockItemTextProjection() {
    }

    public static ItemStack project(ItemStack source, BedrockTextRenderer renderer) {
        if (source == null) {
            return null;
        }
        ItemStack projected = source.clone();
        if (renderer == null) {
            return projected;
        }
        ItemMeta meta = projected.getItemMeta();
        if (meta == null) {
            return projected;
        }
        Component displayName = meta.displayName();
        if (displayName != null) {
            meta.displayName(project(displayName, renderer));
        }
        List<Component> lore = meta.lore();
        if (lore != null) {
            meta.lore(project(lore, renderer));
        }
        projected.setItemMeta(meta);
        return projected;
    }

    static Component project(Component component, BedrockTextRenderer renderer) {
        if (component == null) {
            return Component.empty();
        }
        return renderer == null ? component : Component.text(renderer.render(component));
    }

    static List<Component> project(List<Component> components, BedrockTextRenderer renderer) {
        if (components == null) {
            return List.of();
        }
        return components.stream().map(component -> project(component, renderer)).toList();
    }
}
