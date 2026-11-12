package vn.ledat.platform.api.hook;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Small helper for optional/required hook diagnostics.
 */
public final class HookGuard {
    private static final String LOGGED_PREFIX = "ledat.hookguard.logged.v1.";

    private HookGuard() {
    }

    public static boolean pluginEnabled(JavaPlugin owner, String hookName, String featureName, boolean required) {
        Objects.requireNonNull(owner, "owner");
        String safeHook = clean(hookName);
        boolean available = Bukkit.getPluginManager().isPluginEnabled(safeHook);
        logStatus(owner, safeHook, featureName, required, available, available ? "plugin enabled" : "plugin missing/disabled");
        return available;
    }

    public static Optional<Plugin> plugin(JavaPlugin owner, String hookName, String featureName, boolean required) {
        Objects.requireNonNull(owner, "owner");
        String safeHook = clean(hookName);
        Plugin plugin = Bukkit.getPluginManager().getPlugin(safeHook);
        boolean available = plugin != null && plugin.isEnabled();
        logStatus(owner, safeHook, featureName, required, available, available ? "plugin enabled" : "plugin missing/disabled");
        return available ? Optional.of(plugin) : Optional.empty();
    }

    public static <T> Optional<T> service(JavaPlugin owner, Class<T> serviceClass, String hookName, String featureName, boolean required) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(serviceClass, "serviceClass");
        String safeHook = clean(hookName);
        RegisteredServiceProvider<T> provider = owner.getServer().getServicesManager().getRegistration(serviceClass);
        T service = provider == null ? null : provider.getProvider();
        boolean available = service != null;
        logStatus(owner, safeHook, featureName, required, available, available ? "service provider registered" : "service provider missing");
        return available ? Optional.of(service) : Optional.empty();
    }

    public static void feature(JavaPlugin owner, String hookName, String featureName, boolean required, boolean available, String detail) {
        Objects.requireNonNull(owner, "owner");
        logStatus(owner, clean(hookName), featureName, required, available, detail == null ? "" : detail);
    }

    private static void logStatus(JavaPlugin owner, String hookName, String featureName, boolean required, boolean available, String detail) {
        String feature = featureName == null || featureName.isBlank() ? "feature" : featureName.trim();
        String key = LOGGED_PREFIX
                + owner.getName().toLowerCase(Locale.ROOT) + "."
                + hookName.toLowerCase(Locale.ROOT) + "."
                + feature.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_") + "."
                + available;
        synchronized (System.getProperties()) {
            if (System.getProperty(key) != null) {
                return;
            }
            System.setProperty(key, "true");
        }

        String message = "[Hook] " + hookName + " -> " + feature + ": "
                + (available ? "enabled" : (required ? "required but unavailable" : "disabled"));
        if (detail != null && !detail.isBlank()) {
            message += " (" + detail + ")";
        }
        if (available) {
            owner.getLogger().info(message);
        } else if (required) {
            owner.getLogger().log(Level.SEVERE, message);
        } else {
            owner.getLogger().warning(message);
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
