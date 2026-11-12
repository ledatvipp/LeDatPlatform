package vn.ledat.platform.core.executor;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import vn.ledat.platform.api.executor.ManagedExecutorService.ExecutorHandle;

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.AbstractExecutorService;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultManagedExecutorServiceTest {
    @Test
    void scheduledBacklogIsBoundedAndRejectionCountedOnce() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("production-profile.executors.scheduled-queue-capacity", 2);
        config.set("production-profile.executors.allow-core-timeout", false);
        config.set("production-profile.executors.shutdown-timeout-ms", 100L);
        Plugin plugin = plugin("LeDatPlatform", config);
        DefaultManagedExecutorService service = new DefaultManagedExecutorService(plugin);
        ExecutorHandle handle = service.scheduled(plugin, "bounded", 1);
        ScheduledExecutorService executor = handle.scheduledExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            executor.schedule(() -> {
                started.countDown();
                await(release);
            }, 0L, TimeUnit.MILLISECONDS);
            assertTrue(started.await(3L, TimeUnit.SECONDS));
            executor.schedule(() -> { }, 1L, TimeUnit.HOURS);

            assertThrows(RejectedExecutionException.class,
                    () -> executor.schedule(() -> { }, 1L, TimeUnit.HOURS));
            assertEquals(1L, handle.stats().rejectedTasks());
        } finally {
            release.countDown();
            service.shutdownAll();
        }
    }

    @Test
    void cancellingDelayedTaskReleasesScheduledAdmission() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("production-profile.executors.scheduled-queue-capacity", 1);
        config.set("production-profile.executors.shutdown-timeout-ms", 100L);
        Plugin plugin = plugin("LeDatPlatform", config);
        DefaultManagedExecutorService service = new DefaultManagedExecutorService(plugin);
        ExecutorHandle handle = service.scheduled(plugin, "cancel-release", 1);
        ScheduledExecutorService executor = handle.scheduledExecutor();

        try {
            ScheduledFuture<?> first = executor.schedule(() -> { }, 1L, TimeUnit.HOURS);
            assertTrue(first.cancel(false));
            ScheduledFuture<?> replacement = executor.schedule(() -> { }, 1L, TimeUnit.HOURS);
            assertTrue(!replacement.isCancelled());
            assertEquals(0L, handle.stats().rejectedTasks());
        } finally {
            service.shutdownAll();
        }
    }

    @Test
    void scheduledHandleSubmissionUsesTheSameAdmissionBound() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("production-profile.executors.scheduled-queue-capacity", 1);
        config.set("production-profile.executors.shutdown-timeout-ms", 100L);
        Plugin plugin = plugin("LeDatPlatform", config);
        DefaultManagedExecutorService service = new DefaultManagedExecutorService(plugin);
        ExecutorHandle handle = service.scheduled(plugin, "handle-bound", 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            handle.supplyAsync("blocking", () -> {
                started.countDown();
                await(release);
                return 1;
            });
            assertTrue(started.await(3L, TimeUnit.SECONDS));

            assertThrows(CompletionException.class,
                    () -> handle.supplyAsync("overflow", () -> 2).join());
            assertEquals(1L, handle.stats().rejectedTasks());
            assertEquals(1L, handle.stats().failedTasks());
        } finally {
            release.countDown();
            service.shutdownAll();
        }
    }

    @Test
    void shutdownAllUsesOneDeadlineAcrossHandles() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("production-profile.executors.shutdown-timeout-ms", 120L);
        Plugin plugin = plugin("LeDatPlatform", config);
        DefaultManagedExecutorService service = new DefaultManagedExecutorService(plugin);
        service.register(plugin, "first", new NeverTerminatingExecutor(), true);
        service.register(plugin, "second", new NeverTerminatingExecutor(), true);

        long started = System.nanoTime();
        service.shutdownAll();
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(tookMillis < 210L, "shutdown timeout must be shared instead of multiplied per handle; took=" + tookMillis);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(3L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Plugin plugin(String name, YamlConfiguration config) {
        return (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "getConfig" -> config;
            case "getLogger" -> java.util.logging.Logger.getLogger(name);
            case "isEnabled" -> true;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
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

    private static final class NeverTerminatingExecutor extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return Collections.emptyList(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return false; }
        @Override public void execute(Runnable command) { }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            unit.sleep(timeout);
            return false;
        }
    }
}
