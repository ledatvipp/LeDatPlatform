package vn.ledat.platform.api.executor;

import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

public interface ManagedExecutorService {
    ExecutorHandle single(Plugin owner, String name);

    default ExecutorHandle single(Plugin owner, String name, int queueCapacity) {
        return single(owner, name);
    }

    ExecutorHandle fixed(Plugin owner, String name, int threads, int queueCapacity);

    ExecutorHandle scheduled(Plugin owner, String name, int threads);

    ExecutorHandle register(Plugin owner, String name, ExecutorService executor, boolean shutdownOnDisable);

    Map<String, ExecutorStats> stats();

    List<String> unregisteredLeDatThreadNames();

    void shutdown(Plugin owner);

    /**
     * Stop an owner's executors without blocking a Paper lifecycle callback.
     * Implementations may override this default to use a managed cleanup worker.
     */
    default CompletableFuture<Void> shutdownAsync(Plugin owner) {
        return CompletableFuture.runAsync(() -> shutdown(owner));
    }

    void shutdownAll();

    interface ExecutorHandle extends AutoCloseable {
        String key();
        String pluginName();
        String name();
        ExecutorService executor();

        default ScheduledExecutorService scheduledExecutor() {
            return executor() instanceof ScheduledExecutorService scheduled ? scheduled : null;
        }

        <T> CompletableFuture<T> supplyAsync(String operation, Supplier<T> supplier);

        default CompletableFuture<Void> runAsync(String operation, Runnable runnable) {
            return supplyAsync(operation, () -> {
                runnable.run();
                return null;
            });
        }

        ExecutorStats stats();

        void shutdown();

        @Override
        default void close() {
            shutdown();
        }
    }

    record ExecutorStats(
            String key,
            String pluginName,
            String name,
            boolean scheduled,
            int poolSize,
            int activeThreads,
            int queuedTasks,
            long submittedTasks,
            long completedTasks,
            long failedTasks,
            long rejectedTasks,
            boolean shutdown,
            Instant createdAt
    ) {
        public ExecutorStats {
            key = key == null ? "" : key;
            pluginName = pluginName == null || pluginName.isBlank() ? "unknown" : pluginName;
            name = name == null || name.isBlank() ? "executor" : name;
            poolSize = Math.max(0, poolSize);
            activeThreads = Math.max(0, activeThreads);
            queuedTasks = Math.max(0, queuedTasks);
            submittedTasks = Math.max(0L, submittedTasks);
            completedTasks = Math.max(0L, completedTasks);
            failedTasks = Math.max(0L, failedTasks);
            rejectedTasks = Math.max(0L, rejectedTasks);
            createdAt = createdAt == null ? Instant.now() : createdAt;
        }
    }
}
