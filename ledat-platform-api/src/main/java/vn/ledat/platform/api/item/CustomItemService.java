package vn.ledat.platform.api.item;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface CustomItemService {
    Optional<ItemStack> create(CustomItemKey key, int amount);

    default Optional<ItemStack> create(String key, int amount) {
        try {
            return create(CustomItemKey.parse(key), amount);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    Optional<CustomItemKey> identify(ItemStack item);
    void register(Plugin owner, CustomItemAdapter adapter);
    void unregister(Plugin owner);
    Map<String, CustomItemAdapter> adapters();

    default Collection<String> suggestions(String input) {
        return java.util.List.of();
    }

    default ItemServiceStats stats() {
        return new ItemServiceStats(0, 0, 0, 0, 0);
    }

    static CustomItemService disabled() {
        return new CustomItemService() {
            @Override public Optional<ItemStack> create(CustomItemKey key, int amount) { return Optional.empty(); }
            @Override public Optional<CustomItemKey> identify(ItemStack item) { return Optional.empty(); }
            @Override public void register(Plugin owner, CustomItemAdapter adapter) { }
            @Override public void unregister(Plugin owner) { }
            @Override public Map<String, CustomItemAdapter> adapters() { return Map.of(); }
        };
    }

    record ItemServiceStats(int adapters, int availableAdapters, long creates, long identifications, long failures) {
    }
}
