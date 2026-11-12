package vn.ledat.platform.core.config;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.platform.api.config.ConfigService;
import vn.ledat.platform.api.config.ConfigValidationReport;
import vn.ledat.platform.api.database.StorageMode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

public final class DefaultConfigService implements ConfigService {
    private final Plugin platformPlugin;
    private final Logger logger;

    public DefaultConfigService(Plugin platformPlugin) {
        this.platformPlugin = platformPlugin;
        this.logger = platformPlugin.getLogger();
    }

    @Override
    public ConfigValidationReport validate(Plugin plugin, FileConfiguration config, List<ConfigRule> rules) {
        ConfigValidationReport report = new ConfigValidationReport();
        if (config == null) {
            report.error(plugin.getName() + ": config is null");
            return report;
        }
        for (ConfigRule rule : rules) {
            validateRule(plugin, config, rule, report);
        }
        return report;
    }

    @Override
    public ConfigValidationReport validatePlatformConfig() {
        return validate(platformPlugin, platformPlugin.getConfig(), List.of(
                ConfigRule.of("config-version", ConfigValueType.INT, true),
                ConfigRule.of("production-profile.enabled", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("production-profile.warning-interval-ms", ConfigValueType.LONG, false),
                ConfigRule.of("production-profile.database.reject-main-thread-borrows", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("production-profile.command.slow-handler-warn-ms", ConfigValueType.LONG, false),
                ConfigRule.of("production-profile.executors.default-queue-capacity", ConfigValueType.INT, false),
                ConfigRule.of("production-profile.executors.scheduled-queue-capacity", ConfigValueType.INT, false),
                ConfigRule.of("production-profile.executors.warn-queue-size", ConfigValueType.INT, false),
                ConfigRule.of("production-profile.executors.allow-core-timeout", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("production-profile.executors.shutdown-timeout-seconds", ConfigValueType.LONG, false),
                ConfigRule.of("production-profile.executors.shutdown-timeout-ms", ConfigValueType.LONG, false),
                ConfigRule.of("production-profile.executors.thread-scan-cache-ms", ConfigValueType.LONG, false),
                ConfigRule.of("production-profile.executors.warn-unregistered-ledat-threads", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("production-profile.scheduler.warn-disabled-owner-tasks", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("owner-lifecycle.enabled", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("owner-lifecycle.cleanup-on-plugin-disable", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("owner-lifecycle.refresh-hooks-on-plugin-enable", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("owner-lifecycle.log-cleanup-summary", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("owner-lifecycle.log-stateless-cleanup", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("owner-lifecycle.skip-stateless-plugin-disable", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("owner-lifecycle.recent-limit", ConfigValueType.INT, false),
                ConfigRule.of("owner-lifecycle.user-cache-flush-timeout-seconds", ConfigValueType.LONG, false),
                ConfigRule.of("owner-lifecycle.total-timeout-ms", ConfigValueType.LONG, false),
                ConfigRule.of("language.default", ConfigValueType.STRING, true),
                ConfigRule.of("language.fallback", ConfigValueType.STRING, true),
                ConfigRule.of("language.required-locales", ConfigValueType.STRING_LIST, true),
                ConfigRule.of("language.version.current", ConfigValueType.INT, true),
                ConfigRule.of("language.missing-message-log-interval-ms", ConfigValueType.LONG, false),
                ConfigRule.of("language.invalid-message-log-interval-ms", ConfigValueType.LONG, false),
                ConfigRule.of("language.diagnostics.warn-loaded-bundles", ConfigValueType.INT, false),
                ConfigRule.of("language.diagnostics.warn-missing-warning-keys", ConfigValueType.INT, false),
                ConfigRule.of("language.diagnostics.warn-invalid-warning-keys", ConfigValueType.INT, false),
                ConfigRule.of("language.diagnostics.warn-active-bossbars", ConfigValueType.INT, false),
                ConfigRule.of("storage.mode", ConfigValueType.STORAGE_MODE, true),
                ConfigRule.of("scheduler.backend", ConfigValueType.STRING, false),
                ConfigRule.of("scheduler.async-warning-ms", ConfigValueType.LONG, false),
                ConfigRule.of("scheduler.slow-task-warn-ms", ConfigValueType.LONG, false),
                ConfigRule.of("scheduler.slow-task-log-interval-ms", ConfigValueType.LONG, false),
                ConfigRule.of("scheduler.task-log-errors", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("scheduler.task-rethrow-errors", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("scheduler.task-error-log-interval-ms", ConfigValueType.LONG, false),
                ConfigRule.of("scheduler.diagnostics.warn-stale-one-shot-tasks", ConfigValueType.INT, false),
                ConfigRule.of("command.paper-native.max-suggestions", ConfigValueType.INT, false),
                ConfigRule.of("cooldown.cleanup-startup-delay-seconds", ConfigValueType.LONG, false),
                ConfigRule.of("cooldown.cleanup-interval-seconds", ConfigValueType.LONG, false),
                ConfigRule.of("command.error.cooldown-seconds", ConfigValueType.LONG, false),
                ConfigRule.of("command.error.log-interval-ms", ConfigValueType.LONG, false),
                ConfigRule.of("command.diagnostics.slow-handler-log-interval-ms", ConfigValueType.LONG, false),
                ConfigRule.of("storage.executor.core-threads", ConfigValueType.INT, false),
                ConfigRule.of("storage.executor.max-threads", ConfigValueType.INT, false),
                ConfigRule.of("storage.executor.scale-before-queue", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.executor.queue-capacity", ConfigValueType.INT, false),
                ConfigRule.of("storage.executor.queue-wait-warn-ms", ConfigValueType.LONG, false),
                ConfigRule.of("storage.executor.queue-wait-log-interval-ms", ConfigValueType.LONG, false),
                ConfigRule.of("storage.executor.allow-core-timeout", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.executor.prestart-core-threads", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.executor.prestart-local-mode", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.executor.shutdown-timeout-seconds", ConfigValueType.LONG, false),
                ConfigRule.of("storage.executor.shutdown-timeout-ms", ConfigValueType.LONG, false),
                ConfigRule.of("storage.standard-layout.enabled", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.standard-layout.folder", ConfigValueType.STRING, false),
                ConfigRule.of("storage.standard-layout.default-yaml-file", ConfigValueType.STRING, false),
                ConfigRule.of("storage.standard-layout.default-sqlite-file", ConfigValueType.STRING, false),
                ConfigRule.of("storage.yaml.write-through", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.yaml.health-flush-max-stores", ConfigValueType.INT, false),
                ConfigRule.of("storage.yaml.backup-before-replace", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.safety.main-thread-borrow-warning-interval-ms", ConfigValueType.LONG, false),
                ConfigRule.of("storage.query.slow-query-log-interval-ms", ConfigValueType.LONG, false),
                ConfigRule.of("database.query.default-plugin-max-queued-tasks", ConfigValueType.INT, false),
                ConfigRule.of("storage.health.startup-delay-ms", ConfigValueType.LONG, false),
                ConfigRule.of("storage.health.interval-ms", ConfigValueType.LONG, false),
                ConfigRule.of("storage.diagnostics.warn-unhealthy-units", ConfigValueType.INT, false),
                ConfigRule.of("storage.diagnostics.warn-dirty-yaml-stores", ConfigValueType.INT, false),
                ConfigRule.of("storage.diagnostics.warn-slow-tasks", ConfigValueType.LONG, false),
                ConfigRule.of("storage.diagnostics.warn-rejected-tasks", ConfigValueType.LONG, false),
                ConfigRule.of("storage.verification.flush-dirty-yaml", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.verification.warn-dirty-yaml", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.verification.checkpoint-sqlite", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.verification.deep-integrity-check", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.sqlite.busy-timeout-ms", ConfigValueType.INT, false),
                ConfigRule.of("storage.sqlite.journal-mode", ConfigValueType.STRING, false),
                ConfigRule.of("storage.sqlite.synchronous", ConfigValueType.STRING, false),
                ConfigRule.of("storage.sqlite.wal-autocheckpoint", ConfigValueType.INT, false),
                ConfigRule.of("storage.sqlite.cache-size", ConfigValueType.INT, false),
                ConfigRule.of("storage.sqlite.temp-store", ConfigValueType.STRING, false),
                ConfigRule.of("storage.sqlite.foreign-keys", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.sqlite.journal-size-limit-bytes", ConfigValueType.LONG, false),
                ConfigRule.of("storage.sqlite.mmap-size-bytes", ConfigValueType.LONG, false),
                ConfigRule.of("storage.sqlite.checkpoint-on-health-check", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.sqlite.checkpoint-on-close", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.sqlite.checkpoint-mode", ConfigValueType.STRING, false),
                ConfigRule.of("storage.sqlite.close-checkpoint-mode", ConfigValueType.STRING, false),
                ConfigRule.of("storage.sqlite.optimize-on-checkpoint", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.local-sqlite.enabled", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.local-sqlite.extension", ConfigValueType.STRING, false),
                ConfigRule.of("storage.local-sqlite.checkpoint-on-health-check", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.local-sqlite.checkpoint-mode", ConfigValueType.STRING, false),
                ConfigRule.of("storage.local-sqlite.close-checkpoint-mode", ConfigValueType.STRING, false),
                ConfigRule.of("storage.local-sqlite.optimize-on-health-check", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.local-sqlite.optimize-on-checkpoint", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.local-sqlite.health.skip-when-pool-busy", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.local-sqlite.integrity.quick-check-on-health", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.local-sqlite.pragma.busy-timeout-ms", ConfigValueType.INT, false),
                ConfigRule.of("storage.local-sqlite.pragma.journal-mode", ConfigValueType.STRING, false),
                ConfigRule.of("storage.local-sqlite.pragma.synchronous", ConfigValueType.STRING, false),
                ConfigRule.of("storage.local-sqlite.pragma.wal-autocheckpoint", ConfigValueType.INT, false),
                ConfigRule.of("storage.local-sqlite.pragma.cache-size", ConfigValueType.INT, false),
                ConfigRule.of("storage.local-sqlite.pragma.temp-store", ConfigValueType.STRING, false),
                ConfigRule.of("storage.local-sqlite.pragma.journal-size-limit-bytes", ConfigValueType.LONG, false),
                ConfigRule.of("storage.local-sqlite.pragma.mmap-size-bytes", ConfigValueType.LONG, false),
                ConfigRule.of("storage.local-sqlite.pool.maximum-pool-size", ConfigValueType.INT, false),
                ConfigRule.of("database.compatibility-provider.enabled", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("storage.mysql.cache-prepared-statements", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("user-cache.maintenance-interval-seconds", ConfigValueType.LONG, false),
                ConfigRule.of("profiles.cache-ttl-seconds", ConfigValueType.LONG, false),
                ConfigRule.of("profiles.negative-cache-ttl-seconds", ConfigValueType.LONG, false),
                ConfigRule.of("profiles.maximum-cache-size", ConfigValueType.INT, false),
                ConfigRule.of("player-block-tracker.auto-track", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("audit.enabled", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("audit.write-enabled", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("audit.sql-batch-size", ConfigValueType.INT, false),
                ConfigRule.of("audit.metadata.max-entries", ConfigValueType.INT, false),
                ConfigRule.of("ledger.enabled", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("ledger.write-enabled", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("gui.default-size", ConfigValueType.GUI_SIZE, false),
                ConfigRule.of("gui.session-ttl-seconds", ConfigValueType.LONG, false),
                ConfigRule.of("gui.stale-cleanup-interval-seconds", ConfigValueType.LONG, false),
                ConfigRule.of("gui.click-debounce-ms", ConfigValueType.LONG, false),
                ConfigRule.of("gui.diagnostics.warn-active-sessions", ConfigValueType.INT, false),
                ConfigRule.of("gui.diagnostics.warn-reopen-guards", ConfigValueType.INT, false),
                ConfigRule.of("gui.diagnostics.warn-blocked-rapid-clicks", ConfigValueType.LONG, false),
                ConfigRule.of("gui.diagnostics.warn-failed-opens", ConfigValueType.LONG, false),
                ConfigRule.of("forms.enabled", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("forms.require-floodgate", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("forms.prefer-for-bedrock", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("forms.session-ttl-seconds", ConfigValueType.LONG, false),
                ConfigRule.of("forms.diagnostics.warn-active-sessions", ConfigValueType.INT, false),
                ConfigRule.of("forms.diagnostics.warn-failed-opens", ConfigValueType.LONG, false),
                ConfigRule.of("forms.diagnostics.warn-invalid-responses", ConfigValueType.LONG, false),
                ConfigRule.of("forms.diagnostics.debug-routing", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("forms.dialog-routing.enabled", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("forms.dialog-routing.bedrock-prefer-form", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("forms.dialog-routing.java-use-existing-dialog-service", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("forms.dialog-routing.unsupported-input-action", ConfigValueType.STRING, false),
                ConfigRule.of("forms.security.one-time-submit", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("forms.security.reject-unknown-buttons", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("forms.security.reject-expired-sessions", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("forms.security.clear-session-on-quit", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("forms.security.log-invalid-response", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("forms.text.max-title-length", ConfigValueType.INT, false),
                ConfigRule.of("forms.text.max-content-length", ConfigValueType.INT, false),
                ConfigRule.of("forms.text.object-fallback", ConfigValueType.STRING, false),
                ConfigRule.of("forms.text.strip-minimessage-tags", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("dialogs.enabled", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("dialogs.diagnostics.warn-active-sessions", ConfigValueType.INT, false),
                ConfigRule.of("dialogs.diagnostics.warn-native-actions", ConfigValueType.INT, false),
                ConfigRule.of("dialogs.diagnostics.warn-native-fallbacks", ConfigValueType.LONG, false),
                ConfigRule.of("dialogs.diagnostics.warn-failed-opens", ConfigValueType.LONG, false),
                ConfigRule.of("dialogs.native.enabled", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("dialogs.native.min-client-version", ConfigValueType.STRING, false),
                ConfigRule.of("dialogs.native.assume-compatible-without-client-detector", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("dialogs.native.default-columns", ConfigValueType.INT, false),
                ConfigRule.of("dialogs.native.default-button-width", ConfigValueType.INT, false),
                ConfigRule.of("dialogs.fallback.enabled", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("dialogs.fallback.default-size", ConfigValueType.GUI_SIZE, false),
                ConfigRule.of("dialogs.fallback.info-slot", ConfigValueType.SLOT, false),
                ConfigRule.of("dialogs.session-ttl-seconds", ConfigValueType.LONG, false),
                ConfigRule.of("server-links.enabled", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("server-links.server-name", ConfigValueType.STRING, false),
                ConfigRule.of("server-links.apply-all-on-reload", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("server-links.apply-all-initial-delay-ticks", ConfigValueType.LONG, false),
                ConfigRule.of("server-links.apply-all-batch-size", ConfigValueType.INT, false),
                ConfigRule.of("server-links.apply-all-stagger-ticks", ConfigValueType.LONG, false),
                ConfigRule.of("server-links.resend-on-join-delay-ticks", ConfigValueType.LONG, false),
                ConfigRule.of("doctor.suite.list-limit", ConfigValueType.INT, false),
                ConfigRule.of("doctor.suite.warn-missing-command-routes", ConfigValueType.BOOLEAN, false),
                ConfigRule.of("doctor.suite.warn-plugin-not-enabled", ConfigValueType.BOOLEAN, false)
        ));
    }

    private void validateRule(Plugin plugin, FileConfiguration config, ConfigRule rule, ConfigValidationReport report) {
        String path = rule.path();
        if (!config.contains(path)) {
            if (rule.required()) {
                report.error(plugin.getName() + ": missing required config path " + path);
            } else if (rule.fallback() != null) {
                report.warn(plugin.getName() + ": missing optional config path " + path + ", fallback=" + rule.fallback());
                config.set(path, rule.fallback());
            }
            return;
        }
        Object value = config.get(path);
        boolean ok = switch (rule.type()) {
            case BOOLEAN -> value instanceof Boolean;
            case INT -> value instanceof Number;
            case LONG -> value instanceof Number;
            case DOUBLE -> value instanceof Number;
            case STRING -> value instanceof String;
            case STRING_LIST -> config.isList(path);
            case MATERIAL -> parseMaterial(String.valueOf(value));
            case SOUND -> parseSound(String.valueOf(value));
            case SLOT -> value instanceof Number && config.getInt(path) >= 0 && config.getInt(path) <= 53;
            case GUI_SIZE -> value instanceof Number && config.getInt(path) >= 9 && config.getInt(path) <= 54 && config.getInt(path) % 9 == 0;
            case DURATION -> value instanceof Number || String.valueOf(value).trim().matches("(?i)\\d+\\s*(ms|s|m|h|d|ticks?)?");
            case STORAGE_MODE -> StorageMode.parse(String.valueOf(value), null) != null;
        };
        if (!ok) {
            if (rule.fallback() != null) {
                config.set(path, rule.fallback());
                report.warn(plugin.getName() + ": invalid config path " + path + " expected " + rule.type() + " got " + value + ", fallback=" + rule.fallback());
            } else {
                report.error(plugin.getName() + ": invalid config path " + path + " expected " + rule.type() + " got " + value);
            }
        }
    }

    @Override
    public boolean migrateDeclaredConfigFiles(Plugin plugin) {
        if (plugin == null) {
            logger.warning("Could not migrate declared config files: plugin is null");
            return false;
        }
        try (InputStream stream = plugin.getResource("ledat-plugin.yml")) {
            if (stream == null) {
                return false;
            }
            YamlConfiguration descriptor = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            Set<String> resources = new LinkedHashSet<>(descriptor.getStringList("config-files"));
            if (resources.isEmpty()) {
                return false;
            }

            boolean changed = false;
            int migrated = 0;
            int ensured = 0;
            int skipped = 0;
            for (String resource : resources) {
                if (!isYamlPath(resource)) {
                    skipped++;
                    continue;
                }
                String safeResourcePath;
                try {
                    safeResourcePath = safeResourcePath(resource);
                } catch (IllegalArgumentException error) {
                    logger.warning(plugin.getName() + " declares invalid config resource '" + resource + "': " + error.getMessage());
                    skipped++;
                    continue;
                }

                try (InputStream bundledStream = plugin.getResource(safeResourcePath)) {
                    if (bundledStream == null) {
                        skipped++;
                        continue;
                    }
                    YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(bundledStream, StandardCharsets.UTF_8));
                    String versionPath = detectVersionPath(bundled);
                    if (versionPath != null) {
                        int targetVersion = bundled.getInt(versionPath, 0);
                        if (targetVersion > 0 && migrateBundledYaml(plugin, safeResourcePath, versionPath, targetVersion)) {
                            changed = true;
                            migrated++;
                        }
                        continue;
                    }
                    if (ensureBundledYaml(plugin, safeResourcePath)) {
                        changed = true;
                        ensured++;
                    }
                } catch (Exception error) {
                    skipped++;
                    logger.warning("Could not process declared config resource "
                            + plugin.getName() + "/" + safeResourcePath + ": " + error.getMessage());
                }
            }

            if (changed) {
                logger.info("Processed declared config files for " + plugin.getName()
                        + ": migrated=" + migrated + ", ensured=" + ensured + ", skipped=" + skipped);
            }
            return changed;
        } catch (Exception error) {
            logger.warning("Could not migrate declared config files for " + plugin.getName() + ": " + error.getMessage());
            return false;
        }
    }

    @Override
    public boolean migrateBundledYaml(Plugin plugin, String resourcePath, String versionPath, int targetVersion) {
        try {
            if (plugin == null) {
                logger.warning("Could not migrate bundled YAML: plugin is null");
                return false;
            }
            String safeResourcePath = safeResourcePath(resourcePath);
            String safeVersionPath = safeVersionPath(versionPath);
            if (targetVersion < 1) {
                logger.warning("Invalid target config version for " + plugin.getName() + "/" + safeResourcePath + ": " + targetVersion);
                return false;
            }

            boolean createdFromBundle = ensureBundledYaml(plugin, safeResourcePath);
            File file = dataFile(plugin, safeResourcePath);
            FileConfiguration config = YamlConfiguration.loadConfiguration(file);
            int current = version(config, safeVersionPath, 0);
            if (current > targetVersion) {
                logger.warning(plugin.getName() + "/" + safeResourcePath + " has newer " + safeVersionPath
                        + "=" + current + " than bundled target=" + targetVersion + "; skipping downgrade.");
                return false;
            }
            if (current == targetVersion) {
                return false;
            }

            try (InputStream stream = plugin.getResource(safeResourcePath)) {
                if (stream == null) {
                    logger.warning("Missing bundled resource for migration: " + plugin.getName() + "/" + safeResourcePath);
                    return false;
                }
                if (!createdFromBundle && !matchesBundledResource(plugin, safeResourcePath, file)) {
                    backupIfPresent(file, current);
                }
                FileConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                mergeMissing(config, defaults);
            }
            setVersion(config, safeVersionPath, targetVersion);
            saveAtomically(config, file);
            logger.info("Migrated " + plugin.getName() + "/" + safeResourcePath + " from " + current + " to " + targetVersion);
            return true;
        } catch (Exception error) {
            String pluginName = plugin == null ? "<null>" : plugin.getName();
            logger.warning("Could not migrate " + pluginName + "/" + resourcePath + ": " + error.getMessage());
            return false;
        }
    }

    @Override
    public boolean ensureBundledYaml(Plugin plugin, String resourcePath) {
        try {
            if (plugin == null) {
                logger.warning("Could not ensure bundled YAML: plugin is null");
                return false;
            }
            String safeResourcePath = safeResourcePath(resourcePath);
            File file = dataFile(plugin, safeResourcePath);
            if (file.exists()) {
                return false;
            }
            try (InputStream stream = plugin.getResource(safeResourcePath)) {
                if (stream == null) {
                    logger.warning("Bundled YAML resource not found: " + plugin.getName() + "/" + safeResourcePath);
                    return false;
                }
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warning("Could not create config directory: " + parent.getAbsolutePath());
                return false;
            }
            if (!(plugin instanceof JavaPlugin javaPlugin)) {
                logger.warning("Plugin cannot save bundled resource because it is not a JavaPlugin: " + plugin.getName() + "/" + safeResourcePath);
                return false;
            }
            javaPlugin.saveResource(safeResourcePath, false);
            return true;
        } catch (Exception error) {
            logger.warning("Could not ensure bundled YAML " + (plugin == null ? "<null>" : plugin.getName()) + "/" + resourcePath + ": " + error.getMessage());
            return false;
        }
    }

    @Override
    public int version(FileConfiguration config, String versionPath, int fallback) {
        return config == null ? fallback : config.getInt(versionPath, fallback);
    }

    @Override
    public void setVersion(FileConfiguration config, String versionPath, int version) {
        if (config != null) {
            config.set(versionPath, version);
        }
    }

    @Override
    public void reload() {
        platformPlugin.reloadConfig();
    }

    @Override
    public Path resolveServerRootPath(Plugin plugin, String rawPath, String fallbackPath) {
        Plugin owner = plugin == null ? platformPlugin : plugin;
        String value = rawPath == null || rawPath.isBlank() ? fallbackPath : rawPath;
        if (value == null || value.isBlank()) {
            value = "logs/plugins/" + owner.getName().toLowerCase(Locale.ROOT);
        }
        File file = new File(value);
        if (file.isAbsolute()) {
            return file.toPath().normalize();
        }
        try {
            File pluginsDir = owner.getDataFolder().getParentFile();
            File serverRoot = pluginsDir == null || pluginsDir.getParentFile() == null
                    ? new File("").getCanonicalFile()
                    : pluginsDir.getParentFile().getCanonicalFile();
            return new File(serverRoot, value).toPath().normalize();
        } catch (IOException error) {
            logger.warning("Could not resolve server root path for " + owner.getName() + ": " + error.getMessage());
            return Path.of(value).toAbsolutePath().normalize();
        }
    }

    private void mergeMissing(FileConfiguration config, FileConfiguration defaults) {
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            if (!config.contains(key)) {
                config.set(key, defaults.get(key));
            }
        }
    }

    private File dataFile(Plugin plugin, String resourcePath) throws IOException {
        File dataFolder = plugin.getDataFolder().getCanonicalFile();
        File file = new File(dataFolder, resourcePath).getCanonicalFile();
        if (!file.toPath().startsWith(dataFolder.toPath())) {
            throw new IOException("resource path escapes plugin data folder: " + resourcePath);
        }
        return file;
    }

    private String safeResourcePath(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath is blank");
        }
        String normalized = resourcePath.trim().replace('\\', '/');
        if (normalized.startsWith("/") || normalized.endsWith("/")) {
            throw new IllegalArgumentException("invalid resourcePath: " + resourcePath);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".yml") && !lower.endsWith(".yaml")) {
            throw new IllegalArgumentException("resourcePath is not YAML: " + resourcePath);
        }
        Path path = Path.of(normalized).normalize();
        if (path.isAbsolute() || path.startsWith("..") || normalized.contains("../") || normalized.contains("/..")) {
            throw new IllegalArgumentException("resourcePath escapes plugin resources: " + resourcePath);
        }
        return normalized;
    }

    private boolean isYamlPath(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return false;
        }
        String lower = resourcePath.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    private String detectVersionPath(FileConfiguration config) {
        if (config == null) {
            return null;
        }
        if (config.isInt("config-version")) {
            return "config-version";
        }
        if (config.isInt("language-version")) {
            return "language-version";
        }
        if (config.isInt("_meta.schema-version")) {
            return "_meta.schema-version";
        }
        return null;
    }

    private String safeVersionPath(String versionPath) {
        if (versionPath == null || versionPath.isBlank()) {
            throw new IllegalArgumentException("versionPath is blank");
        }
        String normalized = versionPath.trim();
        if (normalized.contains("/") || normalized.contains("\\") || normalized.contains("..")) {
            throw new IllegalArgumentException("invalid versionPath: " + versionPath);
        }
        return normalized;
    }

    private void backupIfPresent(File file, int currentVersion) throws IOException {
        if (!file.exists()) {
            return;
        }
        File parent = file.getParentFile();
        File backup = new File(parent, file.getName() + ".v" + currentVersion + "." + System.currentTimeMillis() + ".bak");
        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
    }

    private boolean matchesBundledResource(Plugin plugin, String resourcePath, File file) {
        if (plugin == null || file == null || !file.isFile()) {
            return false;
        }
        try (InputStream stream = plugin.getResource(resourcePath)) {
            return stream != null && java.util.Arrays.equals(Files.readAllBytes(file.toPath()), stream.readAllBytes());
        } catch (IOException error) {
            return false;
        }
    }

    private void saveAtomically(FileConfiguration config, File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create config directory: " + parent.getAbsolutePath());
        }
        File temp = File.createTempFile(file.getName(), ".tmp", parent);
        try {
            config.save(temp);
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (temp.exists() && !temp.delete()) {
                temp.deleteOnExit();
            }
        }
    }

    private boolean parseMaterial(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return Material.matchMaterial(value.toUpperCase(Locale.ROOT)) != null;
    }

    private boolean parseSound(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Sound.valueOf(value.toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
