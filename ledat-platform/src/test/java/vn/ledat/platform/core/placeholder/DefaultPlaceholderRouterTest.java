package vn.ledat.platform.core.placeholder;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultPlaceholderRouterTest {
    @Test
    void preservesLongestPrefixAndRebuildsIndexAfterMutation() {
        DefaultPlaceholderRouter router = new DefaultPlaceholderRouter();
        Plugin first = plugin("FirstPlugin");
        Plugin second = plugin("SecondPlugin");

        router.registerRoute(first, "team", (player, parameter) -> "short:" + parameter, "short");
        router.registerRoute(second, "team_member", (player, parameter) -> "long:" + parameter, "long");

        assertEquals("long:count", router.resolve(null, "TEAM-MEMBER-count").orElseThrow());
        assertEquals("SecondPlugin", router.resolve(null, "meta_team_member_plugin").orElseThrow());

        router.registerRoute(second, "team_member", (player, parameter) -> "replaced:" + parameter, "replacement");
        assertEquals("replaced:count", router.resolve(null, "team_member_count").orElseThrow());

        router.unregisterPlugin(second);
        assertEquals("short:member_count", router.resolve(null, "team_member_count").orElseThrow());
        assertTrue(router.resolve(null, "meta_team_member_status").isEmpty());
    }

    @Test
    void normalizationAndResolverFailureRemainCompatible() {
        DefaultPlaceholderRouter router = new DefaultPlaceholderRouter();
        Plugin plugin = plugin("Owner");
        router.registerRoute(plugin, "mixed route", (player, parameter) -> parameter, "normalized");
        router.registerRoute(plugin, "broken", (player, parameter) -> { throw new IllegalStateException("boom"); }, "failure");

        assertEquals("value", router.resolve(null, " mixed route-value ").orElseThrow());
        assertEquals("error", router.resolve(null, "broken").orElseThrow());
        assertEquals("LeDatPlatform", router.resolve(null, "   ").orElseThrow());
    }

    @Test
    void unsupportedUnicodeCodePointNormalizesToOneSeparator() {
        DefaultPlaceholderRouter router = new DefaultPlaceholderRouter();
        router.registerRoute(plugin("UnicodeOwner"), "route😀name", (player, parameter) -> parameter, "unicode");

        assertEquals("value", router.resolve(null, "route_name_value").orElseThrow());
    }

    @Test
    void resolvesRedundantLeDatNamespaceWithoutBreakingExplicitRoutesOrLifecycle() {
        DefaultPlaceholderRouter router = new DefaultPlaceholderRouter();
        Plugin statusPlugin = plugin("LeDatStatus");
        Plugin explicitPlugin = plugin("ExplicitStatus");

        router.registerRoute(statusPlugin, "ledat_status", (player, parameter) -> "compat:" + parameter, "status");

        assertEquals("compat:icon", router.resolve(null, "status_icon").orElseThrow());
        assertEquals("compat:icon", router.resolve(null, "ledat_status_icon").orElseThrow());
        assertEquals("enabled", router.resolve(null, "meta_status_status").orElseThrow());
        assertEquals("LeDatStatus", router.resolve(null, "meta_status_plugin").orElseThrow());
        assertEquals(1, router.routes().size());
        assertEquals("ledat_status", router.routes().getFirst().routeKey());

        router.registerRoute(explicitPlugin, "status", (player, parameter) -> "explicit:" + parameter, "explicit");
        assertEquals("explicit:icon", router.resolve(null, "status_icon").orElseThrow());
        assertEquals("compat:icon", router.resolve(null, "ledat_status_icon").orElseThrow());

        router.unregisterPlugin(explicitPlugin);
        assertEquals("compat:icon", router.resolve(null, "status_icon").orElseThrow());

        router.registerRoute(statusPlugin, "ledat_status", (player, parameter) -> "replacement:" + parameter, "replacement");
        assertEquals("replacement:icon", router.resolve(null, "status_icon").orElseThrow());

        router.unregisterPlugin(statusPlugin);
        assertTrue(router.resolve(null, "status_icon").isEmpty());
        assertTrue(router.resolve(null, "ledat_status_icon").isEmpty());
        assertTrue(router.resolve(null, "meta_status_status").isEmpty());
    }

    private Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> name;
            default -> defaultValue(method.getReturnType());
        });
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
