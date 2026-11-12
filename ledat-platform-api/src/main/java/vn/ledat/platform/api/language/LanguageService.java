package vn.ledat.platform.api.language;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public interface LanguageService {
    String defaultLocale();
    String fallbackLocale();
    String resolveLocale(CommandSender sender);
    default String resolveLocale(UUID playerId) {
        return defaultLocale();
    }
    void setLocale(Player player, String locale);
    default void setLocale(UUID playerId, String locale, String source) {
    }
    default boolean setLocaleIfAbsent(UUID playerId, String locale, String source) {
        return false;
    }
    void clearLocale(Player player);
    default void clearLocale(UUID playerId) {
    }

    default List<String> requiredLocales() {
        return List.of(defaultLocale(), fallbackLocale());
    }

    default String displayName(String locale) {
        return switch (normalize(locale)) {
            case "vi_VN" -> "Tiếng Việt";
            case "en_US" -> "English";
            case "zh_CN" -> "简体中文";
            default -> normalize(locale);
        };
    }

    default AutoCloseable listenLocaleChanges(LanguageChangeListener listener) {
        return () -> { };
    }

    /**
     * Register and load a plugin language folder using plugins/<plugin>/languages/<locale>.yml.
     * Missing bundled files are copied from the child plugin jar when available.
     */
    default void registerPlugin(Plugin plugin) {
    }

    /**
     * Remove cached language bundles and bossbars for a plugin. Usually called on plugin disable.
     */
    default void unregisterPlugin(Plugin plugin) {
    }

    /**
     * Reload a single plugin language folder without touching other bundles.
     */
    default void reload(Plugin plugin) {
        reload();
    }

    /**
     * Supported locales detected for a plugin from config + languages/*.yml.
     */
    default List<String> supportedLocales(Plugin plugin) {
        return List.of(defaultLocale(), fallbackLocale());
    }

    /**
     * Lightweight runtime snapshot for diagnostics and capacity planning.
     */
    default LanguageStats stats() {
        return new LanguageStats(defaultLocale(), fallbackLocale(), 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
    }

    /**
     * Physical languages folder of a plugin.
     */
    default File languageFolder(Plugin plugin) {
        return plugin == null ? new File("languages") : new File(plugin.getDataFolder(), "languages");
    }

    Component component(Plugin plugin, String locale, String key, Map<String, String> placeholders);

    /**
     * Render a component while allowing a small, explicit set of trusted placeholders to keep MiniMessage tags.
     * Use this only for admin-controlled config values such as configured color tags, never for player input.
     */
    default Component componentTrusted(Plugin plugin, String locale, String key, Map<String, String> placeholders, List<String> trustedPlaceholderKeys) {
        return component(plugin, locale, key, placeholders);
    }

    List<Component> componentList(Plugin plugin, String locale, String key, List<String> fallback, Map<String, String> placeholders);

    /**
     * Render a component list while allowing a small, explicit set of trusted placeholders to keep MiniMessage tags.
     * Use this for internal fragments such as localized status labels, not for player-controlled input.
     */
    default List<Component> componentListTrusted(Plugin plugin, String locale, String key, List<String> fallback,
                                                 Map<String, String> placeholders, List<String> trustedPlaceholderKeys) {
        return componentList(plugin, locale, key, fallback, placeholders);
    }

    /**
     * Resolve the MiniMessage string after fallback and placeholder replacement.
     * Intended for trusted message fragments from language files, not direct player input.
     */
    default String miniMessage(Plugin plugin, String locale, String key, Map<String, String> placeholders) {
        return plain(plugin, locale, key, placeholders);
    }

    String plain(Plugin plugin, String locale, String key, Map<String, String> placeholders);
    boolean has(Plugin plugin, String locale, String key);

    default boolean hasList(Plugin plugin, String locale, String key) {
        return has(plugin, locale, key);
    }

    void send(CommandSender sender, Plugin plugin, String key, Map<String, String> placeholders);

    default void sendTrusted(CommandSender sender, Plugin plugin, String key, Map<String, String> placeholders, List<String> trustedPlaceholderKeys) {
        if (sender != null) {
            sender.sendMessage(componentTrusted(plugin, resolveLocale(sender), key, placeholders, trustedPlaceholderKeys));
        }
    }

    void sendList(CommandSender sender, Plugin plugin, String key, List<String> fallback, Map<String, String> placeholders);
    void actionBar(Player player, Plugin plugin, String key, Map<String, String> placeholders);

    default void actionBarTrusted(Player player, Plugin plugin, String key, Map<String, String> placeholders, List<String> trustedPlaceholderKeys) {
        if (player != null) {
            player.sendActionBar(componentTrusted(plugin, resolveLocale(player), key, placeholders, trustedPlaceholderKeys));
        }
    }
    void title(Player player, Plugin plugin, String titleKey, String subtitleKey, Duration fadeIn, Duration stay, Duration fadeOut, Map<String, String> placeholders);
    void bossBar(Player player, Plugin plugin, String barId, String key, float progress, Duration duration, Map<String, String> placeholders);
    void hideBossBar(Player player, String barId);

    /**
     * Cleanup player-scoped language state such as locale overrides and bossbar handles.
     * Implementations should call this on quit to avoid retaining stale UUID state.
     */
    default void cleanup(UUID playerId) { }

    void reload();

    static Map<String, String> placeholders(String key, Object value) {
        return Map.of(key, String.valueOf(value));
    }

    static String normalize(String locale) {
        if (locale == null || locale.isBlank()) {
            return "vi_VN";
        }
        String raw = locale.replace('-', '_').trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "vi", "vn", "vi_vn", "vietnamese", "tieng_viet", "tiếng_việt" -> {
                return "vi_VN";
            }
            case "en", "us", "en_us", "english" -> {
                return "en_US";
            }
            case "zh", "cn", "chs", "zh_cn", "chinese", "simplified_chinese" -> {
                return "zh_CN";
            }
            default -> {
            }
        }
        String[] split = raw.split("_", 2);
        if (split.length == 1) {
            return split[0].toLowerCase(Locale.ROOT);
        }
        return split[0].toLowerCase(Locale.ROOT) + "_" + split[1].toUpperCase(Locale.ROOT);
    }

    record LanguageStats(
            String defaultLocale,
            String fallbackLocale,
            int registeredPlugins,
            int loadedBundles,
            int localeOverrides,
            int playersWithBossBars,
            int activeBossBars,
            int missingMessageWarningKeys,
            int invalidMessageWarningKeys,
            int savedLocales,
            int listeners,
            List<String> requiredLocales
    ) {
    }

    record LanguageChange(
            UUID playerId,
            String previousLocale,
            String newLocale,
            String source,
            long changedAtMillis
    ) {
    }

    @FunctionalInterface
    interface LanguageChangeListener {
        void onLanguageChange(LanguageChange change);
    }
}
