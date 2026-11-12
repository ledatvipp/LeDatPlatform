package vn.ledat.platform.common.suite;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.platform.common.scheduler.SchedulerFacade;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Premium, suite-wide startup banner for migrated LeDat plugins.
 *
 * <p>The class intentionally stores state in JVM system properties instead of
 * static fields only. Each LeDat plugin shades/relocates ledat-platform-common,
 * so system properties make the suite banner classloader-safe across jars.</p>
 */
public final class LeDatStartupBanner {
    private static final String REGISTRY_KEY = "ledat.banner.registry.v4";
    private static final String SCHEDULED_KEY = "ledat.banner.scheduled.v4";
    private static final String PRINTED_KEY = "ledat.banner.printed.v4";
    private static final String BOOT_MILLIS_KEY = "ledat.banner.boot-millis.v4";
    private static final int WIDTH = 86;
    private static final int PLUGIN_ROWS_DEFAULT = 32;

    private LeDatStartupBanner() {
    }

    /** Register a plugin with both the interop registry and the unified banner registry. */
    public static void register(JavaPlugin plugin, SchedulerFacade scheduler) {
        Objects.requireNonNull(plugin, "plugin");
        if (!LeDatConfigDefaults.interopEnabled(plugin)) {
            unregister(plugin);
            return;
        }

        LeDatInterop.register(plugin, scheduler);
        registry().put(plugin.getName(), BannerEntry.from(plugin).toMap());
        synchronized (System.getProperties()) {
            System.getProperties().putIfAbsent(BOOT_MILLIS_KEY, String.valueOf(System.currentTimeMillis()));
        }

        if (!bannerEnabled(plugin)) {
            return;
        }
        synchronized (System.getProperties()) {
            if (System.getProperty(SCHEDULED_KEY) != null) {
                return;
            }
            System.setProperty(SCHEDULED_KEY, "true");
        }
        long delay = LeDatConfigDefaults.startupBannerDelayTicks(plugin);
        if (scheduler != null) {
            scheduler.runGlobalLater(() -> printOnce(plugin.getServer().getLogger(), plugin), delay);
        } else {
            printOnce(plugin.getServer().getLogger(), plugin);
        }
    }

    /** Remove a plugin from both registries. Safe to call multiple times. */
    public static void unregister(JavaPlugin plugin) {
        if (plugin == null) {
            return;
        }
        LeDatInterop.unregister(plugin);
        registry().remove(plugin.getName());
    }

    /** Force printing a fresh banner. Useful for admin diagnostics/reload commands. */
    public static void printNow(JavaPlugin plugin) {
        if (plugin == null) {
            return;
        }
        synchronized (System.getProperties()) {
            System.clearProperty(PRINTED_KEY);
        }
        printOnce(plugin.getServer().getLogger(), plugin);
    }

    private static void printOnce(Logger logger, JavaPlugin requester) {
        synchronized (System.getProperties()) {
            if (System.getProperty(PRINTED_KEY) != null) {
                return;
            }
            System.setProperty(PRINTED_KEY, "true");
        }
        logger.info("");
        for (String line : render(requester)) {
            logger.info(line);
        }
        logger.info("");
    }

    private static List<String> render(JavaPlugin requester) {
        List<BannerEntry> entries = registry().values().stream()
                .map(BannerEntry::fromMap)
                .sorted(Comparator.comparing(BannerEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        Map<String, Object> health = LeDatInterop.healthSummary();
        boolean showCapabilities = LeDatConfigDefaults.startupBannerShowCapabilities(requester);
        boolean showStorage = LeDatConfigDefaults.startupBannerShowStorage(requester);
        Map<String, Integer> capabilityCounts = showCapabilities ? capabilityCounts(health.get("capabilities")) : Map.of();
        int totalCapabilities = capabilityCounts.values().stream().mapToInt(Integer::intValue).sum();
        String capabilityPreview = showCapabilities ? topCapabilities(capabilityCounts, 8) : "hidden";
        String storagePreview = showStorage ? storagePreview(entries) : "hidden";
        String languagePreview = languagePreview(requester);
        String server = clean(Bukkit.getName() + " " + Bukkit.getBukkitVersion());
        String java = System.getProperty("java.version", "unknown");
        String os = clean(System.getProperty("os.name", "unknown") + " " + System.getProperty("os.arch", ""));
        String cluster = LeDatConfigDefaults.clusterId(requester);
        String uptime = uptimeText();
        String generated = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now());

        List<String> lines = new ArrayList<>();
        lines.add(top());
        lines.add(center("✦  LEDAT PLUGIN SUITE  ✦"));
        lines.add(center("Premium Canvas/Folia-ready platform • SchedulerFacade • i18n • Interop"));
        lines.add(separator('═'));
        lines.add(row("Server", server, "Java", java));
        lines.add(row("Cluster", cluster, "OS", os));
        lines.add(row("Plugins", entries.size() + " registered", "Capabilities", showCapabilities ? totalCapabilities + " links" : "hidden"));
        lines.add(row("Storage", storagePreview, "Languages", languagePreview));
        lines.add(row("Scheduler", "Facade active", "Boot", uptime));
        lines.add(row("Generated", generated, "Mode", bannerMode(requester)));
        lines.add(separator('─'));
        if (showCapabilities) {
            lines.add(single("Top capabilities", capabilityPreview.isBlank() ? "none" : capabilityPreview));
        } else {
            lines.add(single("Top capabilities", "hidden by config"));
        }
        lines.add(separator('─'));
        lines.add(center("Loaded LeDat Modules"));
        lines.add(separator('─'));

        int limit = Math.max(1, LeDatConfigDefaults.startupBannerMaxPluginRows(requester));
        int index = 1;
        for (BannerEntry entry : entries) {
            if (index > limit) {
                lines.add(single("…", (entries.size() - limit) + " more modules hidden by max-plugin-rows"));
                break;
            }
            lines.add(pluginRow(index++, entry));
        }
        lines.add(separator('═'));
        lines.add(center("LeDatPlatform • stable startup • unified services • ready"));
        lines.add(bottom());
        return lines;
    }

    private static boolean bannerEnabled(JavaPlugin plugin) {
        return LeDatConfigDefaults.startupBannerEnabled(plugin);
    }

    private static String bannerMode(JavaPlugin plugin) {
        return LeDatConfigDefaults.startupBannerMode(plugin).toLowerCase(Locale.ROOT);
    }

    private static String languagePreview(JavaPlugin plugin) {
        List<String> supported = LeDatConfigDefaults.supportedLanguages(plugin);
        String fallback = LeDatConfigDefaults.fallbackLanguage(plugin);
        return String.join(",", supported) + " → " + fallback;
    }

    private static String storagePreview(List<BannerEntry> entries) {
        Set<String> values = new LinkedHashSet<>();
        for (BannerEntry entry : entries) {
            if (!entry.storage().isBlank()) {
                values.add(entry.storage());
            }
        }
        if (values.isEmpty()) {
            return "mixed";
        }
        return limit(String.join(",", values), 26);
    }

    private static String uptimeText() {
        long start = 0L;
        Object raw = System.getProperties().get(BOOT_MILLIS_KEY);
        if (raw != null) {
            try {
                start = Long.parseLong(String.valueOf(raw));
            } catch (NumberFormatException ignored) {
                start = 0L;
            }
        }
        long delta = start <= 0L ? 0L : Math.max(0L, System.currentTimeMillis() - start);
        long seconds = delta / 1000L;
        if (seconds < 60L) {
            return seconds + "s";
        }
        long minutes = seconds / 60L;
        seconds %= 60L;
        return minutes + "m " + seconds + "s";
    }

    private static Map<String, Integer> capabilityCounts(Object value) {
        Map<String, Integer> output = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                int count = 0;
                Object countValue = entry.getValue();
                if (countValue instanceof Number number) {
                    count = number.intValue();
                } else {
                    try {
                        count = Integer.parseInt(String.valueOf(countValue));
                    } catch (NumberFormatException ignored) {
                        count = 0;
                    }
                }
                if (count > 0) {
                    output.put(String.valueOf(entry.getKey()), count);
                }
            }
        }
        return output;
    }

    private static String topCapabilities(Map<String, Integer> capabilityCounts, int limit) {
        return capabilityCounts.entrySet().stream()
                .sorted((left, right) -> {
                    int byCount = Integer.compare(right.getValue(), left.getValue());
                    return byCount != 0 ? byCount : left.getKey().compareTo(right.getKey());
                })
                .limit(limit)
                .map(entry -> entry.getKey() + "×" + entry.getValue())
                .reduce((a, b) -> a + "  " + b)
                .orElse("");
    }

    private static String top() {
        return "╔" + "═".repeat(WIDTH - 2) + "╗";
    }

    private static String bottom() {
        return "╚" + "═".repeat(WIDTH - 2) + "╝";
    }

    private static String separator(char ch) {
        return "╟" + String.valueOf(ch).repeat(WIDTH - 2) + "╢";
    }

    private static String center(String text) {
        String clean = clean(text);
        int inner = WIDTH - 4;
        if (visibleLength(clean) > inner) {
            clean = limit(clean, inner);
        }
        int left = Math.max(0, (inner - visibleLength(clean)) / 2);
        int right = Math.max(0, inner - visibleLength(clean) - left);
        return "║ " + " ".repeat(left) + clean + " ".repeat(right) + " ║";
    }

    private static String single(String label, String value) {
        String text = clean(label) + ": " + clean(value);
        int inner = WIDTH - 4;
        text = limit(text, inner);
        return "║ " + padRight(text, inner) + " ║";
    }

    private static String row(String leftLabel, String leftValue, String rightLabel, String rightValue) {
        int leftWidth = 40;
        int rightWidth = WIDTH - 7 - leftWidth;
        String left = clean(leftLabel) + ": " + clean(leftValue);
        String right = clean(rightLabel) + ": " + clean(rightValue);
        return "║ " + padRight(limit(left, leftWidth), leftWidth) + " │ "
                + padRight(limit(right, rightWidth), rightWidth) + " ║";
    }

    private static String pluginRow(int index, BannerEntry entry) {
        String caps = entry.capabilities().isEmpty() ? "-" : String.join(",", entry.capabilities());
        String left = String.format(Locale.ROOT, "%02d. %-20s v%-12s", index, limit(entry.name(), 20), limit(entry.version(), 12));
        String right = "module=" + limit(entry.module(), 16)
                + " storage=" + limit(entry.storage().isBlank() ? "none" : entry.storage(), 10)
                + " caps=" + limit(caps, 24);
        int leftWidth = 38;
        int rightWidth = WIDTH - 7 - leftWidth;
        return "║ " + padRight(limit(left, leftWidth), leftWidth) + " │ "
                + padRight(limit(right, rightWidth), rightWidth) + " ║";
    }

    private static String padRight(String text, int width) {
        String clean = clean(text);
        int len = visibleLength(clean);
        if (len >= width) {
            return limit(clean, width);
        }
        return clean + " ".repeat(width - len);
    }

    private static String limit(String text, int width) {
        String clean = clean(text);
        if (visibleLength(clean) <= width) {
            return clean;
        }
        if (width <= 1) {
            return "…";
        }
        return clean.substring(0, Math.min(clean.length(), Math.max(0, width - 1))) + "…";
    }

    private static int visibleLength(String text) {
        return text == null ? 0 : text.length();
    }

    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
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

    private record BannerEntry(
            String name,
            String version,
            String module,
            String storage,
            List<String> capabilities
    ) {
        static BannerEntry from(JavaPlugin plugin) {
            Map<String, Object> profile = LeDatInterop.findPlugin(plugin.getName()).orElseGet(() -> fallbackProfile(plugin));
            return new BannerEntry(
                    string(profile.get("name"), plugin.getName()),
                    string(profile.get("version"), plugin.getDescription().getVersion()),
                    string(profile.get("module"), plugin.getName()),
                    string(profile.get("storage"), ""),
                    stringList(profile.get("capabilities"))
            );
        }

        static BannerEntry fromMap(Map<String, Object> map) {
            if (map == null) {
                return new BannerEntry("unknown", "unknown", "unknown", "", List.of());
            }
            return new BannerEntry(
                    string(map.get("name"), "unknown"),
                    string(map.get("version"), "unknown"),
                    string(map.get("module"), "unknown"),
                    string(map.get("storage"), ""),
                    stringList(map.get("capabilities"))
            );
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("version", version);
            map.put("module", module);
            map.put("storage", storage);
            map.put("capabilities", new ArrayList<>(capabilities));
            return map;
        }

        private static Map<String, Object> fallbackProfile(JavaPlugin plugin) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", plugin.getName());
            map.put("version", plugin.getDescription().getVersion());
            map.put("module", plugin.getName());
            map.put("capabilities", List.of("utility"));
            return map;
        }
    }

    private static String string(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
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
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : collection) {
            String normalized = String.valueOf(item).trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
            if (!normalized.isBlank() && seen.add(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }
}

