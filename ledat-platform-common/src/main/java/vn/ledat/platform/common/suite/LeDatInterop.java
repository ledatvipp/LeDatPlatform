package vn.ledat.platform.common.suite;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.platform.common.scheduler.SchedulerFacade;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-local interop registry for migrated LeDat plugins.
 *
 * <p>This registry intentionally stores only JDK types (Map/List/String/Boolean/Number).
 * That is important because each migrated plugin shades and relocates
 * ledat-platform-common, so a normal Java interface registered through Bukkit
 * ServicesManager would not be classloader-safe unless a separate API plugin is installed.</p>
 */
public final class LeDatInterop {
    private static final String REGISTRY_KEY = "ledat.suite.registry.v2";
    private static final String EVENT_HISTORY_KEY = "ledat.suite.event-history.v2";
    private static final int MAX_EVENTS = 150;

    private LeDatInterop() {
    }

    public static void register(JavaPlugin plugin, SchedulerFacade scheduler) {
        if (plugin == null) {
            return;
        }
        if (!LeDatConfigDefaults.interopEnabled(plugin)) {
            unregister(plugin);
            return;
        }
        register(plugin, scheduler, loadDescriptor(plugin));
    }

    public static void register(JavaPlugin plugin, SchedulerFacade scheduler, Map<String, Object> metadata) {
        if (plugin == null) {
            return;
        }
        if (!LeDatConfigDefaults.interopEnabled(plugin)) {
            unregister(plugin);
            return;
        }

        Map<String, Object> safeMetadata = metadata == null ? Map.of() : metadata;
        PluginDescriptionFile description = plugin.getDescription();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", string(safeMetadata.get("id"), normalizeId(plugin.getName())));
        profile.put("name", plugin.getName());
        profile.put("version", description.getVersion());
        profile.put("main", description.getMain());
        profile.put("apiVersion", apiVersion(description));
        profile.put("module", string(safeMetadata.get("module"), normalizeModule(plugin.getName())));
        profile.put("suiteVersion", number(safeMetadata.get("suite-version"), 1));
        profile.put("clusterId", LeDatConfigDefaults.clusterId(plugin));
        profile.put("configVersion", LeDatConfigDefaults.configVersion(plugin));
        profile.put("dataFolder", plugin.getDataFolder().getPath());
        profile.put("enabled", plugin.isEnabled());
        profile.put("foliaSafe", true);
        profile.put("scheduler", scheduler == null ? "none" : scheduler.getClass().getName());
        profile.put("capabilities", capabilities(plugin, safeMetadata));
        profile.put("configFiles", stringList(safeMetadata.get("config-files")));
        profile.put("softDepends", new ArrayList<>(description.getSoftDepend()));
        profile.put("depends", new ArrayList<>(description.getDepend()));
        profile.put("registeredAtMillis", System.currentTimeMillis());
        profile.put("lastSeenMillis", System.currentTimeMillis());

        Object storage = safeMetadata.get("storage");
        if (storage != null) {
            profile.put("storage", String.valueOf(storage));
        }
        Object api = safeMetadata.get("api");
        if (api instanceof Map<?, ?> apiMap) {
            profile.put("api", sanitizeMap(apiMap));
        }
        Object notes = safeMetadata.get("notes");
        if (notes != null) {
            profile.put("notes", String.valueOf(notes));
        }

        registry().put(plugin.getName(), profile);
        publish("plugin-registered", Map.of(
                "plugin", plugin.getName(),
                "version", description.getVersion(),
                "capabilities", String.join(",", stringList(profile.get("capabilities")))
        ));
    }

    public static void heartbeat(JavaPlugin plugin) {
        if (plugin == null) {
            return;
        }
        Map<String, Object> profile = registry().get(plugin.getName());
        if (profile != null) {
            profile.put("lastSeenMillis", System.currentTimeMillis());
            profile.put("enabled", plugin.isEnabled());
            profile.put("configVersion", LeDatConfigDefaults.configVersion(plugin));
            profile.put("clusterId", LeDatConfigDefaults.clusterId(plugin));
        }
    }

    public static void unregister(JavaPlugin plugin) {
        if (plugin == null) {
            return;
        }
        Map<String, Object> removed = registry().remove(plugin.getName());
        if (removed != null) {
            publish("plugin-unregistered", Map.of("plugin", plugin.getName()));
        }
    }

    public static List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> value : registry().values()) {
            result.add(copyProfile(value));
        }
        result.sort(Comparator.comparing(map -> String.valueOf(map.getOrDefault("name", "")), String.CASE_INSENSITIVE_ORDER));
        return Collections.unmodifiableList(result);
    }

    public static Optional<Map<String, Object>> findPlugin(String pluginNameOrId) {
        if (pluginNameOrId == null || pluginNameOrId.isBlank()) {
            return Optional.empty();
        }
        String needle = pluginNameOrId.trim();
        Map<String, Object> direct = registry().get(needle);
        if (direct != null) {
            return Optional.of(copyProfile(direct));
        }
        String normalized = normalizeId(needle);
        return snapshot().stream()
                .filter(profile -> normalized.equals(normalizeId(String.valueOf(profile.get("id"))))
                        || normalized.equals(normalizeId(String.valueOf(profile.get("name")))))
                .findFirst();
    }

    public static List<Map<String, Object>> findByCapability(String capability) {
        String normalized = normalizeCapability(capability);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> profile : snapshot()) {
            if (stringList(profile.get("capabilities")).contains(normalized)) {
                result.add(profile);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static boolean hasCapability(String capability) {
        return !findByCapability(capability).isEmpty();
    }

    public static Map<String, Object> healthSummary() {
        List<Map<String, Object>> plugins = snapshot();
        Map<String, Integer> capabilityCounts = new LinkedHashMap<>();
        Map<String, Integer> storageCounts = new LinkedHashMap<>();
        for (Map<String, Object> profile : plugins) {
            for (String capability : stringList(profile.get("capabilities"))) {
                capabilityCounts.merge(capability, 1, Integer::sum);
            }
            String storage = string(profile.get("storage"), "unknown").toLowerCase(Locale.ROOT);
            storageCounts.merge(storage, 1, Integer::sum);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("pluginCount", plugins.size());
        summary.put("capabilities", capabilityCounts);
        summary.put("storage", storageCounts);
        summary.put("eventCount", eventHistory().size());
        summary.put("generatedAtMillis", System.currentTimeMillis());
        return summary;
    }

    public static void publish(String channel, Map<String, String> payload) {
        if (channel == null || channel.isBlank()) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("channel", channel.trim().toLowerCase(Locale.ROOT));
        event.put("payload", payload == null ? Map.of() : new LinkedHashMap<>(payload));
        event.put("createdAtMillis", System.currentTimeMillis());
        ArrayDeque<Map<String, Object>> history = eventHistory();
        synchronized (history) {
            history.addFirst(event);
            while (history.size() > MAX_EVENTS) {
                history.removeLast();
            }
        }
    }

    public static List<Map<String, Object>> recentEvents(int limit) {
        int capped = Math.max(1, Math.min(MAX_EVENTS, limit));
        List<Map<String, Object>> result = new ArrayList<>();
        ArrayDeque<Map<String, Object>> history = eventHistory();
        synchronized (history) {
            int count = 0;
            for (Map<String, Object> event : history) {
                result.add(copyProfile(event));
                if (++count >= capped) {
                    break;
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static int purgeStale(long maxAgeMillis) {
        long cutoff = System.currentTimeMillis() - Math.max(1L, maxAgeMillis);
        int removed = 0;
        for (Map.Entry<String, Map<String, Object>> entry : registry().entrySet()) {
            long lastSeen = number(entry.getValue().get("lastSeenMillis"), 0);
            if (lastSeen > 0L && lastSeen < cutoff && registry().remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        if (removed > 0) {
            publish("registry-purged", Map.of("removed", String.valueOf(removed)));
        }
        return removed;
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, Map<String, Object>> registry() {
        synchronized (System.getProperties()) {
            Object existing = System.getProperties().get(REGISTRY_KEY);
            if (existing instanceof ConcurrentHashMap<?, ?> map) {
                return (ConcurrentHashMap<String, Map<String, Object>>) map;
            }
            ConcurrentHashMap<String, Map<String, Object>> created = new ConcurrentHashMap<>();
            System.getProperties().put(REGISTRY_KEY, created);
            return created;
        }
    }

    @SuppressWarnings("unchecked")
    private static ArrayDeque<Map<String, Object>> eventHistory() {
        synchronized (System.getProperties()) {
            Object existing = System.getProperties().get(EVENT_HISTORY_KEY);
            if (existing instanceof ArrayDeque<?> deque) {
                return (ArrayDeque<Map<String, Object>>) deque;
            }
            ArrayDeque<Map<String, Object>> created = new ArrayDeque<>();
            System.getProperties().put(EVENT_HISTORY_KEY, created);
            return created;
        }
    }

    private static String apiVersion(PluginDescriptionFile description) {
        try {
            Object value = description.getClass().getMethod("getAPIVersion").invoke(description);
            return value == null ? "unknown" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static Map<String, Object> loadDescriptor(JavaPlugin plugin) {
        try (InputStream input = plugin.getResource("ledat-plugin.yml")) {
            if (input == null) {
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("capabilities", detectCapabilities(plugin));
                return fallback;
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : yaml.getKeys(false)) {
                Object value = yaml.get(key);
                if (value instanceof ConfigurationSection section) {
                    map.put(key, sanitizeMap(section.getValues(true)));
                } else {
                    map.put(key, value);
                }
            }
            if (!map.containsKey("capabilities")) {
                map.put("capabilities", detectCapabilities(plugin));
            }
            return map;
        } catch (Throwable error) {
            plugin.getLogger().warning("Failed to load ledat-plugin.yml for interop: " + error.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("capabilities", detectCapabilities(plugin));
            return fallback;
        }
    }

    private static List<String> capabilities(JavaPlugin plugin, Map<String, Object> metadata) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.addAll(detectCapabilities(plugin));
        values.addAll(stringList(metadata.get("capabilities")));
        values.removeIf(String::isBlank);
        return new ArrayList<>(values);
    }

    private static List<String> detectCapabilities(JavaPlugin plugin) {
        String name = plugin.getName().toLowerCase(Locale.ROOT);
        Set<String> values = new LinkedHashSet<>();
        values.add(LeDatCapabilities.STORAGE);
        values.add(LeDatCapabilities.LANGUAGE);
        if (name.equals("ledatplatform") || name.contains("platform")) values.addAll(List.of(LeDatCapabilities.PLATFORM, LeDatCapabilities.SHARED_DATABASE, LeDatCapabilities.MIGRATION_REGISTRY, LeDatCapabilities.NODE_REGISTRY, LeDatCapabilities.AUDIT_LOG));
        if (name.contains("shardtransfer") || name.contains("shard-transfer")) values.addAll(List.of(LeDatCapabilities.SHARED_DATABASE, LeDatCapabilities.NODE_REGISTRY, LeDatCapabilities.PLAYER_DATA, LeDatCapabilities.TELEPORT));
        if (name.contains("nodeagent") || name.contains("node-agent")) values.addAll(List.of(LeDatCapabilities.SHARED_DATABASE, LeDatCapabilities.LOGGING, LeDatCapabilities.ADMIN));
        if (name.contains("data-bridge") || name.contains("mcbridge")) values.addAll(List.of(LeDatCapabilities.SHARED_DATABASE, LeDatCapabilities.PLAYER_DATA));
        if (name.contains("settings")) values.addAll(List.of(LeDatCapabilities.PLAYER_SETTINGS, LeDatCapabilities.GUI, LeDatCapabilities.LEADERBOARD));
        if (name.contains("shop")) values.addAll(List.of(LeDatCapabilities.SHOP, LeDatCapabilities.ECONOMY, LeDatCapabilities.GUI));
        if (name.contains("crates")) values.addAll(List.of(LeDatCapabilities.CRATE, LeDatCapabilities.ECONOMY, LeDatCapabilities.GUI));
        if (name.contains("cosmetic")) values.addAll(List.of(LeDatCapabilities.COSMETIC, LeDatCapabilities.CHAT, LeDatCapabilities.ANTI_SPAM, LeDatCapabilities.GUI));
        if (name.contains("tp") || name.contains("rtp")) values.add(LeDatCapabilities.TELEPORT);
        if (name.contains("rtp")) values.add(LeDatCapabilities.RTP);
        if (name.contains("team")) values.add(LeDatCapabilities.TEAM);
        if (name.contains("duel")) values.addAll(List.of(LeDatCapabilities.PLAYER_DATA, LeDatCapabilities.GUI));
        if (name.contains("afk")) values.addAll(List.of(LeDatCapabilities.AFK, LeDatCapabilities.ECONOMY, LeDatCapabilities.GUI));
        if (name.contains("order")) values.addAll(List.of(LeDatCapabilities.ORDER, LeDatCapabilities.ECONOMY, LeDatCapabilities.GUI));
        if (name.contains("bounty")) values.addAll(List.of(LeDatCapabilities.BOUNTY, LeDatCapabilities.ECONOMY, LeDatCapabilities.GUI));
        if (name.contains("mythictools")) values.add(LeDatCapabilities.TOOL);
        if (name.contains("worth")) values.add(LeDatCapabilities.WORTH);
        if (name.contains("keyall") || name.contains("billford") || name.contains("lottery")) values.addAll(List.of(LeDatCapabilities.ECONOMY, LeDatCapabilities.GUI));
        if (name.contains("phantoms")) values.add(LeDatCapabilities.PHANTOM_PROTECTION);
        if (name.contains("nightvision")) values.add(LeDatCapabilities.NIGHT_VISION);
        if (name.contains("country") || name.contains("phantoms") || name.contains("nightvision") || name.contains("ping") || name.contains("findplayers")) values.add(LeDatCapabilities.PLAYER_DATA);
        return new ArrayList<>(values);
    }

    private static String normalizeId(String value) {
        if (value == null) {
            return "unknown";
        }
        String raw = value.trim();
        if (raw.isEmpty()) {
            return "unknown";
        }
        String kebab = raw.replaceAll("([a-z])([A-Z])", "$1-$2")
                .replace('_', '-')
                .replace(' ', '-')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "")
                .replaceAll("-+", "-");
        return kebab.startsWith("ledat-") ? kebab : "ledat-" + kebab.replaceFirst("^ledat", "");
    }

    private static String normalizeModule(String pluginName) {
        return "plugin-" + normalizeId(pluginName);
    }

    private static String normalizeCapability(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
    }

    private static String string(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        Collection<?> collection;
        if (value instanceof Collection<?> c) {
            collection = c;
        } else if (value instanceof String text) {
            collection = List.of(text.split(","));
        } else {
            collection = List.of(value);
        }
        List<String> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (Object item : collection) {
            String normalized = normalizeCapability(String.valueOf(item));
            if (!normalized.isBlank() && seen.add(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static Map<String, Object> sanitizeMap(Map<?, ?> input) {
        Map<String, Object> output = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Collection<?> collection) {
                output.put(String.valueOf(entry.getKey()), new ArrayList<>(collection));
            } else if (value instanceof Map<?, ?> map) {
                output.put(String.valueOf(entry.getKey()), sanitizeMap(map));
            } else {
                output.put(String.valueOf(entry.getKey()), String.valueOf(value));
            }
        }
        return output;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyProfile(Map<String, Object> input) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (input == null) {
            return output;
        }
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                output.put(entry.getKey(), sanitizeMap(map));
            } else if (value instanceof Collection<?> collection) {
                output.put(entry.getKey(), new ArrayList<>(collection));
            } else {
                output.put(entry.getKey(), value);
            }
        }
        return output;
    }
}
