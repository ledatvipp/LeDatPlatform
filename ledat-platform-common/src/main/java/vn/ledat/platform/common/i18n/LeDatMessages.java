package vn.ledat.platform.common.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.platform.common.config.LeDatConfigMigrator;
import vn.ledat.platform.common.text.LeDatText;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared MiniMessage language loader for the LeDat suite.
 *
 * <p>Canonical language files:</p>
 * <ul>
 *     <li>plugins/&lt;Plugin&gt;/languages/vi_VN.yml</li>
 *     <li>plugins/&lt;Plugin&gt;/languages/en_US.yml</li>
 *     <li>plugins/&lt;Plugin&gt;/languages/zh_CN.yml</li>
 * </ul>
 *
 * Existing admin translations are preserved. Bundled defaults are only merged
 * when a key is missing. This class intentionally uses only Bukkit/JDK types in
 * public methods so shaded/relocated plugins can adopt it gradually.
 */
public final class LeDatMessages {
    public static final int LANGUAGE_CONFIG_VERSION = 4;

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    private static final Map<String, Bundle> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> MISSING_KEY_LOG = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> PARSE_ERROR_LOG = new ConcurrentHashMap<>();

    private LeDatMessages() {
    }

    public static void ensure(JavaPlugin plugin) {
        ensure(plugin, LANGUAGE_CONFIG_VERSION);
    }

    public static void ensure(JavaPlugin plugin, int targetVersion) {
        Objects.requireNonNull(plugin, "plugin");
        for (String language : LeDatLanguageCodes.canonicalLanguages()) {
            migrateIfBundled(plugin, "languages/" + language + ".yml", targetVersion);
        }
        reload(plugin);
    }

    public static void reload(JavaPlugin plugin) {
        if (plugin == null) {
            return;
        }
        CACHE.remove(plugin.getName());
        bundle(plugin);
    }

    public static void clear(JavaPlugin plugin) {
        if (plugin != null) {
            CACHE.remove(plugin.getName());
        }
    }

    public static void clearAll() {
        CACHE.clear();
        MISSING_KEY_LOG.clear();
        PARSE_ERROR_LOG.clear();
    }

    public static Map<String, String> placeholders(Object... pairs) {
        if (pairs == null || pairs.length == 0) {
            return Map.of();
        }
        Map<String, String> placeholders = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object key = pairs[i];
            if (key == null) {
                continue;
            }
            Object value = pairs[i + 1];
            placeholders.put(String.valueOf(key), value == null ? "" : String.valueOf(value));
        }
        return placeholders;
    }

    public static Component component(JavaPlugin plugin, CommandSender sender, String key) {
        return component(plugin, sender, key, Map.of(), null);
    }

    public static Component component(JavaPlugin plugin, CommandSender sender, String key, Map<String, String> placeholders) {
        return component(plugin, sender, key, placeholders, null);
    }

    public static Component component(JavaPlugin plugin, CommandSender sender, String key, String fallback, Object... placeholderPairs) {
        return component(plugin, sender, key, placeholders(placeholderPairs), fallback);
    }

    public static Component component(JavaPlugin plugin, CommandSender sender, String key, Map<String, String> placeholders, String fallback) {
        String language = languageOf(plugin, sender);
        return component(plugin, language, key, placeholders, fallback);
    }

    public static Component component(JavaPlugin plugin, UUID playerId, String key, Map<String, String> placeholders, String fallback) {
        String language = LeDatLanguageRegistry.getOrDefault(playerId, defaultLanguage(plugin));
        return component(plugin, language, key, placeholders, fallback);
    }

    public static Component component(JavaPlugin plugin, String language, String key, Map<String, String> placeholders, String fallback) {
        String raw = raw(plugin, language, key, placeholders, fallback == null ? "<gray>" + key + "</gray>" : fallback);
        return LeDatText.noItalic(deserializeSmart(plugin, key, raw));
    }

    public static String raw(JavaPlugin plugin, CommandSender sender, String key, String fallback, Object... placeholderPairs) {
        return raw(plugin, languageOf(plugin, sender), key, placeholders(placeholderPairs), fallback);
    }

    public static String mini(JavaPlugin plugin, CommandSender sender, String key, Map<String, String> placeholders, String fallback) {
        return MINI.serialize(component(plugin, sender, key, placeholders, fallback));
    }

    public static String legacy(JavaPlugin plugin, CommandSender sender, String key, Map<String, String> placeholders, String fallback) {
        return LEGACY_SECTION.serialize(component(plugin, sender, key, placeholders, fallback));
    }

    public static List<Component> componentList(JavaPlugin plugin, CommandSender sender, String key, Map<String, String> placeholders) {
        String language = languageOf(plugin, sender);
        return componentList(plugin, language, key, placeholders, List.of());
    }

    public static List<Component> componentList(JavaPlugin plugin, CommandSender sender, String key, Map<String, String> placeholders, List<String> fallback) {
        String language = languageOf(plugin, sender);
        return componentList(plugin, language, key, placeholders, fallback);
    }

    public static List<Component> componentList(JavaPlugin plugin, CommandSender sender, String key, List<String> fallback, Object... placeholderPairs) {
        String language = languageOf(plugin, sender);
        return componentList(plugin, language, key, placeholders(placeholderPairs), fallback);
    }

    public static List<Component> componentList(JavaPlugin plugin, String language, String key, Map<String, String> placeholders, List<String> fallback) {
        List<String> rawLines = rawList(plugin, language, key, placeholders, fallback);
        if (rawLines.isEmpty()) {
            return Collections.emptyList();
        }
        List<Component> result = new ArrayList<>();
        for (String raw : rawLines) {
            result.add(LeDatText.noItalic(deserializeSmart(plugin, key, raw)));
        }
        return result;
    }

    public static List<String> legacyList(JavaPlugin plugin, CommandSender sender, String key, Map<String, String> placeholders) {
        List<String> result = new ArrayList<>();
        for (Component line : componentList(plugin, sender, key, placeholders)) {
            result.add(LEGACY_SECTION.serialize(line));
        }
        return result;
    }

    public static void send(JavaPlugin plugin, CommandSender sender, String key) {
        send(plugin, sender, key, Map.of(), null);
    }

    public static void send(JavaPlugin plugin, CommandSender sender, String key, Map<String, String> placeholders) {
        send(plugin, sender, key, placeholders, null);
    }

    public static void send(JavaPlugin plugin, CommandSender sender, String key, String fallback, Object... placeholderPairs) {
        send(plugin, sender, key, placeholders(placeholderPairs), fallback);
    }

    public static void send(JavaPlugin plugin, CommandSender sender, String key, Map<String, String> placeholders, String fallback) {
        if (sender != null) {
            sender.sendMessage(component(plugin, sender, key, placeholders, fallback));
        }
    }

    public static void sendList(JavaPlugin plugin, CommandSender sender, String key, List<String> fallback, Object... placeholderPairs) {
        if (sender == null) {
            return;
        }
        for (Component line : componentList(plugin, sender, key, fallback, placeholderPairs)) {
            sender.sendMessage(line);
        }
    }

    public static void broadcast(JavaPlugin plugin, Iterable<? extends Player> recipients, String key, Map<String, String> placeholders, String fallback) {
        if (recipients == null) {
            return;
        }
        for (Player player : recipients) {
            if (player != null && player.isOnline()) {
                player.sendMessage(component(plugin, player, key, placeholders, fallback));
            }
        }
    }

    public static String raw(JavaPlugin plugin, String language, String key, Map<String, String> placeholders, String fallback) {
        Bundle bundle = bundle(plugin);
        String normalized = LeDatLanguageCodes.normalizeSupported(language, bundle.defaultLanguage);
        FileConfiguration config = configFor(bundle, normalized);
        String raw = value(config, key);
        if (raw == null && !normalized.equals(bundle.defaultLanguage)) {
            raw = value(configFor(bundle, bundle.defaultLanguage), key);
        }
        if (raw == null) {
            logMissingKeyOnce(plugin, normalized, key);
            raw = fallback;
        }
        return applyPlaceholders(raw == null ? key : raw, withSystemPlaceholders(plugin, config, bundle, normalized, placeholders));
    }

    public static List<String> rawList(JavaPlugin plugin, String language, String key) {
        return rawList(plugin, language, key, Map.of(), List.of());
    }

    public static List<String> rawList(JavaPlugin plugin, String language, String key, Map<String, String> placeholders, List<String> fallback) {
        Bundle bundle = bundle(plugin);
        String normalized = LeDatLanguageCodes.normalizeSupported(language, bundle.defaultLanguage);
        FileConfiguration config = configFor(bundle, normalized);
        List<String> list = listValue(config, key);
        if (list.isEmpty() && !normalized.equals(bundle.defaultLanguage)) {
            list = listValue(configFor(bundle, bundle.defaultLanguage), key);
        }
        if (list.isEmpty()) {
            if (fallback == null || fallback.isEmpty()) {
                logMissingKeyOnce(plugin, normalized, key);
                return Collections.emptyList();
            }
            list = fallback;
        }
        Map<String, String> effective = withSystemPlaceholders(plugin, config, bundle, normalized, placeholders);
        List<String> output = new ArrayList<>();
        for (String line : list) {
            output.add(applyPlaceholders(line, effective));
        }
        return output;
    }

    public static boolean has(JavaPlugin plugin, String language, String key) {
        Bundle bundle = bundle(plugin);
        String normalized = LeDatLanguageCodes.normalizeSupported(language, bundle.defaultLanguage);
        FileConfiguration config = configFor(bundle, normalized);
        FileConfiguration fallback = configFor(bundle, bundle.defaultLanguage);
        return value(config, key) != null || !listValue(config, key).isEmpty()
                || value(fallback, key) != null || !listValue(fallback, key).isEmpty();
    }

    public static String defaultLanguage(JavaPlugin plugin) {
        if (plugin == null) {
            return LeDatLanguageCodes.DEFAULT;
        }
        String configured = plugin.getConfig().getString("ledat-suite.language.default",
                plugin.getConfig().getString("settings.default-language", LeDatLanguageCodes.DEFAULT));
        return LeDatLanguageCodes.normalizeSupported(configured, LeDatLanguageCodes.DEFAULT);
    }

    public static List<String> supportedLanguages(JavaPlugin plugin) {
        if (plugin == null) {
            return LeDatLanguageCodes.canonicalLanguages();
        }
        List<String> configured = plugin.getConfig().getStringList("ledat-suite.language.supported");
        if (configured.isEmpty()) {
            return LeDatLanguageCodes.canonicalLanguages();
        }
        List<String> result = new ArrayList<>();
        for (String language : configured) {
            String normalized = LeDatLanguageCodes.normalize(language);
            if (LeDatLanguageCodes.isSupported(normalized) && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result.isEmpty() ? LeDatLanguageCodes.canonicalLanguages() : result;
    }

    public static String escapePlayerInput(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return input
                .replace("\\", "\\\\")
                .replace("<", "\\<")
                .replace(">", "\\>")
                .replace("\u00A7", "")
                .replace("&", "＆");
    }

    public static String stripPlayerFormatting(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return input.replaceAll("(?i)\u00A7[0-9a-fk-orx]", "")
                .replaceAll("(?i)&[0-9a-fk-orx]", "")
                .replace("<", "")
                .replace(">", "");
    }

    public static String applyPlaceholders(String input, Map<String, String> placeholders) {
        if (input == null) {
            return "";
        }
        String output = input;
        Map<String, String> all = placeholders == null ? Map.of() : placeholders;
        for (Map.Entry<String, String> entry : all.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String safeValue = entry.getValue() == null ? "" : entry.getValue();
            output = output.replace("{" + entry.getKey() + "}", safeValue);
            output = output.replace("%" + entry.getKey() + "%", safeValue);
        }
        return output;
    }

    private static void migrateIfBundled(JavaPlugin plugin, String resourceName, int targetVersion) {
        try (InputStream input = plugin.getResource(resourceName)) {
            if (input != null) {
                LeDatConfigMigrator.ensureResource(plugin, resourceName, targetVersion);
            }
        } catch (IOException error) {
            plugin.getLogger().warning("Failed to inspect bundled language resource " + resourceName + ": " + error.getMessage());
        }
    }

    private static Bundle bundle(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return CACHE.compute(plugin.getName(), (name, existing) -> {
            if (existing != null && existing.plugin == plugin) {
                return existing;
            }
            return load(plugin);
        });
    }

    private static Bundle load(JavaPlugin plugin) {
        File langDir = new File(plugin.getDataFolder(), "languages");
        if (!langDir.exists() && !langDir.mkdirs()) {
            plugin.getLogger().warning("Could not create language folder: " + langDir.getPath());
        }

        List<String> supported = new ArrayList<>(supportedLanguages(plugin));
        String defaultLanguage = defaultLanguage(plugin);
        if (!supported.contains(defaultLanguage)) {
            supported.add(0, defaultLanguage);
        }

        Map<String, FileConfiguration> languages = new LinkedHashMap<>();
        for (String language : supported) {
            languages.put(language, loadLanguage(plugin, langDir, language));
        }
        if (languages.isEmpty()) {
            languages.put(LeDatLanguageCodes.DEFAULT, skeleton(plugin, LeDatLanguageCodes.DEFAULT));
            defaultLanguage = LeDatLanguageCodes.DEFAULT;
        }
        return new Bundle(plugin, defaultLanguage, languages);
    }

    private static FileConfiguration loadLanguage(JavaPlugin plugin, File langDir, String language) {
        File file = new File(langDir, language + ".yml");
        if (file.exists()) {
            return YamlConfiguration.loadConfiguration(file);
        }

        String legacy = LeDatLanguageCodes.legacyShort(language);
        File legacyFile = new File(langDir, legacy + ".yml");
        if (legacyFile.exists()) {
            return YamlConfiguration.loadConfiguration(legacyFile);
        }

        try (InputStream input = plugin.getResource("languages/" + language + ".yml")) {
            if (input != null) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
        } catch (IOException error) {
            plugin.getLogger().warning("Failed to load language " + language + ": " + error.getMessage());
        }
        return skeleton(plugin, language);
    }

    private static YamlConfiguration skeleton(JavaPlugin plugin, String language) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("config-version", LANGUAGE_CONFIG_VERSION);
        config.set("meta.plugin", plugin.getName());
        config.set("meta.language", language);
        config.set("meta.display-name", LeDatLanguageCodes.displayName(language));
        config.set("meta.format", "MiniMessage");
        config.set("style.prefix", "<#C797FF>" + plugin.getName() + " <dark_gray>» ");
        config.set("style.lore-bullet", " <dark_gray>○ <gray>");
        config.set("style.lore-action", " <#C797FF>Nhấn <white>để thao tác");
        config.set("style.no-italic", true);
        config.set("common.prefix", "<#C797FF>" + plugin.getName() + " <dark_gray>» ");
        config.set("common.no-permission", "{prefix}<red>Bạn không có quyền dùng lệnh này.");
        config.set("common.player-only", "{prefix}<red>Lệnh này chỉ dành cho người chơi.");
        config.set("common.reload", "{prefix}<green>Đã reload cấu hình và ngôn ngữ.");
        config.set("common.reload-failed", "{prefix}<red>Reload thất bại. Kiểm tra console để biết thêm chi tiết.");
        config.set("common.invalid-usage", "{prefix}<red>Sai cú pháp. Dùng <white>{usage}<red>.");
        config.set("common.invalid-number", "{prefix}<red>Số không hợp lệ.");
        config.set("common.player-not-found", "{prefix}<red>Không tìm thấy người chơi <white>{player}<red>.");
        config.set("common.error", "{prefix}<red>Có lỗi xảy ra. Vui lòng thử lại sau.");
        if (LeDatLanguageCodes.EN_US.equals(LeDatLanguageCodes.normalize(language))) {
            config.set("style.lore-action", " <#C797FF>Click <white>to use");
            config.set("common.no-permission", "{prefix}<red>You do not have permission to use this command.");
            config.set("common.player-only", "{prefix}<red>This command can only be used by players.");
            config.set("common.reload", "{prefix}<green>Configuration and language files reloaded.");
            config.set("common.reload-failed", "{prefix}<red>Reload failed. Check console for details.");
            config.set("common.invalid-usage", "{prefix}<red>Invalid usage. Use <white>{usage}<red>.");
            config.set("common.invalid-number", "{prefix}<red>Invalid number.");
            config.set("common.player-not-found", "{prefix}<red>Player <white>{player}<red> was not found.");
            config.set("common.error", "{prefix}<red>An error occurred. Please try again later.");
        }
        if (LeDatLanguageCodes.ZH_CN.equals(LeDatLanguageCodes.normalize(language))) {
            config.set("style.lore-action", " <#C797FF>点击 <white>进行操作");
            config.set("common.no-permission", "{prefix}<red>你没有权限使用此命令。");
            config.set("common.player-only", "{prefix}<red>该命令只能由玩家使用。");
            config.set("common.reload", "{prefix}<green>配置和语言文件已重新加载。");
            config.set("common.reload-failed", "{prefix}<red>重载失败，请查看控制台了解详情。");
            config.set("common.invalid-usage", "{prefix}<red>用法错误。请使用 <white>{usage}<red>。");
            config.set("common.invalid-number", "{prefix}<red>数字无效。");
            config.set("common.player-not-found", "{prefix}<red>未找到玩家 <white>{player}<red>。");
            config.set("common.error", "{prefix}<red>发生错误，请稍后再试。");
        }
        return config;
    }

    private static String languageOf(JavaPlugin plugin, CommandSender sender) {
        if (sender instanceof Player player) {
            return LeDatLanguageRegistry.getOrDefault(player.getUniqueId(), defaultLanguage(plugin));
        }
        return defaultLanguage(plugin);
    }

    private static FileConfiguration configFor(Bundle bundle, String language) {
        FileConfiguration config = bundle.languages.get(LeDatLanguageCodes.normalize(language));
        if (config != null) {
            return config;
        }
        config = bundle.languages.get(bundle.defaultLanguage);
        if (config != null) {
            return config;
        }
        return bundle.languages.values().stream().findFirst().orElseGet(() -> skeleton(bundle.plugin, LeDatLanguageCodes.DEFAULT));
    }

    private static String value(FileConfiguration config, String key) {
        if (config == null || key == null) {
            return null;
        }
        String direct = config.getString(key);
        if (direct != null) {
            return direct;
        }
        String message = config.getString("messages." + key);
        if (message != null) {
            return message;
        }
        return config.getString("common." + key);
    }

    private static List<String> listValue(FileConfiguration config, String key) {
        if (config == null || key == null) {
            return Collections.emptyList();
        }
        if (config.isList(key)) {
            return config.getStringList(key);
        }
        if (config.isList("messages." + key)) {
            return config.getStringList("messages." + key);
        }
        if (config.isList("common." + key)) {
            return config.getStringList("common." + key);
        }
        return Collections.emptyList();
    }

    private static Map<String, String> withSystemPlaceholders(JavaPlugin plugin, FileConfiguration config,
                                                              Bundle bundle, String normalized,
                                                              Map<String, String> placeholders) {
        Map<String, String> effective = new LinkedHashMap<>();
        if (placeholders != null) {
            effective.putAll(placeholders);
        }
        effective.putIfAbsent("plugin", plugin.getName());
        effective.putIfAbsent("language", LeDatLanguageCodes.displayName(normalized));
        effective.putIfAbsent("prefix", prefix(config, bundle, normalized, plugin));
        return effective;
    }

    private static String prefix(FileConfiguration config, Bundle bundle, String normalized, JavaPlugin plugin) {
        String prefix = config == null ? null : config.getString("common.prefix");
        if (prefix == null && config != null) {
            prefix = config.getString("style.prefix");
        }
        if (prefix == null && !normalized.equals(bundle.defaultLanguage)) {
            FileConfiguration fallback = configFor(bundle, bundle.defaultLanguage);
            prefix = fallback.getString("common.prefix");
            if (prefix == null) {
                prefix = fallback.getString("style.prefix");
            }
        }
        return prefix == null ? "<#C797FF>" + plugin.getName() + " <dark_gray>» " : prefix.replace("{plugin}", plugin.getName());
    }

    private static Component deserializeSmart(JavaPlugin plugin, String key, String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        try {
            if (raw.indexOf('\u00A7') >= 0) {
                return LEGACY_SECTION.deserialize(raw);
            }
            // Existing migrated plugins still contain many legacy ampersand strings in configs.
            // Treat them as legacy only when no MiniMessage-like tags are present.
            if (raw.indexOf('&') >= 0 && raw.indexOf('<') < 0) {
                return LEGACY_AMPERSAND.deserialize(raw);
            }
            return MINI.deserialize(raw);
        } catch (Throwable error) {
            logParseErrorOnce(plugin, key, error.getMessage());
            return LeDatText.noItalic(Component.text(stripPlayerFormatting(raw)));
        }
    }

    private static void logMissingKeyOnce(JavaPlugin plugin, String language, String key) {
        if (plugin == null || key == null || key.isBlank()) {
            return;
        }
        String id = plugin.getName() + ':' + language + ':' + key;
        if (MISSING_KEY_LOG.putIfAbsent(id, Boolean.TRUE) == null) {
            plugin.getLogger().warning("Missing language key '" + key + "' for " + language + "; using fallback.");
        }
    }

    private static void logParseErrorOnce(JavaPlugin plugin, String key, String message) {
        if (plugin == null) {
            return;
        }
        String id = plugin.getName() + ':' + key;
        if (PARSE_ERROR_LOG.putIfAbsent(id, Boolean.TRUE) == null) {
            plugin.getLogger().warning("Failed to parse language key '" + key + "' as MiniMessage/legacy text: " + message);
        }
    }

    private record Bundle(JavaPlugin plugin, String defaultLanguage, Map<String, FileConfiguration> languages) {
    }
}
