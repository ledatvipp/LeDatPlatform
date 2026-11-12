package vn.ledat.platform.core.executor;

import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.executor.ManagedExecutorService;
import vn.ledat.platform.api.executor.ManagedExecutorService.ExecutorHandle;
import vn.ledat.platform.api.executor.ManagedExecutorService.ExecutorStats;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class DefaultManagedExecutorService implements ManagedExecutorService {
    private static final int OWNER_HANDLE_WARNING_THRESHOLD = 16;
    private static final int TOTAL_HANDLE_WARNING_THRESHOLD = 128;
    private final Plugin platformPlugin;
    private final Logger logger;
    private final Map<String, ManagedHandle> handles = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> managedThreadNames = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicLong threadId = new AtomicLong();
    private final AtomicLong threadSnapshotAt = new AtomicLong();
    private volatile List<String> cachedUnregisteredThreadNames = List.of();

    public DefaultManagedExecutorService(Plugin platformPlugin) {
        this.platformPlugin = Objects.requireNonNull(platformPlugin, "platformPlugin");
        this.logger = platformPlugin.getLogger();
    }

    private long shutdownTimeoutMillis() {
        long legacySeconds = Math.max(1L, platformPlugin.getConfig()
                .getLong("production-profile.executors.shutdown-timeout-seconds", 2L));
        long cappedLegacyMillis = Math.min(legacySeconds * 1000L, 2000L);
        return Math.max(100L, platformPlugin.getConfig()
                .getLong("production-profile.executors.shutdown-timeout-ms", cappedLegacyMillis));
    }

    @Override
    public ExecutorHandle single(Plugin owner, String name) {
        int queue = Math.max(16, platformPlugin.getConfig().getInt("production-profile.executors.default-queue-capacity", 1024));
        return fixed(owner, name, 1, queue);
    }

    @Override
    public ExecutorHandle single(Plugin owner, String name, int queueCapacity) {
        return fixed(owner, name, 1, queueCapacity);
    }

    @Override
    public ExecutorHandle fixed(Plugin owner, String name, int threads, int queueCapacity) {
        Plugin safeOwner = owner(owner);
        String safeName = sanitizeName(name);
        int safeThreads = Math.max(1, Math.min(64, threads));
        int safeQueue = Math.max(1, queueCapacity);
        AtomicLong rejected = new AtomicLong();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                safeThreads,
                safeThreads,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(safeQueue),
                threadFactory(safeOwner.getName(), safeName),
                (task, pool) -> {
                    rejected.incrementAndGet();
                    throw new RejectedExecutionException("Managed executor queue full: " + safeOwner.getName() + "/" + safeName);
                }
        );
        executor.allowCoreThreadTimeOut(platformPlugin.getConfig().getBoolean("production-profile.executors.allow-core-timeout", true));
        return put(new ManagedHandle(safeOwner.getName(), safeName, executor, true, rejected));
    }

    @Override
    public ExecutorHandle scheduled(Plugin owner, String name, int threads) {
        Plugin safeOwner = owner(owner);
        String safeName = sanitizeName(name);
        int safeThreads = Math.max(1, Math.min(16, threads));
        int capacity = Math.max(1, platformPlugin.getConfig().getInt(
                "production-profile.executors.scheduled-queue-capacity",
                platformPlugin.getConfig().getInt("production-profile.executors.default-queue-capacity", 1024)));
        AtomicLong rejected = new AtomicLong();
        ScheduledThreadPoolExecutor executor = new BoundedScheduledThreadPoolExecutor(safeThreads, capacity, threadFactory(safeOwner.getName(), safeName), (task, pool) -> {
            rejected.incrementAndGet();
            throw new RejectedExecutionException("Managed scheduled executor queue full: " + safeOwner.getName() + "/" + safeName);
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return put(new ManagedHandle(safeOwner.getName(), safeName, executor, true, rejected));
    }

    @Override
    public ExecutorHandle register(Plugin owner, String name, ExecutorService executor, boolean shutdownOnDisable) {
        if (executor == null) {
            throw new IllegalArgumentException("executor cannot be null");
        }
        Plugin safeOwner = owner(owner);
        return put(new ManagedHandle(safeOwner.getName(), sanitizeName(name), executor, shutdownOnDisable, new AtomicLong()));
    }

    @Override
    public Map<String, ExecutorStats> stats() {
        return handles.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().stats()));
    }

    @Override
    public List<String> unregisteredLeDatThreadNames() {
        long now = System.currentTimeMillis();
        long cacheMillis = Math.max(0L, platformPlugin.getConfig().getLong(
                "production-profile.executors.thread-scan-cache-ms", 5000L));
        long previous = threadSnapshotAt.get();
        if (cacheMillis > 0L && now - previous < cacheMillis) {
            return cachedUnregisteredThreadNames;
        }
        if (!threadSnapshotAt.compareAndSet(previous, now)) {
            return cachedUnregisteredThreadNames;
        }
        List<String> names = new ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread == null || !thread.isAlive()) {
                continue;
            }
            String name = thread.getName();
            if (name == null || !name.toLowerCase(Locale.ROOT).contains("ledat")) {
                continue;
            }
            if (managedThreadNames.contains(name) || knownPlatformInternalThread(name)) {
                continue;
            }
            names.add(name);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> snapshot = names.stream().distinct().limit(50).toList();
        cachedUnregisteredThreadNames = snapshot;
        return snapshot;
    }

    @Override
    public void shutdown(Plugin owner) {
        shutdown(owner, shutdownTimeoutMillis());
    }

    public void shutdown(Plugin owner, long totalTimeoutMillis) {
        if (owner == null) {
            return;
        }
        long deadline = deadlineAfter(totalTimeoutMillis);
        String pluginName = owner.getName();
        List<String> keys = handles.entrySet().stream()
                .filter(entry -> entry.getValue().pluginName().equalsIgnoreCase(pluginName))
                .map(Map.Entry::getKey)
                .toList();
        for (String key : keys) {
            ManagedHandle handle = handles.remove(key);
            if (handle != null) {
                handle.shutdown(remainingMillis(deadline));
            }
        }
    }

    @Override
    public void shutdownAll() {
        long deadline = deadlineAfter(shutdownTimeoutMillis());
        List<ManagedHandle> snapshot = new ArrayList<>(handles.values());
        handles.clear();
        snapshot.stream()
                .sorted(Comparator.comparing(ManagedHandle::pluginName).thenComparing(ManagedHandle::name))
                .forEach(handle -> handle.shutdown(remainingMillis(deadline)));
    }

    private ManagedHandle put(ManagedHandle handle) {
        ManagedHandle previous = handles.put(handle.key(), handle);
        if (previous != null && previous.shutdownOnDisable) {
            previous.shutdown();
        }
        if (previous == null) {
            int ownerHandles = 0;
            for (ManagedHandle current : handles.values()) {
                if (current.pluginName().equalsIgnoreCase(handle.pluginName())) {
                    ownerHandles++;
                }
            }
            if (ownerHandles == OWNER_HANDLE_WARNING_THRESHOLD + 1) {
                logger.warning("Plugin " + handle.pluginName() + " owns more than "
                        + OWNER_HANDLE_WARNING_THRESHOLD + " managed executors; review aggregate thread usage.");
            }
            if (handles.size() == TOTAL_HANDLE_WARNING_THRESHOLD + 1) {
                logger.warning("LeDatPlatform manages more than " + TOTAL_HANDLE_WARNING_THRESHOLD
                        + " executors; review aggregate thread and queue capacity.");
            }
        }
        return handle;
    }

    private ThreadFactory threadFactory(String pluginName, String name) {
        String ownerPart = sanitizeName(pluginName);
        String namePart = sanitizeName(name);
        return task -> {
            // folia-audit: ignore raw-executor -- managed platform executor thread tracked by ManagedExecutorService.
            String threadName = "LeDatManaged-" + ownerPart + "-" + namePart + "-" + threadId.incrementAndGet();
            Thread thread = new Thread(() -> {
                try {
                    task.run();
                } finally {
                    managedThreadNames.remove(Thread.currentThread().getName());
                }
            }, threadName);
            managedThreadNames.add(thread.getName());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, error) -> logger.log(Level.WARNING, "Uncaught managed executor error: " + thread.getName(), error));
            return thread;
        };
    }

    private boolean knownPlatformInternalThread(String name) {
        return name.startsWith("LeDatPlatform-DB-")
                || name.startsWith("LeDatPlatform-WriteBehind-")
                || name.startsWith("HikariPool-")
                || name.startsWith("LeDatPlatformPool-");
    }

    private long deadlineAfter(long timeoutMillis) {
        long safeMillis = Math.max(0L, timeoutMillis);
        long now = System.nanoTime();
        long nanos = TimeUnit.MILLISECONDS.toNanos(safeMillis);
        return Long.MAX_VALUE - now < nanos ? Long.MAX_VALUE : now + nanos;
    }

    private long remainingMillis(long deadline) {
        long remaining = deadline - System.nanoTime();
        return remaining <= 0L ? 0L : Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private Plugin owner(Plugin owner) {
        return owner == null ? platformPlugin : owner;
    }

    private String sanitizeName(String value) {
        String safe = value == null ? "executor" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        safe = safe.replaceAll("-+", "-").replaceAll("^-+|-+$", "");
        return safe.isBlank() ? "executor" : safe;
    }

    private final class ManagedHandle implements ExecutorHandle {
        private final String pluginName;
        private final String name;
        private final ExecutorService executor;
        private final boolean shutdownOnDisable;
        private final AtomicLong rejected;
        private final AtomicLong submitted = new AtomicLong();
        private final AtomicLong completed = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final Instant createdAt = Instant.now();
        private final String key;

        private ManagedHandle(String pluginName, String name, ExecutorService executor, boolean shutdownOnDisable, AtomicLong rejected) {
            this.pluginName = pluginName == null || pluginName.isBlank() ? platformPlugin.getName() : pluginName;
            this.name = name == null || name.isBlank() ? "executor" : name;
            this.executor = executor;
            this.shutdownOnDisable = shutdownOnDisable;
            this.rejected = rejected;
            this.key = this.pluginName.toLowerCase(Locale.ROOT) + ":" + this.name;
        }

        @Override public String key() { return key; }
        @Override public String pluginName() { return pluginName; }
        @Override public String name() { return name; }
        @Override public ExecutorService executor() { return executor; }

        @Override
        public <T> CompletableFuture<T> supplyAsync(String operation, Supplier<T> supplier) {
            submitted.incrementAndGet();
            CompletableFuture<T> future = new CompletableFuture<>();
            try {
                executor.execute(() -> {
                    try {
                        T result = supplier.get();
                        completed.incrementAndGet();
                        future.complete(result);
                    } catch (Throwable error) {
                        failed.incrementAndGet();
                        logger.log(Level.WARNING, "Managed executor task failed: " + pluginName + "/" + name + " operation=" + safeOperation(operation), error);
                        future.completeExceptionally(error);
                    }
                });
            } catch (RejectedExecutionException error) {
                failed.incrementAndGet();
                future.completeExceptionally(error);
            }
            return future;
        }

        @Override
        public ExecutorStats stats() {
            int poolSize = 0;
            int active = 0;
            int queued = 0;
            if (executor instanceof ThreadPoolExecutor pool) {
                poolSize = pool.getPoolSize();
                active = pool.getActiveCount();
                queued = pool.getQueue().size();
            } else if (executor instanceof ScheduledThreadPoolExecutor pool) {
                poolSize = pool.getPoolSize();
                active = pool.getActiveCount();
                queued = pool.getQueue().size();
            }
            return new ExecutorStats(key, pluginName, name, executor instanceof ScheduledExecutorService,
                    poolSize, active, queued, submitted.get(), completed.get(), failed.get(), rejected.get(),
                    executor.isShutdown(), createdAt);
        }

        @Override
        public void shutdown() {
            shutdown(shutdownTimeoutMillis());
        }

        private void shutdown(long timeoutMillis) {
            executor.shutdown();
            try {
                if (timeoutMillis <= 0L || !executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }

        private String safeOperation(String operation) {
            return operation == null || operation.isBlank() ? "task" : operation.replaceAll("[^A-Za-z0-9_.:-]+", "_");
        }
    }
}
