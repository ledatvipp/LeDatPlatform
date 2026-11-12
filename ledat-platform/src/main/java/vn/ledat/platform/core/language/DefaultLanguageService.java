package vn.ledat.platform.core.language;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.platform.api.language.LanguageService;
import vn.ledat.platform.api.scheduler.LeDatScheduler;
import vn.ledat.platform.api.scheduler.LeDatTask;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class DefaultLanguageService implements LanguageService, Listener {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pattern LANGUAGE_VERSION_FILE_PATTERN = Pattern.compile(".*(?:^|[-.])v\\d+(?:[-.]|$).*");
    private static final Pattern NON_KEY_CHARACTER_PATTERN = Pattern.compile("[^a-z0-9_]");

    private final Plugin platformPlugin;
    private final LeDatScheduler scheduler;
    private final Logger logger;
    private final Map<BundleKey, FileConfiguration> bundles = new ConcurrentHashMap<>();
    private final Map<BundleKey, String> prefixCache = new ConcurrentHashMap<>();
    private final Map<CandidateKey, List<String>> candidateKeyCache = new ConcurrentHashMap<>();
    private final Map<BundleKey, List<SearchTarget>> searchTargetCache = new ConcurrentHashMap<>();
    private final Map<String, String> pluginKeyCache = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> localesByPlugin = new ConcurrentHashMap<>();
    private final Set<String> registeredPlugins = ConcurrentHashMap.newKeySet();
    private final Set<String> versionWarnings = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> missingMessageWarnings = new ConcurrentHashMap<>();
    private final Map<String, Long> invalidMessageWarnings = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerLocales = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, BossBar>> activeBossBars = new ConcurrentHashMap<>();
    private final Set<LanguageChangeListener> localeListeners = ConcurrentHashMap.newKeySet();
    private volatile LeDatTask pendingPreferenceSave = LeDatTask.NOOP;
    private volatile String defaultLocale = "vi_VN";
    private volatile String fallbackLocale = "vi_VN";
    private volatile List<String> requiredLocales = List.of("vi_VN", "en_US", "zh_CN");

    public DefaultLanguageService(Plugin platformPlugin, LeDatScheduler scheduler) {
        this.platformPlugin = platformPlugin;
        this.scheduler = scheduler;
        this.logger = platformPlugin.getLogger();
        Bukkit.getPluginManager().registerEvents(this, platformPlugin);
        reload();
    }

    @Override
    public String defaultLocale() {
        return defaultLocale;
    }

    @Override
    public String fallbackLocale() {
        return fallbackLocale;
    }

    @Override
    public String resolveLocale(CommandSender sender) {
        if (sender instanceof Player player) {
            return resolveLocale(player.getUniqueId());
        }
        return defaultLocale;
    }

    @Override
    public String resolveLocale(UUID playerId) {
        if (playerId == null) {
            return defaultLocale;
        }
        return playerLocales.getOrDefault(playerId, defaultLocale);
    }

    @Override
    public void setLocale(Player player, String locale) {
        if (player == null) {
            return;
        }
        setLocale(player.getUniqueId(), locale, "api-player");
    }

    @Override
    public void setLocale(UUID playerId, String locale, String source) {
        if (playerId == null) {
            return;
        }
        String normalized = normalizeSupportedLocale(locale);
        String previous = playerLocales.put(playerId, normalized);
        if (!normalized.equals(previous)) {
            requestPreferenceSave();
            fireLocaleChange(new LanguageChange(playerId, previous == null ? defaultLocale : previous, normalized, safeSource(source), System.currentTimeMillis()));
        }
    }

    @Override
    public boolean setLocaleIfAbsent(UUID playerId, String locale, String source) {
        if (playerId == null) {
            return false;
        }
        String normalized = normalizeSupportedLocale(locale);
        String previous = playerLocales.putIfAbsent(playerId, normalized);
        if (previous == null) {
            requestPreferenceSave();
            fireLocaleChange(new LanguageChange(playerId, defaultLocale, normalized, safeSource(source), System.currentTimeMillis()));
            return true;
        }
        return false;
    }

    @Override
    public void clearLocale(Player player) {
        if (player != null) {
            clearLocale(player.getUniqueId());
        }
    }

    @Override
    public void clearLocale(UUID playerId) {
        if (playerId == null) {
            return;
        }
        String previous = playerLocales.remove(playerId);
        if (previous != null) {
            requestPreferenceSave();
            fireLocaleChange(new LanguageChange(playerId, previous, defaultLocale, "clear", System.currentTimeMillis()));
        }
    }

    @Override
    public List<String> requiredLocales() {
        return requiredLocales;
    }

    @Override
    public String displayName(String locale) {
        String normalized = LanguageService.normalize(locale);
        return switch (normalized) {
            case "vi_VN" -> "Ti\u1ebfng Vi\u1ec7t";
            case "en_US" -> "English";
            case "zh_CN" -> "\u7b80\u4f53\u4e2d\u6587";
            default -> normalized;
        };
    }

    @Override
    public AutoCloseable listenLocaleChanges(LanguageChangeListener listener) {
        if (listener == null) {
            return () -> { };
        }
        localeListeners.add(listener);
        return () -> localeListeners.remove(listener);
    }

    @Override
    public void registerPlugin(Plugin plugin) {
        Plugin owner = owner(plugin);
        String ownerKey = pluginKey(owner);
        if (!registeredPlugins.add(ownerKey)) {
            return;
        }
        Set<String> locales = detectLocales(owner);
        localesByPlugin.put(ownerKey, locales);
        for (String locale : locales) {
            load(owner, locale);
        }
        logger.info("Language bundle registered: " + owner.getName() + " locales=" + locales);
    }

    @Override
    public void unregisterPlugin(Plugin plugin) {
        Plugin owner = owner(plugin);
        String ownerKey = pluginKey(owner);
        registeredPlugins.remove(ownerKey);
        localesByPlugin.remove(ownerKey);
        bundles.keySet().removeIf(key -> key.pluginName().equals(ownerKey));
        prefixCache.keySet().removeIf(key -> key.pluginName().equals(ownerKey));
        candidateKeyCache.keySet().removeIf(key -> key.pluginName().equals(ownerKey));
        searchTargetCache.keySet().removeIf(key -> key.pluginName().equals(ownerKey));
        versionWarnings.removeIf(key -> key.startsWith(ownerKey + ":"));
        missingMessageWarnings.keySet().removeIf(key -> key.startsWith(ownerKey + ":"));
        invalidMessageWarnings.keySet().removeIf(key -> key.startsWith(ownerKey + ":"));
    }

    @Override
    public void reload(Plugin plugin) {
        Plugin owner = owner(plugin);
        String ownerKey = pluginKey(owner);
        bundles.keySet().removeIf(key -> key.pluginName().equals(ownerKey));
        prefixCache.keySet().removeIf(key -> key.pluginName().equals(ownerKey));
        candidateKeyCache.keySet().removeIf(key -> key.pluginName().equals(ownerKey));
        searchTargetCache.keySet().removeIf(key -> key.pluginName().equals(ownerKey));
        localesByPlugin.put(ownerKey, detectLocales(owner));
        registeredPlugins.remove(ownerKey);
        missingMessageWarnings.keySet().removeIf(key -> key.startsWith(ownerKey + ":"));
        invalidMessageWarnings.keySet().removeIf(key -> key.startsWith(ownerKey + ":"));
        registerPlugin(owner);
    }

    @Override
    public List<String> supportedLocales(Plugin plugin) {
        Plugin owner = owner(plugin);
        Set<String> locales = localesByPlugin.computeIfAbsent(pluginKey(owner), ignored -> detectLocales(owner));
        return List.copyOf(locales);
    }

    @Override
    public LanguageStats stats() {
        int bossBars = activeBossBars.values().stream().mapToInt(Map::size).sum();
        return new LanguageStats(
                defaultLocale,
                fallbackLocale,
                registeredPlugins.size(),
                bundles.size(),
                playerLocales.size(),
                activeBossBars.size(),
                bossBars,
                missingMessageWarnings.size(),
                invalidMessageWarnings.size(),
                playerLocales.size(),
                localeListeners.size(),
                List.copyOf(requiredLocales)
        );
    }

    @Override
    public File languageFolder(Plugin plugin) {
        return new File(owner(plugin).getDataFolder(), "languages");
    }

    @Override
    public Component component(Plugin plugin, String locale, String key, Map<String, String> placeholders) {
        return renderComponent(plugin, locale, key, placeholders, Set.of());
    }

    @Override
    public Component componentTrusted(Plugin plugin, String locale, String key, Map<String, String> placeholders, List<String> trustedPlaceholderKeys) {
        return renderComponent(plugin, locale, key, placeholders, trustedKeys(trustedPlaceholderKeys));
    }

    @Override
    public List<Component> componentList(Plugin plugin, String locale, String key, List<String> fallback, Map<String, String> placeholders) {
        return renderComponentList(plugin, locale, key, fallback, placeholders, Set.of());
    }

    @Override
    public List<Component> componentListTrusted(Plugin plugin, String locale, String key, List<String> fallback,
                                                Map<String, String> placeholders, List<String> trustedPlaceholderKeys) {
        return renderComponentList(plugin, locale, key, fallback, placeholders, trustedKeys(trustedPlaceholderKeys));
    }

    private List<Component> renderComponentList(Plugin plugin, String locale, String key, List<String> fallback,
                                                Map<String, String> placeholders, Set<String> trustedPlaceholderKeys) {
        Plugin owner = owner(plugin);
        registerPlugin(owner);
        String safeKey = key == null || key.isBlank() ? "missing-key" : key;
        String selectedLocale = chooseLocale(owner, locale);
        List<String> lines = rawStringList(owner, selectedLocale, safeKey);
        if (lines == null || lines.isEmpty()) {
            warnMissingMessage(owner, selectedLocale, safeKey);
            lines = fallback == null || fallback.isEmpty()
                    ? List.of("<red>Missing message list: <white>" + escape(safeKey))
                    : fallback;
        }
        List<Component> components = new ArrayList<>(lines.size());
        for (String line : lines) {
            components.add(deserialize(owner, selectedLocale, safeKey, apply(owner, selectedLocale, line, placeholders, trustedPlaceholderKeys)));
        }
        return List.copyOf(components);
    }

    @Override
    public String miniMessage(Plugin plugin, String locale, String key, Map<String, String> placeholders) {
        Plugin owner = owner(plugin);
        registerPlugin(owner);
        String safeKey = key == null || key.isBlank() ? "missing-key" : key;
        String selectedLocale = chooseLocale(owner, locale);
        String value = raw(owner, selectedLocale, safeKey);
        if (value == null) {
            warnMissingMessage(owner, selectedLocale, safeKey);
            return "<red>Missing message: <white>" + escape(safeKey);
        }
        return apply(owner, selectedLocale, value, placeholders, Set.of());
    }

    @Override
    public String plain(Plugin plugin, String locale, String key, Map<String, String> placeholders) {
        return PLAIN.serialize(component(plugin, locale, key, placeholders));
    }

    @Override
    public boolean has(Plugin plugin, String locale, String key) {
        Plugin owner = owner(plugin);
        registerPlugin(owner);
        String selectedLocale = chooseLocale(owner, locale);
        if (raw(owner, selectedLocale, key) != null) {
            return true;
        }
        List<String> list = rawStringList(owner, selectedLocale, key);
        return list != null && !list.isEmpty();
    }

    @Override
    public boolean hasList(Plugin plugin, String locale, String key) {
        Plugin owner = owner(plugin);
        registerPlugin(owner);
        String selectedLocale = chooseLocale(owner, locale);
        List<String> list = rawStringList(owner, selectedLocale, key);
        return list != null && !list.isEmpty();
    }

    @Override
    public void send(CommandSender sender, Plugin plugin, String key, Map<String, String> placeholders) {
        if (sender == null) {
            return;
        }
        Plugin owner = owner(plugin);
        Component message = component(owner, resolveLocale(sender), key, placeholders);
        runForSender(sender, () -> sender.sendMessage(message));
    }

    @Override
    public void sendTrusted(CommandSender sender, Plugin plugin, String key, Map<String, String> placeholders, List<String> trustedPlaceholderKeys) {
        if (sender == null) {
            return;
        }
        Plugin owner = owner(plugin);
        Component message = componentTrusted(owner, resolveLocale(sender), key, placeholders, trustedPlaceholderKeys);
        runForSender(sender, () -> sender.sendMessage(message));
    }

    @Override
    public void sendList(CommandSender sender, Plugin plugin, String key, List<String> fallback, Map<String, String> placeholders) {
        if (sender == null) {
            return;
        }
        Plugin owner = owner(plugin);
        List<Component> messages = componentList(owner, resolveLocale(sender), key, fallback, placeholders);
        runForSender(sender, () -> messages.forEach(sender::sendMessage));
    }

    @Override
    public void actionBar(Player player, Plugin plugin, String key, Map<String, String> placeholders) {
        if (player == null) {
            return;
        }
        Plugin owner = owner(plugin);
        Component message = component(owner, resolveLocale(player), key, placeholders);
        runForPlayer(player, () -> player.sendActionBar(message));
    }

    @Override
    public void actionBarTrusted(Player player, Plugin plugin, String key, Map<String, String> placeholders, List<String> trustedPlaceholderKeys) {
        if (player == null) {
            return;
        }
        Plugin owner = owner(plugin);
        Component message = componentTrusted(owner, resolveLocale(player), key, placeholders, trustedPlaceholderKeys);
        runForPlayer(player, () -> player.sendActionBar(message));
    }

    @Override
    public void title(Player player, Plugin plugin, String titleKey, String subtitleKey, Duration fadeIn, Duration stay, Duration fadeOut, Map<String, String> placeholders) {
        if (player == null) {
            return;
        }
        Plugin owner = owner(plugin);
        String locale = resolveLocale(player);
        Component title = component(owner, locale, titleKey, placeholders);
        Component subtitle = component(owner, locale, subtitleKey, placeholders);
        Duration safeFadeIn = fadeIn == null ? Duration.ofMillis(250) : fadeIn;
        Duration safeStay = stay == null ? Duration.ofSeconds(2) : stay;
        Duration safeFadeOut = fadeOut == null ? Duration.ofMillis(250) : fadeOut;
        runForPlayer(player, () -> player.showTitle(Title.title(title, subtitle, Title.Times.times(safeFadeIn, safeStay, safeFadeOut))));
    }

    @Override
    public void bossBar(Player player, Plugin plugin, String barId, String key, float progress, Duration duration, Map<String, String> placeholders) {
        if (player == null || barId == null || barId.isBlank()) {
            return;
        }
        Plugin owner = owner(plugin);
        String safeBarId = normalizeKey(barId);
        Component name = component(owner, resolveLocale(player), key, placeholders);
        BossBar bar = BossBar.bossBar(name, Math.max(0F, Math.min(1F, progress)), BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        runForPlayer(player, () -> {
            hideBossBarDirect(player, safeBarId);
            activeBossBars.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>()).put(safeBarId, bar);
            player.showBossBar(bar);
        });
        if (duration != null && !duration.isZero() && !duration.isNegative()) {
            long delayTicks = Math.max(1L, duration.toMillis() / 50L);
            scheduler.entityLater(platformPlugin, player, () -> {
                if (!player.isOnline()) {
                    cleanup(player.getUniqueId());
                    return;
                }
                hideBossBarDirect(player, safeBarId);
            }, delayTicks);
        }
    }

    @Override
    public void hideBossBar(Player player, String barId) {
        if (player == null || barId == null) {
            return;
        }
        String safeBarId = normalizeKey(barId);
        runForPlayer(player, () -> hideBossBarDirect(player, safeBarId));
    }


    @Override
    public void cleanup(UUID playerId) {
        if (playerId == null) {
            return;
        }
        activeBossBars.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer().getUniqueId());
    }

    public void shutdown() {
        LeDatTask pending = pendingPreferenceSave;
        if (pending != null) {
            pending.cancelQuietly();
        }
        pendingPreferenceSave = LeDatTask.NOOP;
        savePreferenceFile();
        localeListeners.clear();
        activeBossBars.clear();
    }

    @Override
    public void reload() {
        platformPlugin.reloadConfig();
        defaultLocale = LanguageService.normalize(platformPlugin.getConfig().getString("language.default", "vi_VN"));
        fallbackLocale = LanguageService.normalize(platformPlugin.getConfig().getString("language.fallback", defaultLocale));
        List<String> configured = platformPlugin.getConfig().getStringList("language.required-locales");
        if (configured.isEmpty()) {
            configured = platformPlugin.getConfig().getStringList("language.supported");
        }
        LinkedHashSet<String> required = new LinkedHashSet<>();
        required.add(defaultLocale);
        required.add(fallbackLocale);
        for (String locale : configured) {
            required.add(LanguageService.normalize(locale));
        }
        if (required.isEmpty()) {
            required.add("vi_VN");
        }
        requiredLocales = List.copyOf(required);
        bundles.clear();
        prefixCache.clear();
        candidateKeyCache.clear();
        searchTargetCache.clear();
        localesByPlugin.clear();
        registeredPlugins.clear();
        versionWarnings.clear();
        missingMessageWarnings.clear();
        invalidMessageWarnings.clear();
        registerPlugin(platformPlugin);
        loadPreferenceFile();
    }

    private File preferenceFile() {
        return new File(platformPlugin.getDataFolder(), "storage/language-preferences.yml");
    }

    private void loadPreferenceFile() {
        File file = preferenceFile();
        Map<UUID, String> loaded = new HashMap<>();
        if (file.exists()) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(file);
            if (config.isConfigurationSection("players")) {
                var section = config.getConfigurationSection("players");
                if (section != null) {
                    for (String key : section.getKeys(false)) {
                        try {
                            UUID playerId = UUID.fromString(key);
                            String locale = config.getString("players." + key + ".locale", config.getString("players." + key));
                            loaded.put(playerId, normalizeSupportedLocale(locale));
                        } catch (IllegalArgumentException ignored) {
                            // Ignore invalid manually edited UUID keys.
                        }
                    }
                }
            }
        }
        playerLocales.clear();
        playerLocales.putAll(loaded);
    }

    private void requestPreferenceSave() {
        LeDatTask previous = pendingPreferenceSave;
        if (previous != null) {
            previous.cancelQuietly();
        }
        pendingPreferenceSave = scheduler.asyncLater(platformPlugin, "language-preferences-save", this::savePreferenceFile, Duration.ofMillis(500));
    }

    private void savePreferenceFile() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("_meta.schema", "LeDatPlatformLanguagePreferences");
        config.set("_meta.schema-version", 1);
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, String> entry : playerLocales.entrySet()) {
            String path = "players." + entry.getKey();
            config.set(path + ".locale", normalizeSupportedLocale(entry.getValue()));
            config.set(path + ".updated-at", now);
        }
        File file = preferenceFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            logger.warning("Could not create language preference folder: " + parent.getAbsolutePath());
            return;
        }
        try {
            config.save(file);
        } catch (Exception error) {
            logger.warning("Could not save language preferences: " + error.getMessage());
        }
    }

    private void fireLocaleChange(LanguageChange change) {
        if (change == null || localeListeners.isEmpty()) {
            return;
        }
        for (LanguageChangeListener listener : localeListeners) {
            try {
                listener.onLanguageChange(change);
            } catch (Throwable error) {
                logger.log(Level.WARNING, "Language change listener failed", error);
            }
        }
    }

    private String normalizeSupportedLocale(String locale) {
        String normalized = LanguageService.normalize(locale == null ? defaultLocale : locale);
        if (requiredLocales.contains(normalized)) {
            return normalized;
        }
        if (requiredLocales.contains(defaultLocale)) {
            return defaultLocale;
        }
        if (requiredLocales.contains(fallbackLocale)) {
            return fallbackLocale;
        }
        return requiredLocales.stream().findFirst().orElse("vi_VN");
    }

    private String safeSource(String source) {
        return source == null || source.isBlank() ? "unknown" : source.trim();
    }

    private void runForSender(CommandSender sender, Runnable action) {
        if (!scheduler.isFolia() && scheduler.isPrimaryThread()) {
            action.run();
            return;
        }
        scheduler.sender(platformPlugin, sender, action);
    }

    private void runForPlayer(Player player, Runnable action) {
        if (!scheduler.isFolia() && scheduler.isPrimaryThread()) {
            action.run();
            return;
        }
        scheduler.entity(platformPlugin, player, action);
    }

    private void hideBossBarDirect(Player player, String barId) {
        Map<String, BossBar> bars = activeBossBars.get(player.getUniqueId());
        if (bars == null) {
            return;
        }
        BossBar bar = bars.remove(barId);
        if (bar != null) {
            player.hideBossBar(bar);
        }
        if (bars.isEmpty()) {
            activeBossBars.remove(player.getUniqueId());
        }
    }

    private Set<String> detectLocales(Plugin plugin) {
        LinkedHashSet<String> locales = new LinkedHashSet<>(requiredLocales);
        File folder = languageFolder(plugin);
        if (!folder.exists() && !folder.mkdirs()) {
            logger.warning("Could not create language folder for " + plugin.getName() + ": " + folder.getAbsolutePath());
        }
        if (platformPlugin.getConfig().getBoolean("language.plugin-bundles.save-missing-bundled", true)) {
            for (String locale : requiredLocales) {
                saveBundledLanguage(plugin, locale);
            }
        }
        File[] files = folder.listFiles((dir, name) -> isLanguageBundleFile(name));
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    locales.add(LanguageService.normalize(name.substring(0, dot)));
                }
            }
        }
        return locales;
    }

    private void saveBundledLanguage(Plugin plugin, String locale) {
        String normalized = LanguageService.normalize(locale);
        String path = "languages/" + normalized + ".yml";
        File file = new File(plugin.getDataFolder(), path);
        if (file.exists()) {
            return;
        }
        boolean bundled;
        try (InputStream stream = plugin.getResource(path)) {
            bundled = stream != null;
        } catch (Exception ignored) {
            bundled = false;
        }
        if (!bundled) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        if (!(plugin instanceof JavaPlugin javaPlugin)) {
            return;
        }
        try {
            javaPlugin.saveResource(path, false);
        } catch (Throwable ignored) {
            // Child plugins may not ship every locale. Fallback chain handles missing bundles.
        }
    }

    private FileConfiguration load(Plugin plugin, String locale) {
        Plugin owner = owner(plugin);
        String normalized = LanguageService.normalize(locale);
        BundleKey key = new BundleKey(pluginKey(owner), normalized);
        return bundles.computeIfAbsent(key, ignored -> loadBundle(owner, normalized));
    }

    private FileConfiguration loadBundle(Plugin plugin, String locale) {
        if (platformPlugin.getConfig().getBoolean("language.plugin-bundles.save-missing-bundled", true)) {
            saveBundledLanguage(plugin, locale);
        }
        File file = new File(plugin.getDataFolder(), "languages/" + locale + ".yml");
        FileConfiguration config = null;
        if (file.exists()) {
            config = YamlConfiguration.loadConfiguration(file);
            normalizeLanguageBundleFile(plugin, locale, file, config);
        } else {
            String path = "languages/" + locale + ".yml";
            try (InputStream stream = plugin.getResource(path)) {
                if (stream != null) {
                    try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                        config = YamlConfiguration.loadConfiguration(reader);
                    }
                }
            } catch (Exception error) {
                logger.warning("Could not load language bundle " + plugin.getName() + "/" + locale + ": " + error.getMessage());
            }
        }
        if (config == null) {
            if (!plugin.equals(platformPlugin)) {
                return load(platformPlugin, locale);
            }
            logger.warning("Missing language bundle: " + plugin.getName() + "/" + locale);
            config = new YamlConfiguration();
        }
        validateLanguageVersion(plugin, locale, config);
        return config;
    }

    private void normalizeLanguageBundleFile(Plugin plugin, String locale, File file, FileConfiguration config) {
        int expected = expectedLanguageVersion(plugin, locale);
        if (expected <= 0 || config == null || file == null) {
            return;
        }
        int actual = config.getInt("language-version", -1);
        boolean generatedDefault = matchesBundledLanguage(plugin, locale, file);
        boolean changed = false;
        if (config.contains("config-version")) {
            config.set("config-version", null);
            changed = true;
        }
        if (config.contains("auto")) {
            config.set("auto", null);
            changed = true;
        }
        if (actual < expected) {
            mergeBundledLanguageDefaults(plugin, locale, config);
            config.set("language-version", expected);
            changed = true;
        }
        if (!changed) {
            return;
        }
        try {
            if (!generatedDefault) {
                backupLanguageBundle(file, actual);
            }
            config.save(file);
            rewriteLanguageVersionHeader(file, config.getInt("language-version", expected));
            logger.info("Migrated language bundle " + plugin.getName() + "/" + locale
                    + " from " + (actual < 0 ? "missing" : actual) + " to " + config.getInt("language-version", expected));
        } catch (Exception error) {
            logger.warning("Could not migrate language bundle " + plugin.getName() + "/" + locale + ": " + error.getMessage());
        }
    }

    private void mergeBundledLanguageDefaults(Plugin plugin, String locale, FileConfiguration config) {
        String path = "languages/" + LanguageService.normalize(locale) + ".yml";
        try (InputStream stream = plugin.getResource(path)) {
            if (stream == null) {
                return;
            }
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                FileConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
                for (String key : defaults.getKeys(true)) {
                    if (defaults.isConfigurationSection(key) || "config-version".equals(key) || "auto".equals(key)) {
                        continue;
                    }
                    if (!config.contains(key)) {
                        config.set(key, defaults.get(key));
                    }
                }
            }
        } catch (Exception error) {
            logger.warning("Could not merge bundled language defaults for " + plugin.getName() + "/" + locale + ": " + error.getMessage());
        }
    }

    private void backupLanguageBundle(File file, int currentVersion) throws IOException {
        if (!file.exists()) {
            return;
        }
        File parent = file.getParentFile();
        File backup = new File(parent, file.getName() + ".language-v" + Math.max(0, currentVersion) + "." + System.currentTimeMillis() + ".bak");
        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
    }

    private boolean matchesBundledLanguage(Plugin plugin, String locale, File file) {
        String path = "languages/" + LanguageService.normalize(locale) + ".yml";
        if (plugin == null || file == null || !file.isFile()) {
            return false;
        }
        try (InputStream stream = plugin.getResource(path)) {
            return stream != null && java.util.Arrays.equals(Files.readAllBytes(file.toPath()), stream.readAllBytes());
        } catch (IOException error) {
            return false;
        }
    }

    private boolean isLanguageBundleFile(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".yml") && !lower.endsWith(".yaml")) {
            return false;
        }
        String base = lower.substring(0, lower.endsWith(".yaml") ? lower.length() - 5 : lower.length() - 4);
        return !base.contains("language-v")
                && !LANGUAGE_VERSION_FILE_PATTERN.matcher(base).matches()
                && !base.endsWith(".bak");
    }

    private void rewriteLanguageVersionHeader(File file, int version) throws IOException {
        String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        text = text.replaceFirst("(?m)^language-version:\\s*\\d+\\s*\\R?", "");
        Files.writeString(file.toPath(), "language-version: " + version + System.lineSeparator() + text, StandardCharsets.UTF_8);
    }

    private int expectedLanguageVersion(Plugin plugin, String locale) {
        int coreVersion = platformPlugin.getConfig().getInt("language.version.current", 5);
        if (coreVersion <= 0) {
            return coreVersion;
        }
        String path = "languages/" + LanguageService.normalize(locale) + ".yml";
        try (InputStream stream = plugin.getResource(path)) {
            int bundled = bundledLanguageVersion(stream, coreVersion);
            return plugin.equals(platformPlugin) ? Math.max(coreVersion, bundled) : bundled;
        } catch (Exception error) {
            logger.warning("Could not read bundled language version for " + plugin.getName() + "/" + locale
                    + ": " + error.getMessage());
            return coreVersion;
        }
    }

    static int bundledLanguageVersion(InputStream stream, int coreVersion)
            throws IOException, org.bukkit.configuration.InvalidConfigurationException {
        if (stream == null || coreVersion <= 0) {
            return coreVersion;
        }
        YamlConfiguration bundled = new YamlConfiguration();
        bundled.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        int version = bundled.getInt("language-version", coreVersion);
        return version > 0 ? version : coreVersion;
    }

    private void validateLanguageVersion(Plugin plugin, String locale, FileConfiguration config) {
        int expected = expectedLanguageVersion(plugin, locale);
        int actual = config.getInt("language-version", -1);
        if (actual == expected || expected <= 0) {
            return;
        }
        String warningKey = pluginKey(plugin) + ":" + locale;
        if (versionWarnings.add(warningKey)) {
            if (actual < 0) {
                logger.warning("Language bundle " + plugin.getName() + "/" + locale + " missing language-version. Expected " + expected + ".");
            } else if (actual < expected) {
                logger.warning("Language bundle " + plugin.getName() + "/" + locale + " is outdated: " + actual + " < " + expected + ".");
            } else {
                logger.warning("Language bundle " + plugin.getName() + "/" + locale + " is newer than expected version: " + actual + " > " + expected + ".");
            }
        }
    }

    private String chooseLocale(Plugin plugin, String requested) {
        String normalized = LanguageService.normalize(requested == null ? defaultLocale : requested);
        Set<String> locales = localesByPlugin.computeIfAbsent(pluginKey(plugin), ignored -> detectLocales(plugin));
        if (locales.contains(normalized)) {
            return normalized;
        }
        if (locales.contains(defaultLocale)) {
            return defaultLocale;
        }
        if (locales.contains(fallbackLocale)) {
            return fallbackLocale;
        }
        return locales.stream().findFirst().orElse(fallbackLocale);
    }

    private String raw(Plugin plugin, String locale, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        for (SearchTarget target : searchTargets(owner(plugin), locale)) {
            FileConfiguration config = load(target.plugin(), target.locale());
            for (String candidate : candidateKeys(target.plugin(), key)) {
                String direct = config.getString(candidate, null);
                if (direct != null) {
                    return direct;
                }
            }
        }
        return null;
    }

    private List<String> rawStringList(Plugin plugin, String locale, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        for (SearchTarget target : searchTargets(owner(plugin), locale)) {
            FileConfiguration config = load(target.plugin(), target.locale());
            for (String candidate : candidateKeys(target.plugin(), key)) {
                if (config.isList(candidate)) {
                    return config.getStringList(candidate);
                }
                String single = config.getString(candidate, null);
                if (single != null) {
                    return List.of(single);
                }
            }
        }
        return null;
    }

    private List<SearchTarget> searchTargets(Plugin plugin, String locale) {
        Plugin owner = owner(plugin);
        String normalized = LanguageService.normalize(locale == null ? defaultLocale : locale);
        // Message sends resolve targets on every call; the result only depends on
        // (owner, locale, default/fallback locale), so cache it. Invalidated on
        // reload/unregister together with the bundle caches.
        return searchTargetCache.computeIfAbsent(new BundleKey(pluginKey(owner), normalized),
                ignored -> buildSearchTargets(owner, normalized));
    }

    private List<SearchTarget> buildSearchTargets(Plugin owner, String normalized) {
        List<SearchTarget> targets = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addSearchTarget(targets, seen, owner, normalized);
        if (!normalized.equals(defaultLocale)) {
            addSearchTarget(targets, seen, owner, defaultLocale);
        }
        if (!normalized.equals(fallbackLocale) && !defaultLocale.equals(fallbackLocale)) {
            addSearchTarget(targets, seen, owner, fallbackLocale);
        }
        if (!owner.equals(platformPlugin)) {
            addSearchTarget(targets, seen, platformPlugin, normalized);
            if (!normalized.equals(defaultLocale)) {
                addSearchTarget(targets, seen, platformPlugin, defaultLocale);
            }
            if (!normalized.equals(fallbackLocale) && !defaultLocale.equals(fallbackLocale)) {
                addSearchTarget(targets, seen, platformPlugin, fallbackLocale);
            }
        }
        return List.copyOf(targets);
    }

    private void addSearchTarget(List<SearchTarget> targets, Set<String> seen, Plugin plugin, String locale) {
        String normalized = LanguageService.normalize(locale == null ? fallbackLocale : locale);
        String key = pluginKey(plugin) + ":" + normalized;
        if (seen.add(key)) {
            targets.add(new SearchTarget(plugin, normalized));
        }
    }

    private List<String> candidateKeys(Plugin plugin, String key) {
        String safeKey = key == null ? "" : key.trim();
        String namespace = pluginKey(plugin);
        CandidateKey cacheKey = new CandidateKey(namespace, safeKey);
        return candidateKeyCache.computeIfAbsent(cacheKey, ignored -> {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            keys.add(namespace + "." + safeKey);
            keys.add(namespace + ".messages." + safeKey);
            keys.add(namespace + ".messages.messages." + safeKey);
            keys.add(safeKey);
            keys.add("messages." + safeKey);
            keys.add("messages.messages." + safeKey);
            keys.add("common." + safeKey);
            keys.add("style." + safeKey);
            return List.copyOf(keys);
        });
    }

    private Component renderComponent(Plugin plugin, String locale, String key, Map<String, String> placeholders, Set<String> trustedPlaceholderKeys) {
        Plugin owner = owner(plugin);
        registerPlugin(owner);
        String safeKey = key == null || key.isBlank() ? "missing-key" : key;
        String selectedLocale = chooseLocale(owner, locale);
        String value = raw(owner, selectedLocale, safeKey);
        if (value == null) {
            warnMissingMessage(owner, selectedLocale, safeKey);
            value = "<red>Missing message: <white>" + escape(safeKey);
        }
        return deserialize(owner, selectedLocale, safeKey, apply(owner, selectedLocale, value, placeholders, trustedPlaceholderKeys));
    }

    private Component deserialize(Plugin owner, String locale, String key, String applied) {
        try {
            return MINI.deserialize(applied, ITEM_TAG_RESOLVER).decoration(TextDecoration.ITALIC, false);
        } catch (Throwable error) {
            warnInvalidMessage(owner, locale, key, error);
            return Component.text(PLAIN.serialize(MINI.deserialize(MINI.escapeTags(applied)))).decoration(TextDecoration.ITALIC, false);
        }
    }

    private static final TagResolver ITEM_TAG_RESOLVER = TagResolver.resolver("item", (args, ctx) -> {
        String mat = args.popOr("chest").value();
        return Tag.inserting(renderItemSprite(mat));
    });

    private static final java.lang.reflect.Method COMPONENT_OBJECT_METHOD;
    private static final java.lang.reflect.Method SPRITE_ATLAS_METHOD;
    private static final boolean OBJECT_SPRITE_AVAILABLE;

    static {
        java.lang.reflect.Method compObj = null;
        java.lang.reflect.Method spriteAtlas = null;
        boolean ok = false;
        try {
            Class<?> objectContents = Class.forName("net.kyori.adventure.text.object.ObjectContents");
            compObj = Component.class.getMethod("object", objectContents);
            spriteAtlas = objectContents.getMethod("sprite", Key.class, Key.class);
            ok = true;
        } catch (Throwable ignored) {
        }
        COMPONENT_OBJECT_METHOD = compObj;
        SPRITE_ATLAS_METHOD = spriteAtlas;
        OBJECT_SPRITE_AVAILABLE = ok;
    }

    private static final Map<String, String> ITEM_SPRITE_ALIASES = Map.ofEntries(
            Map.entry("chest", "chest_minecart"),
            Map.entry("trapped_chest", "chest_minecart"),
            Map.entry("ender_chest", "ender_eye"),
            Map.entry("copper_chest", "chest_minecart"),
            Map.entry("exposed_copper_chest", "chest_minecart"),
            Map.entry("weathered_copper_chest", "chest_minecart"),
            Map.entry("oxidized_copper_chest", "chest_minecart"),
            Map.entry("key", "trial_key"),
            Map.entry("crate_key", "trial_key"),
            Map.entry("compass", "compass_00"),
            Map.entry("clock", "clock_00"),
            Map.entry("recovery_compass", "recovery_compass_00"),
            Map.entry("crossbow", "crossbow_standby"),
            Map.entry("enchanted_golden_apple", "golden_apple"),
            Map.entry("tipped_arrow", "arrow"),
            Map.entry("shield", "iron_chestplate"),
            Map.entry("debug_stick", "stick"),
            Map.entry("decorated_pot", "brick"),
            Map.entry("player_head", "totem_of_undying"),
            Map.entry("skeleton_skull", "bone"),
            Map.entry("wither_skeleton_skull", "coal"),
            Map.entry("zombie_head", "rotten_flesh"),
            Map.entry("creeper_head", "gunpowder"),
            Map.entry("piglin_head", "gold_ingot"),
            Map.entry("dragon_head", "dragon_breath"),
            Map.entry("dried_ghast", "ghast_tear"),
            Map.entry("chain", "iron_chain")
    );

    private static final Map<String, String> BLOCK_SPRITE_ALIASES = Map.ofEntries(
            Map.entry("vault", "vault_front_on"),
            Map.entry("trial_spawner", "trial_spawner_side_active"),
            Map.entry("cartography_table", "cartography_table_side3"),
            Map.entry("sticky_piston", "piston_top_sticky"),
            Map.entry("crafter", "crafter_north"),
            Map.entry("chiseled_bookshelf", "chiseled_bookshelf_empty"),
            Map.entry("calibrated_sculk_sensor", "calibrated_sculk_sensor_input_side"),
            Map.entry("dried_kelp_block", "dried_kelp_side"),
            Map.entry("magma_block", "magma"),
            Map.entry("snow_block", "snow"),
            Map.entry("moss_carpet", "moss_block"),
            Map.entry("pale_moss_carpet", "pale_moss_block"),
            Map.entry("heavy_weighted_pressure_plate", "iron_block"),
            Map.entry("light_weighted_pressure_plate", "gold_block"),
            Map.entry("smooth_quartz", "quartz_block_bottom"),
            Map.entry("smooth_sandstone", "sandstone_top"),
            Map.entry("smooth_red_sandstone", "red_sandstone_top"),
            Map.entry("suspicious_sand", "suspicious_sand_0"),
            Map.entry("suspicious_gravel", "suspicious_gravel_0"),
            Map.entry("petrified_oak_slab", "oak_planks"),
            Map.entry("copper_golem_statue", "copper_block"),
            Map.entry("exposed_copper_golem_statue", "exposed_copper"),
            Map.entry("weathered_copper_golem_statue", "weathered_copper"),
            Map.entry("oxidized_copper_golem_statue", "oxidized_copper"),
            Map.entry("test_block", "command_block_front")
    );

    private static final Set<String> BLOCKS_IN_ITEM_ATLAS = Set.of(
            "bamboo", "barrier", "bell", "brewing_stand", "cake", "candle", "cauldron", "comparator",
            "firefly_bush", "flower_pot", "glow_item_frame", "hopper", "item_frame", "kelp", "lantern",
            "leaf_litter", "light", "mangrove_propagule", "nether_sprouts", "nether_wart", "pink_petals",
            "pitcher_plant", "pointed_dripstone", "repeater", "resin_clump", "sea_pickle", "seagrass",
            "sniffer_egg", "soul_lantern", "structure_void", "sugar_cane", "turtle_egg", "wheat", "wildflowers"
    );

    private static final Set<String> BLOCK_FRONT_NAMES = Set.of(
            "bee_nest", "beehive", "blast_furnace", "chain_command_block", "command_block", "crafting_table",
            "dispenser", "dropper", "fletching_table", "furnace", "lectern", "loom", "observer",
            "repeating_command_block", "smithing_table", "smoker", "sunflower"
    );

    private static final Set<String> BLOCK_SIDE_NAMES = Set.of(
            "ancient_debris", "azalea", "barrel", "basalt", "big_dripleaf", "bone_block", "cactus",
            "composter", "daylight_detector", "dirt_path", "end_portal_frame", "flowering_azalea",
            "grass_block", "grindstone", "hay_block", "honey_block", "jigsaw", "lodestone", "mangrove_roots",
            "melon", "muddy_mangrove_roots", "mycelium", "ochre_froglight", "pearlescent_froglight",
            "piston", "podzol", "polished_basalt", "pumpkin", "quartz_block", "reinforced_deepslate",
            "scaffolding", "sculk_catalyst", "sculk_sensor", "sculk_shrieker", "small_dripleaf",
            "smooth_stone_slab", "stonecutter", "target", "tnt", "verdant_froglight"
    );

    private static final Set<String> BLOCK_TOP_NAMES = Set.of(
            "chipped_anvil", "damaged_anvil", "enchanting_table", "jukebox", "large_fern", "lilac",
            "peony", "respawn_anchor", "rose_bush", "tall_grass"
    );

    private static Component renderItemSprite(String materialName) {
        if (materialName == null || materialName.isBlank()) {
            return Component.text("▣", TextColor.color(0x55FFFF)).decoration(TextDecoration.ITALIC, false);
        }
        if (OBJECT_SPRITE_AVAILABLE) {
            try {
                String[] resolved = resolveSpriteAtlasAndPath(materialName);
                Key atlasKey = Key.key("minecraft", resolved[0]);
                Key spriteKey = Key.key("minecraft", resolved[1]);
                Object contents = SPRITE_ATLAS_METHOD.invoke(null, atlasKey, spriteKey);
                Object comp = COMPONENT_OBJECT_METHOD.invoke(null, contents);
                if (comp instanceof Component c) {
                    return Component.text("").append(c).decoration(TextDecoration.ITALIC, false);
                }
            } catch (Throwable ignored) {
            }
        }
        return Component.text("▣", TextColor.color(0x55FFFF)).decoration(TextDecoration.ITALIC, false);
    }

    private static String[] resolveSpriteAtlasAndPath(String materialName) {
        String r = materialName.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (r.startsWith("minecraft:")) r = r.substring(10);
        if (r.startsWith("waxed_")) r = r.substring(6);
        if (r.startsWith("infested_")) r = r.substring(9);

        String itemAlias = ITEM_SPRITE_ALIASES.get(r);
        if (itemAlias != null) return new String[]{"items", "item/" + itemAlias};

        String blockAlias = BLOCK_SPRITE_ALIASES.get(r);
        if (blockAlias != null) return new String[]{"blocks", "block/" + blockAlias};

        if (r.endsWith("_stained_glass_pane")) {
            return new String[]{"blocks", "block/" + r.substring(0, r.length() - 5)};
        }
        if (r.equals("glass_pane")) return new String[]{"blocks", "block/glass"};
        if (r.endsWith("_carpet")) {
            return new String[]{"blocks", "block/" + r.substring(0, r.length() - 7) + "_wool"};
        }
        if (r.endsWith("_wood")) {
            return new String[]{"blocks", "block/" + r.substring(0, r.length() - 5) + "_log"};
        }
        if (r.endsWith("_hyphae")) {
            return new String[]{"blocks", "block/" + r.substring(0, r.length() - 7) + "_stem"};
        }
        if (r.endsWith("_banner") || r.endsWith("_bed")) {
            int idx = r.lastIndexOf('_');
            if (idx > 0) return new String[]{"blocks", "block/" + r.substring(0, idx) + "_wool"};
        }
        if (BLOCK_FRONT_NAMES.contains(r)) return new String[]{"blocks", "block/" + r + "_front"};
        if (BLOCK_SIDE_NAMES.contains(r)) return new String[]{"blocks", "block/" + r + "_side"};
        if (BLOCK_TOP_NAMES.contains(r)) return new String[]{"blocks", "block/" + r + "_top"};

        for (String suf : new String[]{"_fence_gate", "_pressure_plate", "_button", "_stairs", "_slab", "_fence", "_wall"}) {
            if (r.endsWith(suf)) {
                String base = r.substring(0, r.length() - suf.length());
                return switch (base) {
                    case "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "bamboo", "crimson", "warped" ->
                            new String[]{"blocks", "block/" + base + "_planks"};
                    case "brick", "stone_brick", "mossy_stone_brick", "deepslate_brick", "deepslate_tile", "tuff_brick", "mud_brick", "nether_brick", "red_nether_brick", "end_stone_brick", "prismarine_brick", "polished_blackstone_brick", "resin_brick" ->
                            new String[]{"blocks", "block/" + base + "s"};
                    case "purpur" -> new String[]{"blocks", "block/purpur_block"};
                    case "quartz", "quartz_block" -> new String[]{"blocks", "block/quartz_block_side"};
                    case "bamboo_mosaic" -> new String[]{"blocks", "block/bamboo_mosaic"};
                    case "sandstone", "red_sandstone", "cut_sandstone", "cut_red_sandstone", "smooth_sandstone", "smooth_red_sandstone", "smooth_quartz" ->
                            resolveSpriteAtlasAndPath(base);
                    default -> new String[]{"blocks", "block/" + base};
                };
            }
        }

        Material matched = Material.matchMaterial(r.toUpperCase(Locale.ROOT));
        boolean isBlock = matched != null && matched.isBlock();
        if (isBlock) {
            if (BLOCKS_IN_ITEM_ATLAS.contains(r)
                    || r.endsWith("_door")
                    || r.endsWith("_sign")
                    || r.endsWith("_candle")
                    || r.endsWith("_chain")
                    || r.endsWith("copper_lantern")
                    || r.endsWith("campfire")) {
                return new String[]{"items", "item/" + r};
            }
            return new String[]{"blocks", "block/" + r};
        }
        return new String[]{"items", "item/" + r};
    }

    private String apply(Plugin plugin, String locale, String input, Map<String, String> placeholders, Set<String> trustedPlaceholderKeys) {
        String result = input == null ? "" : input;
        if (result.isEmpty()) {
            return result;
        }
        Plugin owner = owner(plugin);
        result = replacePlaceholder(result, "prefix", prefix(owner, locale));
        result = replacePlaceholder(result, "plugin", owner.getName());
        if (placeholders == null || placeholders.isEmpty()) {
            return result;
        }
        Set<String> trusted = trustedPlaceholderKeys == null ? Set.of() : trustedPlaceholderKeys;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String normalized = normalizeKey(entry.getKey());
            if (normalized.isBlank()) {
                continue;
            }
            boolean keepTags = trusted.contains(normalized);
            String replacement = keepTags ? String.valueOf(entry.getValue() == null ? "" : entry.getValue()) : escape(entry.getValue());
            result = replacePlaceholder(result, normalized, replacement);
        }
        return result;
    }

    private String prefix(Plugin plugin, String locale) {
        Plugin owner = owner(plugin);
        String normalized = LanguageService.normalize(locale == null ? defaultLocale : locale);
        return prefixCache.computeIfAbsent(new BundleKey(pluginKey(owner), normalized), ignored -> resolvePrefix(owner, normalized));
    }

    private String resolvePrefix(Plugin plugin, String locale) {
        String prefix = raw(plugin, locale, "prefix");
        if (prefix == null) {
            prefix = raw(plugin, locale, "common.prefix");
        }
        if (prefix == null) {
            prefix = raw(plugin, locale, "style.prefix");
        }
        if (prefix == null) {
            prefix = raw(platformPlugin, locale, "prefix");
        }
        return prefix == null ? "" : prefix;
    }

    private String replacePlaceholder(String input, String key, String value) {
        if (input == null || input.isEmpty() || key == null || key.isBlank()) {
            return input == null ? "" : input;
        }
        String replacement = value == null ? "" : value;
        String result = input;
        String brace = "{" + key + "}";
        if (result.contains(brace)) {
            result = result.replace(brace, replacement);
        }
        String tag = "<" + key + ">";
        if (result.contains(tag)) {
            result = result.replace(tag, replacement);
        }
        String percent = "%" + key + "%";
        if (result.contains(percent)) {
            result = result.replace(percent, replacement);
        }
        return result;
    }

    private void warnMissingMessage(Plugin owner, String locale, String key) {
        long interval = Math.max(0L, platformPlugin.getConfig().getLong("language.missing-message-log-interval-ms", 60000L));
        String warningKey = pluginKey(owner) + ":" + LanguageService.normalize(locale) + ":" + normalizeKey(key);
        if (shouldEmit(missingMessageWarnings, warningKey, interval)) {
            logger.warning("Missing language message: " + owner.getName() + "/" + LanguageService.normalize(locale) + " key " + key);
        }
    }

    private void warnInvalidMessage(Plugin owner, String locale, String key, Throwable error) {
        long interval = Math.max(0L, platformPlugin.getConfig().getLong("language.invalid-message-log-interval-ms", 60000L));
        String warningKey = pluginKey(owner) + ":" + LanguageService.normalize(locale) + ":" + normalizeKey(key);
        if (shouldEmit(invalidMessageWarnings, warningKey, interval)) {
            logger.log(Level.WARNING, "Invalid MiniMessage at " + owner.getName() + "/" + LanguageService.normalize(locale) + " key " + key + ": " + (error == null ? "unknown" : error.getMessage()));
        }
    }

    private boolean shouldEmit(Map<String, Long> guard, String key, long intervalMillis) {
        if (intervalMillis <= 0L) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long previous = guard.putIfAbsent(key, now);
        if (previous == null) {
            return true;
        }
        if (now - previous >= intervalMillis) {
            guard.put(key, now);
            return true;
        }
        return false;
    }

    private Set<String> trustedKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> trusted = new LinkedHashSet<>();
        for (String key : keys) {
            String normalized = normalizeKey(key);
            if (!normalized.isBlank()) {
                trusted.add(normalized);
            }
        }
        return Collections.unmodifiableSet(trusted);
    }

    private Plugin owner(Plugin plugin) {
        return plugin == null ? platformPlugin : plugin;
    }

    private String pluginKey(Plugin plugin) {
        return pluginKeyCache.computeIfAbsent(owner(plugin).getName(),
                name -> name.toLowerCase(Locale.ROOT).replace('-', '_'));
    }

    private String escape(Object value) {
        return MINI.escapeTags(String.valueOf(value == null ? "" : value));
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace('-', '_').toLowerCase(Locale.ROOT);
        return NON_KEY_CHARACTER_PATTERN.matcher(normalized).replaceAll("_");
    }

    private record BundleKey(String pluginName, String locale) {
    }

    private record CandidateKey(String pluginName, String key) {
    }

    private record SearchTarget(Plugin plugin, String locale) {
    }
}
