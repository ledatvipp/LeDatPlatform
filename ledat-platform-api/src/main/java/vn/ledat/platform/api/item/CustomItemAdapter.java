package vn.ledat.platform.api.item;

import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Optional;

/** Optional custom-item provider adapter. Implementations must fail closed when their plugin is absent. */
public interface CustomItemAdapter {
    String provider();
    boolean available();
    Optional<ItemStack> create(CustomItemKey key, int amount);
    Optional<CustomItemKey> identify(ItemStack item);

    default Collection<String> suggestions(String input) {
        return java.util.List.of();
    }
}
