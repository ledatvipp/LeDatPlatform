package vn.ledat.platform.core.hook;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.hook.HookRegistry;
import vn.ledat.platform.api.hook.HookStatus;
import vn.ledat.platform.core.bedrockform.FloodgateCumulusBridge;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultHookRegistry implements HookRegistry {
    private final Map<String, Entry> hooks = new ConcurrentHashMap<>();

    @Override
    public void register(Plugin owner, String hookName, Object adapter, boolean available, String detail) {
        hooks.put(normalize(hookName), new Entry(owner == null ? "unknown" : owner.getName(), adapter, available, detail == null ? "" : detail));
    }

    @Override
    public void unregister(Plugin owner, String hookName) {
        hooks.remove(normalize(hookName));
    }

    @Override
    public void unregisterPlugin(Plugin owner) {
        if (owner == null) {
            return;
        }
        hooks.entrySet().removeIf(entry -> entry.getValue().owner.equalsIgnoreCase(owner.getName()));
    }

    @Override
    public boolean available(String hookName) {
        Entry entry = hooks.get(normalize(hookName));
        if (entry != null) {
            return entry.available;
        }
        return findPlugin(hookName).isPresent();
    }

    @Override
    public <T> Optional<T> adapter(String hookName, Class<T> type) {
        Entry entry = hooks.get(normalize(hookName));
        if (entry == null || entry.adapter == null || !type.isInstance(entry.adapter)) {
            return Optional.empty();
        }
        return Optional.of(type.cast(entry.adapter));
    }

    @Override
    public List<HookStatus> statuses() {
        return hooks.entrySet().stream()
                .map(entry -> new HookStatus(entry.getValue().owner, entry.getKey(), entry.getValue().available, entry.getValue().detail))
                .sorted(java.util.Comparator.comparing(HookStatus::hookName))
                .toList();
    }

    @Override
    public void reload() {
        registerDetected("PlaceholderAPI", "PlaceholderAPI");
        registerDetected("Vault", "Vault");
        registerDetected("TAB", "TAB");
        registerDetected("UnlimitedNametags", "UnlimitedNameTags", "UnlimitedNametags");
        registerDetected("SkinsRestorer", "SkinsRestorer");
        registerDetected("WorldGuard", "WorldGuard");
        registerDetectedWithDetail("ViaVersion", "detected from PluginManager; used for dialog client protocol fallback gate", "ViaVersion");
        registerDetected("Geyser", "Geyser", "Geyser-Spigot");
        registerDetected("Geyser-Spigot", "Geyser-Spigot", "Geyser");
        registerFloodgateForms();
    }

    private String normalize(String hookName) {
        return hookName == null ? "unknown" : hookName.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void registerDetected(String hookName, String... pluginNames) {
        registerDetectedWithDetail(hookName, "detected from PluginManager", pluginNames);
    }

    private void registerDetectedWithDetail(String hookName, String detail, String... pluginNames) {
        Optional<Plugin> plugin = findPlugin(pluginNames);
        String resolvedDetail = plugin
                .map(value -> detail + " (" + value.getName() + ")")
                .orElse(detail + " (missing: " + String.join("/", pluginNames) + ")");
        register(null, hookName, plugin.orElse(null), plugin.isPresent(), resolvedDetail);
    }

    private Optional<Plugin> findPlugin(String... pluginNames) {
        if (pluginNames == null) {
            return Optional.empty();
        }
        for (String pluginName : pluginNames) {
            if (pluginName == null || pluginName.isBlank()) {
                continue;
            }
            Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
            if (plugin != null && plugin.isEnabled()) {
                return Optional.of(plugin);
            }
        }
        return Optional.empty();
    }

    private void registerFloodgateForms() {
        FloodgateCumulusBridge.Detection detection = FloodgateCumulusBridge.detect(nullSafePlugin());
        Plugin floodgate = findPlugin("floodgate", "Floodgate").orElse(null);
        register(null, "Floodgate", floodgate, detection.floodgateClassDetected(),
                detection.detail() + (detection.floodgateClassDetected() ? " (api class available)" : " (api class missing)"));
        Plugin geyser = findPlugin("Geyser", "Geyser-Spigot").orElse(null);
        register(null, "GeyserApi", geyser, detection.geyserApiClassDetected(),
                detection.detail() + (detection.geyserApiClassDetected() ? " (api class available)" : " (api class missing)"));
        register(null, "CumulusForms", null, detection.cumulusClassDetected(),
                detection.cumulusClassDetected() ? "Cumulus form classes available for Bedrock Forms" : "Cumulus form classes missing");
    }

    private Plugin nullSafePlugin() {
        return Bukkit.getPluginManager().getPlugin("LeDatPlatform");
    }

    private record Entry(String owner, Object adapter, boolean available, String detail) {
    }
}
