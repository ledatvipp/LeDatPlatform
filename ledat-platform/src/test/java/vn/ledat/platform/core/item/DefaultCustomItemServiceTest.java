package vn.ledat.platform.core.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import vn.ledat.platform.api.item.CustomItemAdapter;
import vn.ledat.platform.api.item.CustomItemKey;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultCustomItemServiceTest {
    @Test
    void customProviderRemainsAheadOfVanillaAcrossRegistryMutations() {
        Plugin platform = plugin("LeDatPlatform");
        Plugin owner = plugin("CustomItems");
        DefaultCustomItemService service = new DefaultCustomItemService(platform);
        service.register(owner, adapter("custom", "first"));

        ItemStack item = mock(ItemStack.class);
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(item.getType()).thenReturn(material);
        assertEquals("custom:first", service.identify(item).orElseThrow().canonical());

        service.register(owner, adapter("custom", "replacement"));
        assertEquals("custom:replacement", service.identify(item).orElseThrow().canonical());

        service.unregister(owner);
        assertTrue(service.identify(item).isEmpty());
        assertTrue(!service.adapters().containsKey("custom"));
    }

    @Test
    void suggestionsStayPrefixFilteredDistinctAndBounded() {
        DefaultCustomItemService service = new DefaultCustomItemService(plugin("LeDatPlatform"));
        Collection<String> suggestions = service.suggestions("minecraft:st");

        assertTrue(suggestions.size() <= 200);
        assertEquals(suggestions.size(), suggestions.stream().distinct().count());
        assertTrue(suggestions.stream().allMatch(value -> value.startsWith("minecraft:st")));
    }

    private CustomItemAdapter adapter(String provider, String id) {
        return new CustomItemAdapter() {
            @Override public String provider() { return provider; }
            @Override public boolean available() { return true; }
            @Override public Optional<ItemStack> create(CustomItemKey key, int amount) { return Optional.empty(); }
            @Override public Optional<CustomItemKey> identify(ItemStack item) { return Optional.of(new CustomItemKey(provider, "", id)); }
            @Override public Collection<String> suggestions(String input) { return List.of(provider + ":" + id); }
        };
    }

    private Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> name;
            default -> defaultValue(method.getReturnType());
        });
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
