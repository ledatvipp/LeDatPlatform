package vn.ledat.platform.common.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conservative config-version helper for migrated LeDat plugins.
 *
 * <p>Design goals:</p>
 * <ul>
 *     <li>never overwrite existing admin values;</li>
 *     <li>merge only missing keys from the bundled default resource;</li>
 *     <li>create a timestamped backup before writing;</li>
 *     <li>never downgrade or mutate future-version files;</li>
 *     <li>write a migration report so changes are auditable;</li>
 *     <li>serialize writes per plugin/resource to avoid reload races.</li>
 * </ul>
 *
 * <p>Note: Bukkit's {@link YamlConfiguration} rewrites formatting/comments when a
 * merge is needed. For that reason this helper writes only when at least one
 * missing key or version bump is required, and always creates a backup first by
 * default.</p>
 */
public final class LeDatConfigMigrator {
    public static final int DEFAULT_CONFIG_VERSION = 7;
    private static final String CONFIG_VERSION = "config-version";
    private static final String MIGRATION_ROOT = "ledat-suite.config-migration";
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final Map<String, Object> RESOURCE_LOCKS = new ConcurrentHashMap<>();

    private LeDatConfigMigrator() {
    }

    public static MigrationResult ensureMainConfig(JavaPlugin plugin) {
        return ensureMainConfig(plugin, DEFAULT_CONFIG_VERSION);
    }

    public static MigrationResult ensureMainConfig(JavaPlugin plugin, int targetVersion) {
        MigrationResult result = ensureResource(plugin, "config.yml", targetVersion);
        ensureBundledResourceIfPresent(plugin, "languages/vi_VN.yml", vn.ledat.platform.common.i18n.LeDatMessages.LANGUAGE_CONFIG_VERSION);
        ensureBundledResourceIfPresent(plugin, "languages/en_US.yml", vn.ledat.platform.common.i18n.LeDatMessages.LANGUAGE_CONFIG_VERSION);
        ensureBundledResourceIfPresent(plugin, "languages/zh_CN.yml", vn.ledat.platform.common.i18n.LeDatMessages.LANGUAGE_CONFIG_VERSION);
        return result;
    }

    public static MigrationResult ensureBundledResourceIfPresent(JavaPlugin plugin, String resourceName, int targetVersion) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(resourceName, "resourceName");
        try (InputStream stream = plugin.getResource(resourceName)) {
            if (stream == null) {
                return MigrationResult.skipped(resourceName, "missing bundled resource");
            }
        } catch (IOException error) {
            plugin.getLogger().warning("Failed to inspect bundled " + resourceName + ": " + error.getMessage());
            return MigrationResult.skipped(resourceName, "inspection failed");
        }
        return ensureResource(plugin, resourceName, targetVersion);
    }

    public static MigrationResult ensureResource(JavaPlugin plugin, String resourceName, int targetVersion) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(resourceName, "resourceName");
        if (targetVersion < 0) {
            throw new IllegalArgumentException("targetVersion cannot be negative");
        }

        String lockKey = plugin.getDataFolder().getAbsolutePath() + '/' + resourceName.replace('\\', '/');
        Object lock = RESOURCE_LOCKS.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (lock) {
            return ensureResourceLocked(plugin, resourceName, targetVersion);
        }
    }

    private static MigrationResult ensureResourceLocked(JavaPlugin plugin, String resourceName, int targetVersion) {
        File file = new File(plugin.getDataFolder(), resourceName);
        try {
            if (!file.exists()) {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    return MigrationResult.skipped(resourceName, "could not create parent directory");
                }
                plugin.saveResource(resourceName, false);
            }
        } catch (IllegalArgumentException error) {
            plugin.getLogger().warning("No bundled " + resourceName + " found; skipping config migration.");
            return MigrationResult.skipped(resourceName, "missing bundled resource");
        }

        if (!file.exists()) {
            return MigrationResult.skipped(resourceName, "file not available after saveResource");
        }

        YamlConfiguration live = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = loadDefaults(plugin, resourceName);
        if (defaults == null) {
            return MigrationResult.skipped(resourceName, "unable to load bundled defaults");
        }

        boolean migrationEnabled = live.getBoolean(MIGRATION_ROOT + ".enabled", true);
        if (!migrationEnabled) {
            reloadIfMain(plugin, resourceName);
            return MigrationResult.skipped(resourceName, "disabled by config");
        }

        int liveVersion = parseVersion(live.get(CONFIG_VERSION), 0);
        boolean futureVersion = liveVersion > targetVersion;
        if (futureVersion) {
            plugin.getLogger().warning(resourceName + " has future config-version " + liveVersion
                    + "; current plugin target is " + targetVersion + ". Skipping merge to avoid mutating future config.");
            reloadIfMain(plugin, resourceName);
            return new MigrationResult(resourceName, liveVersion, targetVersion, false, false,
                    Collections.emptyList(), Collections.emptyList(), null, true, "future-version skipped");
        }

        boolean mergeMissingKeys = live.getBoolean(MIGRATION_ROOT + ".merge-missing-keys", true);
        boolean backupBeforeWrite = live.getBoolean(MIGRATION_ROOT + ".backup-before-write", true);
        boolean reportEnabled = live.getBoolean(MIGRATION_ROOT + ".write-report", true);

        List<String> addedKeys = new ArrayList<>();
        List<String> typeMismatches = new ArrayList<>();
        if (mergeMissingKeys) {
            mergeMissing(live, defaults, "", addedKeys, typeMismatches);
        }

        boolean versionUpdated = false;
        if (liveVersion < targetVersion) {
            live.set(CONFIG_VERSION, targetVersion);
            versionUpdated = true;
        }

        boolean changed = !addedKeys.isEmpty() || versionUpdated;
        if (!changed) {
            reloadIfMain(plugin, resourceName);
            return new MigrationResult(resourceName, liveVersion, targetVersion, false, false,
                    Collections.emptyList(), List.copyOf(typeMismatches), null, false,
                    typeMismatches.isEmpty() ? "up-to-date" : "up-to-date with type warnings");
        }

        File backupFile = null;
        try {
            if (backupBeforeWrite) {
                backupFile = backup(file, plugin, resourceName, liveVersion, targetVersion);
            }
            live.save(file);
            if (reportEnabled) {
                writeReport(plugin, resourceName, liveVersion, targetVersion, addedKeys, typeMismatches, versionUpdated, backupFile);
            }
            reloadIfMain(plugin, resourceName);
            plugin.getLogger().info("Migrated " + resourceName + " to config-version " + targetVersion
                    + " (added " + addedKeys.size() + " missing keys, preserved existing values"
                    + (typeMismatches.isEmpty() ? "" : ", type warnings " + typeMismatches.size()) + ")."
                    + (backupFile == null ? "" : " Backup: " + backupFile.getName()));
            return new MigrationResult(resourceName, liveVersion, targetVersion, true, versionUpdated,
                    List.copyOf(addedKeys), List.copyOf(typeMismatches), backupFile, false, "merged missing keys only");
        } catch (IOException error) {
            plugin.getLogger().severe("Failed to migrate " + resourceName + ": " + error.getMessage());
            reloadIfMain(plugin, resourceName);
            return new MigrationResult(resourceName, liveVersion, targetVersion, false, false,
                    List.copyOf(addedKeys), List.copyOf(typeMismatches), backupFile, false, "failed: " + error.getMessage());
        }
    }

    private static void reloadIfMain(JavaPlugin plugin, String resourceName) {
        if ("config.yml".equals(resourceName)) {
            plugin.reloadConfig();
        }
    }

    private static YamlConfiguration loadDefaults(JavaPlugin plugin, String resourceName) {
        try (InputStream stream = plugin.getResource(resourceName)) {
            if (stream == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException error) {
            plugin.getLogger().warning("Failed to read bundled " + resourceName + ": " + error.getMessage());
            return null;
        }
    }

    private static void mergeMissing(YamlConfiguration live, ConfigurationSection defaults, String prefix,
                                     List<String> addedKeys, List<String> typeMismatches) {
        for (String key : defaults.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object defaultValue = defaults.get(key);
            if (defaultValue instanceof ConfigurationSection defaultSection) {
                if (!live.contains(path)) {
                    live.createSection(path);
                    addedKeys.add(path);
                } else if (!live.isConfigurationSection(path)) {
                    typeMismatches.add(path + " expected section, found scalar; preserved admin value");
                    continue;
                }
                mergeMissing(live, defaultSection, path, addedKeys, typeMismatches);
                continue;
            }

            if (!live.contains(path)) {
                live.set(path, defaultValue);
                addedKeys.add(path);
            } else if (live.isConfigurationSection(path)) {
                typeMismatches.add(path + " expected scalar, found section; preserved admin value");
            }
        }
    }

    private static int parseVersion(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static File backup(File file, JavaPlugin plugin, String resourceName, int fromVersion, int toVersion) throws IOException {
        File backupDir = new File(plugin.getDataFolder(), "config-backups");
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            throw new IOException("Could not create backup directory: " + backupDir.getPath());
        }

        String safeName = resourceName.replace('/', '_').replace('\\', '_');
        String stamp = LocalDateTime.now().format(BACKUP_TIME);
        File backup = new File(backupDir, safeName + ".v" + fromVersion + "-to-v" + toVersion + "." + stamp + ".bak");
        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    private static void writeReport(JavaPlugin plugin, String resourceName, int fromVersion, int toVersion,
                                    List<String> addedKeys, List<String> typeMismatches,
                                    boolean versionUpdated, File backupFile) throws IOException {
        File reportDir = new File(plugin.getDataFolder(), "config-migrations");
        if (!reportDir.exists() && !reportDir.mkdirs()) {
            throw new IOException("Could not create migration report directory: " + reportDir.getPath());
        }

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("plugin", plugin.getName());
        yaml.set("resource", resourceName);
        yaml.set("from-version", fromVersion);
        yaml.set("to-version", toVersion);
        yaml.set("version-updated", versionUpdated);
        yaml.set("preserved-existing-values", true);
        yaml.set("added-missing-keys", addedKeys);
        yaml.set("type-warnings", typeMismatches);
        if (backupFile != null) {
            yaml.set("backup", backupFile.getPath());
        }
        yaml.set("created-at", LocalDateTime.now().toString());

        String safeName = resourceName.replace('/', '_').replace('\\', '_');
        File reportFile = new File(reportDir, safeName + ".migration-" + LocalDateTime.now().format(BACKUP_TIME) + ".yml");
        yaml.save(reportFile);
    }

    public record MigrationResult(
            String resourceName,
            int fromVersion,
            int toVersion,
            boolean changed,
            boolean versionUpdated,
            List<String> addedKeys,
            List<String> typeWarnings,
            File backupFile,
            boolean futureVersion,
            String message
    ) {
        public static MigrationResult skipped(String resourceName, String reason) {
            return new MigrationResult(resourceName, 0, 0, false, false,
                    Collections.emptyList(), Collections.emptyList(), null, false, reason);
        }
    }
}
