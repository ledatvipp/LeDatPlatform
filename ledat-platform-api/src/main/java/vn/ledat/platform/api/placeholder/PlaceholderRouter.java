package vn.ledat.platform.api.placeholder;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;

public interface PlaceholderRouter {
    /**
     * Registers a route beneath the canonical {@code %ledat_<route>_<parameter>%} expansion.
     * Route keys should omit the outer {@code ledat_} namespace. Implementations accept one
     * redundant leading {@code ledat_} as a compatibility alias, so a legacy key such as
     * {@code ledat_status} is also reachable through {@code %ledat_status_<parameter>%}.
     */
    void registerRoute(Plugin plugin, String routeKey, PlaceholderResolver resolver, String description);
    void unregisterPlugin(Plugin plugin);
    Optional<String> resolve(OfflinePlayer player, String params);
    List<RouteInfo> routes();
    void reload();

    @FunctionalInterface
    interface PlaceholderResolver {
        String resolve(OfflinePlayer player, String parameter);
    }

    record RouteInfo(String pluginName, String routeKey, String description) {
    }
}
