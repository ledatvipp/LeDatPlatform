package vn.ledat.platform.core.integration;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import vn.ledat.platform.api.LeDatPlatformApi;
import vn.ledat.platform.api.economy.EconomyService;
import vn.ledat.platform.api.hook.HookRegistry;
import vn.ledat.platform.api.item.CustomItemService;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BuiltInIntegrationsClassloadingTest {
    @Test
    void containerLoadsAndShutsDownWithoutLoadingOptionalPluginApis() {
        AtomicInteger unregistrations = new AtomicInteger();
        Plugin owner = proxy(Plugin.class, (method, args) -> "getName".equals(method.getName()) ? "LeDatPlatform" : defaultValue(method.getReturnType()));
        CustomItemService items = proxy(CustomItemService.class, (method, args) -> {
            if ("unregister".equals(method.getName())) unregistrations.incrementAndGet();
            return defaultValue(method.getReturnType());
        });
        EconomyService economy = proxy(EconomyService.class, (method, args) -> {
            if ("unregister".equals(method.getName())) unregistrations.incrementAndGet();
            return defaultValue(method.getReturnType());
        });
        HookRegistry hooks = proxy(HookRegistry.class, (method, args) -> {
            if ("unregister".equals(method.getName())) unregistrations.incrementAndGet();
            return defaultValue(method.getReturnType());
        });
        LeDatPlatformApi api = proxy(LeDatPlatformApi.class, (method, args) -> switch (method.getName()) {
            case "items" -> items;
            case "economy" -> economy;
            case "hooks" -> hooks;
            default -> defaultValue(method.getReturnType());
        });

        assertDoesNotThrow(() -> Class.forName(BuiltInIntegrations.class.getName()));
        assertDoesNotThrow(() -> BuiltInIntegrations.uninstall(owner, api));
        assertEquals(8, unregistrations.get());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (ignored, method, args) -> handler.call(method, args));
    }

    private static Object defaultValue(Class<?> type) {
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

    @FunctionalInterface
    private interface Invocation {
        Object call(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
}
