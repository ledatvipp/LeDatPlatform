package vn.ledat.platform.core.placeholder;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.placeholder.PlaceholderRouter;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultPlaceholderRouter implements PlaceholderRouter {
    private static final String OUTER_NAMESPACE = "ledat_";

    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final Object mutationLock = new Object();
    private volatile LookupIndex lookupIndex = LookupIndex.empty();

    @Override
    public void registerRoute(Plugin plugin, String routeKey, PlaceholderResolver resolver, String description) {
        String key = normalize(routeKey);
        if (key.isBlank()) {
            return;
        }
        synchronized (mutationLock) {
            routes.put(key, new Route(plugin.getName(), key, resolver, description == null ? "" : description));
            rebuildOrderedRoutes();
        }
    }

    @Override
    public void unregisterPlugin(Plugin plugin) {
        if (plugin != null) {
            synchronized (mutationLock) {
                routes.entrySet().removeIf(entry -> entry.getValue().pluginName.equalsIgnoreCase(plugin.getName()));
                rebuildOrderedRoutes();
            }
        }
    }

    @Override
    public Optional<String> resolve(OfflinePlayer player, String params) {
        String normalized = normalize(params);
        if (normalized.isBlank()) {
            return Optional.of("LeDatPlatform");
        }
        LookupIndex index = lookupIndex;
        if (normalized.startsWith("meta_")) {
            return resolveMeta(index, normalized.substring("meta_".length()));
        }
        RouteMatch match = findRoute(index.routesByKey, index.orderedRoutes, normalized);
        if (match == null) {
            match = findRoute(index.aliasesByKey, index.orderedAliases, normalized);
        }
        return match == null ? Optional.empty() : invoke(match.route, player, match.parameter);
    }

    private RouteMatch findRoute(Map<String, Route> exactRoutes, List<LookupRoute> orderedRoutes, String normalized) {
        Route exact = exactRoutes.get(normalized);
        if (exact != null) {
            return new RouteMatch(exact, "");
        }
        for (LookupRoute lookupRoute : orderedRoutes) {
            String key = lookupRoute.lookupKey;
            int keyLength = key.length();
            if (normalized.length() > keyLength
                    && normalized.charAt(keyLength) == '_'
                    && normalized.startsWith(key)) {
                return new RouteMatch(lookupRoute.route, normalized.substring(keyLength + 1));
            }
        }
        return null;
    }

    private Optional<String> invoke(Route route, OfflinePlayer player, String parameter) {
        try {
            return Optional.ofNullable(route.resolver.resolve(player, parameter));
        } catch (Throwable error) {
            return Optional.of("error");
        }
    }

    private Optional<String> resolveMeta(LookupIndex index, String value) {
        String key = normalize(value);
        if (key.endsWith("_status")) {
            Route route = exactRoute(index, key.substring(0, key.length() - "_status".length()));
            if (route != null) {
                return Optional.of("enabled");
            }
        }
        if (key.endsWith("_plugin")) {
            Route route = exactRoute(index, key.substring(0, key.length() - "_plugin".length()));
            if (route != null) {
                return Optional.of(route.pluginName);
            }
        }
        return Optional.empty();
    }

    private Route exactRoute(LookupIndex index, String key) {
        Route route = index.routesByKey.get(key);
        return route == null ? index.aliasesByKey.get(key) : route;
    }

    @Override
    public List<RouteInfo> routes() {
        return lookupIndex.routesByKey.values().stream()
                .sorted(Comparator.comparing(route -> route.routeKey))
                .map(route -> new RouteInfo(route.pluginName, route.routeKey, route.description))
                .toList();
    }

    @Override
    public void reload() {
        // Routes are owned by plugins; do not clear on reload.
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lowered = value.trim().replace('-', '_').toLowerCase(Locale.ROOT);
        StringBuilder normalized = null;
        for (int index = 0; index < lowered.length();) {
            int character = lowered.codePointAt(index);
            int width = Character.charCount(character);
            boolean allowed = character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_';
            if (!allowed) {
                if (normalized == null) {
                    normalized = new StringBuilder(lowered.length());
                    normalized.append(lowered, 0, index);
                }
                normalized.append('_');
            } else if (normalized != null) {
                normalized.appendCodePoint(character);
            }
            index += width;
        }
        return normalized == null ? lowered : normalized.toString();
    }

    private void rebuildOrderedRoutes() {
        Map<String, Route> routesByKey = Map.copyOf(routes);
        List<LookupRoute> orderedRoutes = routesByKey.values().stream()
                .map(route -> new LookupRoute(route.routeKey, route))
                .sorted(Comparator.comparingInt((LookupRoute route) -> route.lookupKey.length()).reversed())
                .toList();

        Map<String, Route> aliasesByKey = new HashMap<>();
        for (Route route : routesByKey.values()) {
            if (route.routeKey.startsWith(OUTER_NAMESPACE) && route.routeKey.length() > OUTER_NAMESPACE.length()) {
                aliasesByKey.put(route.routeKey.substring(OUTER_NAMESPACE.length()), route);
            }
        }
        Map<String, Route> immutableAliases = Map.copyOf(aliasesByKey);
        List<LookupRoute> orderedAliases = immutableAliases.entrySet().stream()
                .map(entry -> new LookupRoute(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt((LookupRoute route) -> route.lookupKey.length()).reversed())
                .toList();
        lookupIndex = new LookupIndex(routesByKey, orderedRoutes, immutableAliases, orderedAliases);
    }

    private record Route(String pluginName, String routeKey, PlaceholderResolver resolver, String description) {
    }

    private record LookupRoute(String lookupKey, Route route) {
    }

    private record RouteMatch(Route route, String parameter) {
    }

    private record LookupIndex(
            Map<String, Route> routesByKey,
            List<LookupRoute> orderedRoutes,
            Map<String, Route> aliasesByKey,
            List<LookupRoute> orderedAliases
    ) {
        private static LookupIndex empty() {
            return new LookupIndex(Map.of(), List.of(), Map.of(), List.of());
        }
    }
}
