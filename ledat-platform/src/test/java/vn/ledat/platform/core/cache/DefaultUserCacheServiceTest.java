package vn.ledat.platform.core.cache;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import vn.ledat.platform.api.cache.UserCacheService;
import vn.ledat.platform.api.scheduler.LeDatScheduler;
import vn.ledat.platform.api.scheduler.LeDatTask;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultUserCacheServiceTest {
    @Test
    void coalescesLoadsAndFlushesDirtyValues() {
        Plugin plugin = plugin();
        DefaultUserCacheService service = new DefaultUserCacheService(plugin, scheduler());
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<String> saved = new AtomicReference<>();
        UUID userId = UUID.randomUUID();
        UserCacheService.UserCache<String> cache = service.cache(plugin, "profiles", String.class)
                .loader(id -> {
                    loads.incrementAndGet();
                    return CompletableFuture.completedFuture(Optional.of("loaded"));
                })
                .writer((id, value) -> {
                    saved.set(value);
                    return CompletableFuture.completedFuture(null);
                })
                .writeBehind(Duration.ofMinutes(1))
                .build();

        CompletableFuture<Optional<String>> first = cache.get(userId);
        CompletableFuture<Optional<String>> second = cache.get(userId);
        assertEquals("loaded", first.join().orElseThrow());
        assertEquals("loaded", second.join().orElseThrow());
        assertEquals(1, loads.get());

        cache.put(userId, "changed");
        cache.flush().join();
        assertEquals("changed", saved.get());
        assertEquals(0, cache.stats().dirtyEntries());
    }

    @Test
    void inFlightLoadCannotOverwriteDirtyLocalValue() {
        Plugin plugin = plugin();
        DefaultUserCacheService service = new DefaultUserCacheService(plugin, scheduler());
        CompletableFuture<Optional<String>> databaseLoad = new CompletableFuture<>();
        UUID userId = UUID.randomUUID();
        UserCacheService.UserCache<String> cache = service.cache(plugin, "users", String.class)
                .loader(id -> databaseLoad)
                .build();

        CompletableFuture<Optional<String>> loading = cache.get(userId);
        cache.put(userId, "local-newer");
        databaseLoad.complete(Optional.of("database-older"));

        assertEquals("local-newer", loading.join().orElseThrow());
        assertEquals("local-newer", cache.getIfPresent(userId).orElseThrow());
    }

    @Test
    void flushesOnlyCachesOwnedByRequestedPlugin() {
        Plugin first = plugin("FirstPlugin");
        Plugin second = plugin("SecondPlugin");
        DefaultUserCacheService service = new DefaultUserCacheService(first, scheduler());
        AtomicReference<String> firstSaved = new AtomicReference<>();
        AtomicReference<String> secondSaved = new AtomicReference<>();
        UUID userId = UUID.randomUUID();
        UserCacheService.UserCache<String> firstCache = service.cache(first, "users", String.class)
                .writer((id, value) -> { firstSaved.set(value); return CompletableFuture.completedFuture(null); })
                .build();
        UserCacheService.UserCache<String> secondCache = service.cache(second, "users", String.class)
                .writer((id, value) -> { secondSaved.set(value); return CompletableFuture.completedFuture(null); })
                .build();

        firstCache.put(userId, "first");
        secondCache.put(userId, "second");
        service.flush(first).join();

        assertEquals("first", firstSaved.get());
        assertNull(secondSaved.get());
        assertEquals(0, firstCache.stats().dirtyEntries());
        assertEquals(1, secondCache.stats().dirtyEntries());
    }

    @Test
    void boundedChurnFlushesEveryDirtyValueWithoutLosingRecentlyAccessedEntry() {
        Plugin plugin = plugin();
        DefaultUserCacheService service = new DefaultUserCacheService(plugin, scheduler());
        var saved = ConcurrentHashMap.<UUID>newKeySet();
        UserCacheService.UserCache<String> cache = service.cache(plugin, "bounded", String.class)
                .writer((id, value) -> {
                    saved.add(id);
                    return CompletableFuture.completedFuture(null);
                })
                .maximumSize(16)
                .writeBehind(Duration.ofMinutes(1))
                .build();
        UUID retained = UUID.randomUUID();
        cache.put(retained, "retained");
        for (int i = 0; i < 15; i++) {
            cache.put(UUID.randomUUID(), "initial-" + i);
        }
        assertEquals("retained", cache.getIfPresent(retained).orElseThrow());
        for (int i = 0; i < 32; i++) {
            cache.put(UUID.randomUUID(), "churn-" + i);
        }

        assertTrue(cache.size() <= 16);
        cache.flush().join();
        assertEquals(0, cache.stats().dirtyEntries());
        assertEquals(48, saved.size(), "evicted and retained dirty entries must reach the writer exactly once");
    }

    @Test
    void serializesWritesForTheSameUserSoStaleCompletionCannotWin() {
        Plugin plugin = plugin();
        DefaultUserCacheService service = new DefaultUserCacheService(plugin, scheduler());
        List<String> started = new CopyOnWriteArrayList<>();
        List<CompletableFuture<Void>> gates = new CopyOnWriteArrayList<>();
        UserCacheService.UserCache<String> cache = service.cache(plugin, "ordered-writes", String.class)
                .writer((id, value) -> {
                    started.add(value);
                    CompletableFuture<Void> gate = new CompletableFuture<>();
                    gates.add(gate);
                    return gate;
                })
                .writeBehind(Duration.ZERO)
                .build();
        UUID userId = UUID.randomUUID();

        cache.put(userId, "older");
        cache.put(userId, "newer");
        assertEquals(List.of("older"), started, "newer write must wait for the older write to settle");

        gates.getFirst().complete(null);
        assertEquals(List.of("older", "newer"), started);
        gates.get(1).complete(null);
        cache.flush().join();
        assertEquals(0, cache.stats().dirtyEntries());
    }

    private Plugin plugin() {
        return plugin("TestPlugin");
    }

    private Plugin plugin(String name) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("user-cache.maintenance-interval-seconds", 15L);
        return (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "getConfig" -> config;
            case "getLogger" -> Logger.getLogger(name);
            case "isEnabled" -> true;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> name;
            default -> defaultValue(method.getReturnType());
        });
    }

    private LeDatScheduler scheduler() {
        return (LeDatScheduler) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{LeDatScheduler.class}, (proxy, method, args) -> {
            if (LeDatTask.class.isAssignableFrom(method.getReturnType())) return LeDatTask.NOOP;
            return defaultValue(method.getReturnType());
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
