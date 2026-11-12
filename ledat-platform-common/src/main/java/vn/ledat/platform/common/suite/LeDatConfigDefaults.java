package vn.ledat.platform.common.suite;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.platform.common.i18n.LeDatLanguageCodes;

import java.util.List;

/** Shared config keys used by LeDat plugins after migration. */
public final class LeDatConfigDefaults {
    public static final String CONFIG_VERSION = "config-version";
    public static final String SUITE_ROOT = "ledat-suite";
    public static final String SUITE_ENABLED = SUITE_ROOT + ".enabled";
    public static final String SUITE_INTEROP = SUITE_ROOT + ".interop";
    public static final String SUITE_CLUSTER_ID = SUITE_ROOT + ".cluster-id";
    public static final String SUITE_DEBUG = SUITE_ROOT + ".debug";
    public static final String SUITE_LANGUAGE = SUITE_ROOT + ".language";
    public static final String SUITE_LANGUAGE_DEFAULT = SUITE_LANGUAGE + ".default";
    public static final String SUITE_LANGUAGE_FALLBACK = SUITE_LANGUAGE + ".fallback";
    public static final String SUITE_LANGUAGE_SUPPORTED = SUITE_LANGUAGE + ".supported";
    public static final String SUITE_DATABASE = SUITE_ROOT + ".database";
    public static final String SUITE_DATABASE_MODE = SUITE_DATABASE + ".mode";
    public static final String SUITE_DATABASE_PLATFORM_ENABLED = SUITE_DATABASE + ".platform.enabled";
    public static final String SUITE_DATABASE_PLATFORM_REQUIRE = SUITE_DATABASE + ".platform.require-platform";
    public static final String SUITE_DATABASE_PLATFORM_TABLE_PREFIX = SUITE_DATABASE + ".platform.table-prefix";
    public static final String SUITE_STARTUP_BANNER = SUITE_ROOT + ".startup-banner";
    public static final String SUITE_STARTUP_BANNER_ENABLED = SUITE_STARTUP_BANNER + ".enabled";
    public static final String SUITE_STARTUP_BANNER_MODE = SUITE_STARTUP_BANNER + ".mode";
    public static final String SUITE_STARTUP_BANNER_DELAY_TICKS = SUITE_STARTUP_BANNER + ".delay-ticks";
    public static final String SUITE_STARTUP_BANNER_MAX_PLUGIN_ROWS = SUITE_STARTUP_BANNER + ".max-plugin-rows";
    public static final String SUITE_STARTUP_BANNER_SHOW_CAPABILITIES = SUITE_STARTUP_BANNER + ".show-capabilities";
    public static final String SUITE_STARTUP_BANNER_SHOW_STORAGE = SUITE_STARTUP_BANNER + ".show-storage";

    private LeDatConfigDefaults() {
    }

    public static boolean suiteEnabled(JavaPlugin plugin) {
        return plugin != null && plugin.getConfig().getBoolean(SUITE_ENABLED, true);
    }

    public static boolean interopEnabled(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        return config.getBoolean(SUITE_ENABLED, true) && config.getBoolean(SUITE_INTEROP, true);
    }

    public static String clusterId(JavaPlugin plugin) {
        if (plugin == null) {
            return "default";
        }
        String value = plugin.getConfig().getString(SUITE_CLUSTER_ID, "default");
        return value == null || value.isBlank() ? "default" : value.trim();
    }

    public static String configVersion(JavaPlugin plugin) {
        if (plugin == null) {
            return "unknown";
        }
        Object value = plugin.getConfig().get(CONFIG_VERSION);
        return value == null ? "unknown" : String.valueOf(value);
    }

    public static boolean debug(JavaPlugin plugin) {
        return plugin != null && plugin.getConfig().getBoolean(SUITE_DEBUG, false);
    }

    public static String defaultLanguage(JavaPlugin plugin) {
        if (plugin == null) {
            return LeDatLanguageCodes.DEFAULT;
        }
        return LeDatLanguageCodes.normalizeSupported(plugin.getConfig().getString(SUITE_LANGUAGE_DEFAULT, LeDatLanguageCodes.DEFAULT), LeDatLanguageCodes.DEFAULT);
    }

    public static String fallbackLanguage(JavaPlugin plugin) {
        if (plugin == null) {
            return LeDatLanguageCodes.DEFAULT;
        }
        return LeDatLanguageCodes.normalizeSupported(plugin.getConfig().getString(SUITE_LANGUAGE_FALLBACK, defaultLanguage(plugin)), LeDatLanguageCodes.DEFAULT);
    }

    public static List<String> supportedLanguages(JavaPlugin plugin) {
        if (plugin == null) {
            return LeDatLanguageCodes.canonicalLanguages();
        }
        List<String> configured = plugin.getConfig().getStringList(SUITE_LANGUAGE_SUPPORTED);
        return configured.isEmpty() ? LeDatLanguageCodes.canonicalLanguages()
                : configured.stream().map(LeDatLanguageCodes::normalize).filter(LeDatLanguageCodes::isSupported).distinct().toList();
    }

    public static String databaseMode(JavaPlugin plugin) {
        if (plugin == null) {
            return "local";
        }
        String value = plugin.getConfig().getString(SUITE_DATABASE_MODE, "local");
        return value == null || value.isBlank() ? "local" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean platformDatabaseEnabled(JavaPlugin plugin) {
        return plugin != null && plugin.getConfig().getBoolean(SUITE_DATABASE_PLATFORM_ENABLED, false);
    }

    public static boolean platformDatabaseRequired(JavaPlugin plugin) {
        return plugin != null && plugin.getConfig().getBoolean(SUITE_DATABASE_PLATFORM_REQUIRE, false);
    }

    public static String platformTablePrefix(JavaPlugin plugin) {
        if (plugin == null) {
            return "";
        }
        String value = plugin.getConfig().getString(SUITE_DATABASE_PLATFORM_TABLE_PREFIX, "");
        return value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9_]", "_");
    }

    public static boolean startupBannerEnabled(JavaPlugin plugin) {
        return plugin != null
                && plugin.getConfig().getBoolean(SUITE_ENABLED, true)
                && plugin.getConfig().getBoolean(SUITE_STARTUP_BANNER_ENABLED, true);
    }

    public static String startupBannerMode(JavaPlugin plugin) {
        if (plugin == null) {
            return "suite";
        }
        String value = plugin.getConfig().getString(SUITE_STARTUP_BANNER_MODE, "suite");
        return value == null || value.isBlank() ? "suite" : value.trim();
    }

    public static long startupBannerDelayTicks(JavaPlugin plugin) {
        if (plugin == null) {
            return 20L;
        }
        return clamp(plugin.getConfig().getLong(SUITE_STARTUP_BANNER_DELAY_TICKS, 20L), 1L, 20L * 60L);
    }

    public static int startupBannerMaxPluginRows(JavaPlugin plugin) {
        if (plugin == null) {
            return 32;
        }
        return (int) clamp(plugin.getConfig().getInt(SUITE_STARTUP_BANNER_MAX_PLUGIN_ROWS, 32), 1L, 128L);
    }

    public static boolean startupBannerShowCapabilities(JavaPlugin plugin) {
        return plugin == null || plugin.getConfig().getBoolean(SUITE_STARTUP_BANNER_SHOW_CAPABILITIES, true);
    }

    public static boolean startupBannerShowStorage(JavaPlugin plugin) {
        return plugin == null || plugin.getConfig().getBoolean(SUITE_STARTUP_BANNER_SHOW_STORAGE, true);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
