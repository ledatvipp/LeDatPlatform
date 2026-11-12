package vn.ledat.platform.core.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.item.CustomItemAdapter;
import vn.ledat.platform.api.item.CustomItemKey;
import vn.ledat.platform.api.item.CustomItemService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class DefaultCustomItemService implements CustomItemService {
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    private final Object mutationLock = new Object();
    private volatile List<Registration> identificationOrder = List.of();
    private final AtomicLong creates = new AtomicLong();
    private final AtomicLong identifications = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public DefaultCustomItemService(Plugin platformPlugin) {
        register(platformPlugin, new VanillaAdapter());
    }

    @Override
    public Optional<ItemStack> create(CustomItemKey key, int amount) {
        if (key == null) {
            return Optional.empty();
        }
        Registration registration = registrations.get(normalize(key.provider()));
        if (registration == null || !safeAvailable(registration.adapter)) {
            return Optional.empty();
        }
        try {
            Optional<ItemStack> created = registration.adapter.create(key, Math.max(1, Math.min(99, amount)));
            created.ifPresent(item -> item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), amount))));
            creates.incrementAndGet();
            return created.map(ItemStack::clone);
        } catch (Throwable error) {
            failures.incrementAndGet();
            registration.owner.getLogger().warning("Custom item create failed via " + key.provider() + ": " + safe(error));
            return Optional.empty();
        }
    }

    @Override
    public Optional<CustomItemKey> identify(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Optional.empty();
        }
        for (Registration registration : identificationOrder) {
            if (!safeAvailable(registration.adapter)) {
                continue;
            }
            try {
                Optional<CustomItemKey> identified = registration.adapter.identify(item);
                if (identified.isPresent()) {
                    identifications.incrementAndGet();
                    return identified;
                }
            } catch (Throwable error) {
                failures.incrementAndGet();
            }
        }
        return Optional.empty();
    }

    @Override
    public void register(Plugin owner, CustomItemAdapter adapter) {
        if (owner == null || adapter == null || normalize(adapter.provider()).isBlank()) {
            return;
        }
        synchronized (mutationLock) {
            registrations.put(normalize(adapter.provider()), new Registration(owner, adapter));
            rebuildIdentificationOrder();
        }
    }

    @Override
    public void unregister(Plugin owner) {
        if (owner != null) {
            synchronized (mutationLock) {
                registrations.entrySet().removeIf(entry -> entry.getValue().owner.equals(owner));
                rebuildIdentificationOrder();
            }
        }
    }

    @Override
    public Map<String, CustomItemAdapter> adapters() {
        Map<String, CustomItemAdapter> result = new LinkedHashMap<>();
        registrations.forEach((key, registration) -> result.put(key, registration.adapter));
        return Map.copyOf(result);
    }

    @Override
    public Collection<String> suggestions(String input) {
        String prefix = normalize(input);
        List<String> result = new ArrayList<>();
        registrations.forEach((provider, registration) -> {
            if (!safeAvailable(registration.adapter)) {
                return;
            }
            if (provider.startsWith(prefix) || prefix.startsWith(provider + ":")) {
                result.add(provider + ":");
                try {
                    result.addAll(registration.adapter.suggestions(prefix));
                } catch (Throwable ignored) {
                }
            }
        });
        return result.stream().filter(value -> value != null && value.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().limit(200).toList();
    }

    @Override
    public ItemServiceStats stats() {
        int available = (int) registrations.values().stream().filter(registration -> safeAvailable(registration.adapter)).count();
        return new ItemServiceStats(registrations.size(), available, creates.get(), identifications.get(), failures.get());
    }

    private boolean safeAvailable(CustomItemAdapter adapter) {
        try {
            return adapter.available();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void rebuildIdentificationOrder() {
        identificationOrder = registrations.values().stream()
                .sorted((left, right) -> Boolean.compare(left.adapter instanceof VanillaAdapter, right.adapter instanceof VanillaAdapter))
                .toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String safe(Throwable error) {
        String message = error == null ? "unknown" : error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record Registration(Plugin owner, CustomItemAdapter adapter) {
    }

    private static final class VanillaAdapter implements CustomItemAdapter {
        private static volatile List<String> suggestionCache;

        @Override public String provider() { return "minecraft"; }
        @Override public boolean available() { return true; }

        @Override
        public Optional<ItemStack> create(CustomItemKey key, int amount) {
            String raw = key.namespace().isBlank() ? key.id() : key.namespace() + "_" + key.id();
            Material material = Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
            return material == null || material.isAir() ? Optional.empty() : Optional.of(new ItemStack(material, Math.max(1, amount)));
        }

        @Override
        public Optional<CustomItemKey> identify(ItemStack item) {
            return Optional.of(new CustomItemKey("minecraft", "", item.getType().getKey().getKey()));
        }

        @Override
        public Collection<String> suggestions(String input) {
            String prefix = input == null ? "" : input.toLowerCase(Locale.ROOT);
            return cachedSuggestions().stream()
                    .filter(value -> value.startsWith(prefix))
                    .limit(200)
                    .toList();
        }

        private static List<String> cachedSuggestions() {
            List<String> current = suggestionCache;
            if (current != null) {
                return current;
            }
            synchronized (VanillaAdapter.class) {
                current = suggestionCache;
                if (current != null) {
                    return current;
                }
                try {
                    current = java.util.Arrays.stream(Material.values())
                            .filter(material -> material.isItem() && !material.isAir())
                            .map(material -> "minecraft:" + material.getKey().getKey())
                            .toList();
                    suggestionCache = current;
                    return current;
                } catch (Throwable unavailableRegistry) {
                    // Paper's unit-test API has no RegistryAccess. Do not cache
                    // that transient state because runtime registry access is available after server bootstrap.
                    return List.of();
                }
            }
        }
    }
}
