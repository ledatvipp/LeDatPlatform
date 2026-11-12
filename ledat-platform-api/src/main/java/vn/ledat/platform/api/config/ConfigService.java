package vn.ledat.platform.api.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

public interface ConfigService {
    ConfigValidationReport validate(Plugin plugin, FileConfiguration config, List<ConfigRule> rules);
    ConfigValidationReport validatePlatformConfig();
    boolean migrateBundledYaml(Plugin plugin, String resourcePath, String versionPath, int targetVersion);
    default boolean migrateDeclaredConfigFiles(Plugin plugin) {
        return false;
    }
    boolean ensureBundledYaml(Plugin plugin, String resourcePath);
    int version(FileConfiguration config, String versionPath, int fallback);
    void setVersion(FileConfiguration config, String versionPath, int version);
    void reload();

    /**
     * Resolve a path from the server root when it is relative. This keeps file logs,
     * audit exports and operator-facing reports out of individual plugin data folders.
     */
    default Path resolveServerRootPath(Plugin plugin, String rawPath, String fallbackPath) {
        String value = rawPath == null || rawPath.isBlank() ? fallbackPath : rawPath;
        if (value == null || value.isBlank()) {
            value = "logs/plugins/" + (plugin == null ? "unknown" : plugin.getName().toLowerCase(java.util.Locale.ROOT));
        }
        File file = new File(value);
        if (file.isAbsolute()) {
            return file.toPath().normalize();
        }
        File pluginsDir = plugin == null ? null : plugin.getDataFolder().getParentFile();
        File serverRoot = pluginsDir == null || pluginsDir.getParentFile() == null
                ? new File("").getAbsoluteFile()
                : pluginsDir.getParentFile();
        return new File(serverRoot, value).toPath().normalize();
    }

    default Path pluginLogFolder(Plugin plugin, String pluginId) {
        String safePluginId = pluginId == null || pluginId.isBlank()
                ? (plugin == null ? "unknown" : plugin.getName())
                : pluginId;
        safePluginId = safePluginId.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
        if (safePluginId.isBlank()) {
            safePluginId = "unknown";
        }
        return resolveServerRootPath(plugin, "logs/plugins/" + safePluginId, "logs/plugins/" + safePluginId);
    }

    /** Convenience method for migrated LeDat series plugins. */
    default boolean ensureMainConfig(Plugin plugin, int targetVersion) {
        boolean declared = migrateDeclaredConfigFiles(plugin);
        boolean copied = ensureBundledYaml(plugin, "config.yml");
        boolean migrated = migrateBundledYaml(plugin, "config.yml", "config-version", targetVersion);
        plugin.reloadConfig();
        return declared || copied || migrated;
    }

    /** Validate a plugin's currently loaded config with platform generic checks. */
    default ConfigValidationReport validate(Plugin plugin) {
        return validate(plugin, plugin.getConfig(), List.of());
    }

    record ConfigRule(String path, ConfigValueType type, boolean required, Object fallback, String note) {
        public static ConfigRule of(String path, ConfigValueType type, boolean required) {
            return new ConfigRule(path, type, required, null, "");
        }
    }

    enum ConfigValueType {
        BOOLEAN, INT, LONG, DOUBLE, STRING, STRING_LIST, MATERIAL, SOUND, SLOT, GUI_SIZE, DURATION, STORAGE_MODE
    }
}
