package vn.ledat.platform.core.scheduler;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.scheduler.LeDatScheduler;
import vn.ledat.platform.api.scheduler.LeDatTask;
import vn.ledat.platform.api.scheduler.EntityTaskOutcome;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DefaultScheduler implements LeDatScheduler {
    private final Plugin platformPlugin;
    private final Logger logger;
    private final FoliaLib foliaLib;
    private final String implementationName;
    private final Map<String, Set<ManagedTask>> tasksByOwner = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, AtomicLong> ownerGenerations = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();
    private final AtomicLong slow = new AtomicLong();
    private final Map<String, AtomicLong> failureLogTimes = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, AtomicLong> slowLogTimes = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int LOG_THROTTLE_MAP_CAP = 4096;
    private volatile Settings settings;

    public DefaultScheduler(Plugin platformPlugin) {
        this.platformPlugin = Objects.requireNonNull(platformPlugin, "platformPlugin");
        this.logger = platformPlugin.getLogger();
        reloadSettings();
        this.foliaLib = new FoliaLib(platformPlugin);
        String type;
        try {
            type = foliaLib.getImplType().name();
        } catch (Throwable ignored) {
            type = foliaLib.isFolia() ? "FOLIA" : (foliaLib.isPaper() ? "PAPER" : "UNKNOWN");
        }
        this.implementationName = type;
    }

    @Override
    public boolean isFolia() {
        return foliaLib.isFolia();
    }

    @Override
    public boolean isPrimaryThread() {
        try {
            if (foliaLib.isFolia()) {
                return foliaLib.getScheduler().isGlobalTickThread();
            }
            return Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public String implementationName() {
        return implementationName;
    }

    @Override
    public boolean isOwnedByCurrentRegion(Location location) {
        if (location == null || location.getWorld() == null) {
            return isPrimaryThread();
        }
        try {
            return foliaLib.getScheduler().isOwnedByCurrentRegion(location);
        } catch (Throwable ignored) {
            return !foliaLib.isFolia() && Bukkit.isPrimaryThread();
        }
    }

    @Override
    public boolean isOwnedByCurrentRegion(Entity entity) {
        if (entity == null) {
            return isPrimaryThread();
        }
        try {
            return foliaLib.getScheduler().isOwnedByCurrentRegion(entity);
        } catch (Throwable ignored) {
            return !foliaLib.isFolia() && Bukkit.isPrimaryThread();
        }
    }

    @Override
    public LeDatTask sync(Plugin owner, Runnable task) {
        Plugin safeOwner = owner(owner);
        if (!canSchedule(safeOwner, "sync", task)) {
            return LeDatTask.NOOP;
        }
        TaskState state = new TaskState();
        Runnable wrapped = oneShot(safeOwner, "sync", task, state);
        return scheduleTask(safeOwner, "sync", false, state,
                () -> wrapped(foliaLib.getScheduler().runLater(wrapped, 1L)));
    }

    @Override
    public LeDatTask async(Plugin owner, String taskName, Runnable task) {
        Plugin safeOwner = owner(owner);
        String safeName = taskName(taskName, "async");
        if (!canSchedule(safeOwner, safeName, task)) {
            return LeDatTask.NOOP;
        }
        TaskState state = new TaskState();
        Runnable wrapped = oneShot(safeOwner, safeName, task, state);
        return scheduleTask(safeOwner, safeName, false, state,
                () -> future(foliaLib.getScheduler().runAsync(consumer(wrapped))));
    }

    @Override
    public LeDatTask later(Plugin owner, Runnable task, long delayTicks) {
        Plugin safeOwner = owner(owner);
        if (!canSchedule(safeOwner, "later", task)) {
            return LeDatTask.NOOP;
        }
        long safeDelay = Math.max(1L, delayTicks);
        TaskState state = new TaskState();
        Runnable wrapped = oneShot(safeOwner, "later", task, state);
        return scheduleTask(safeOwner, "later", false, state,
                () -> wrapped(foliaLib.getScheduler().runLater(wrapped, safeDelay)));
    }

    @Override
    public LeDatTask asyncLater(Plugin owner, String taskName, Runnable task, Duration delay) {
        Plugin safeOwner = owner(owner);
        String safeName = taskName(taskName, "async-later");
        if (!canSchedule(safeOwner, safeName, task)) {
            return LeDatTask.NOOP;
        }
        long millis = Math.max(1L, delay == null ? 1L : delay.toMillis());
        TaskState state = new TaskState();
        Runnable wrapped = oneShot(safeOwner, safeName, task, state);
        return scheduleTask(safeOwner, safeName, false, state,
                () -> wrapped(foliaLib.getScheduler().runLaterAsync(wrapped, millis, TimeUnit.MILLISECONDS)));
    }

    @Override
    public LeDatTask repeat(Plugin owner, Runnable task, long delayTicks, long periodTicks) {
        Plugin safeOwner = owner(owner);
        if (!canSchedule(safeOwner, "repeat", task)) {
            return LeDatTask.NOOP;
        }
        long safeDelay = Math.max(1L, delayTicks);
        long safePeriod = Math.max(1L, periodTicks);
        TaskState state = new TaskState();
        Runnable wrapped = repeating(safeOwner, "repeat", task, state);
        return scheduleTask(safeOwner, "repeat", true, state,
                () -> wrapped(foliaLib.getScheduler().runTimer(wrapped, safeDelay, safePeriod)));
    }

    @Override
    public LeDatTask asyncRepeat(Plugin owner, String taskName, Runnable task, Duration delay, Duration period) {
        Plugin safeOwner = owner(owner);
        String safeName = taskName(taskName, "async-repeat");
        if (!canSchedule(safeOwner, safeName, task)) {
            return LeDatTask.NOOP;
        }
        long delayMillis = Math.max(1L, delay == null ? 1L : delay.toMillis());
        long periodMillis = Math.max(50L, period == null ? 50L : period.toMillis());
        TaskState state = new TaskState();
        Runnable wrapped = repeating(safeOwner, safeName, task, state);
        return scheduleTask(safeOwner, safeName, true, state,
                () -> wrapped(foliaLib.getScheduler().runTimerAsync(wrapped, delayMillis, periodMillis, TimeUnit.MILLISECONDS)));
    }

    @Override
    public LeDatTask entity(Plugin owner, Entity entity, Runnable task) {
        Plugin safeOwner = owner(owner);
        if (entity == null) {
            return LeDatTask.NOOP;
        }
        if (!canSchedule(safeOwner, "entity", task)) {
            return LeDatTask.NOOP;
        }
        TaskState state = new TaskState();
        Runnable wrapped = oneShot(safeOwner, "entity", task, state);
        return scheduleTask(safeOwner, "entity", false, state,
                () -> future(foliaLib.getScheduler().runAtEntityWithFallback(entity, consumer(wrapped), wrapped)));
    }

    @Override
    public <T> CompletableFuture<EntityTaskOutcome<T>> entityOperation(
            Plugin owner, Entity entity, Supplier<T> operation) {
        Plugin safeOwner = owner(owner);
        CompletableFuture<EntityTaskOutcome<T>> result = new CompletableFuture<>();
        if (entity == null || operation == null || closed.get() || !ownerEnabled(safeOwner)) {
            result.complete(EntityTaskOutcome.rejected());
            return result;
        }

        TaskState state = new TaskState();
        AtomicBoolean terminal = new AtomicBoolean(false);
        Runnable finish = () -> {
            state.finished.set(true);
            ManagedTask managed = state.task.get();
            if (managed != null) {
                managed.complete();
            }
        };
        Runnable execute = () -> {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            long start = System.currentTimeMillis();
            submitted.incrementAndGet();
            try {
                if (closed.get() || !ownerEnabled(safeOwner)) {
                    result.complete(EntityTaskOutcome.rejected());
                    return;
                }
                result.complete(EntityTaskOutcome.executed(operation.get()));
                completed.incrementAndGet();
            } catch (Throwable error) {
                failed.incrementAndGet();
                result.complete(EntityTaskOutcome.failed(error));
                logFailure(safeOwner, "entity-operation", error);
            } finally {
                warnSlow(safeOwner, "entity-operation", start);
                finish.run();
            }
        };
        Runnable retired = () -> {
            if (terminal.compareAndSet(false, true)) {
                submitted.incrementAndGet();
                result.complete(EntityTaskOutcome.retired());
                finish.run();
            }
        };
        state.cancelCallback.set(() -> {
            if (terminal.compareAndSet(false, true)) {
                result.complete(EntityTaskOutcome.rejected());
                finish.run();
            }
        });
        LeDatTask scheduled = scheduleTask(safeOwner, "entity-operation", false, state,
                () -> future(foliaLib.getScheduler().runAtEntityWithFallback(entity, consumer(execute), retired)));
        if (scheduled == LeDatTask.NOOP && terminal.compareAndSet(false, true)) {
            result.complete(EntityTaskOutcome.rejected());
            finish.run();
        }
        return result;
    }

    @Override
    public LeDatTask entityLater(Plugin owner, Entity entity, Runnable task, long delayTicks) {
        Plugin safeOwner = owner(owner);
        if (entity == null) {
            return LeDatTask.NOOP;
        }
        if (!canSchedule(safeOwner, "entity-later", task)) {
            return LeDatTask.NOOP;
        }
        long safeDelay = Math.max(1L, delayTicks);
        TaskState state = new TaskState();
        Runnable wrapped = oneShot(safeOwner, "entity-later", task, state);
        return scheduleTask(safeOwner, "entity-later", false, state,
                () -> reflective(foliaLib.getScheduler().runAtEntityLater(entity, wrapped, safeDelay)));
    }

    @Override
    public LeDatTask entityRepeat(Plugin owner, Entity entity, Runnable task, long delayTicks, long periodTicks) {
        Plugin safeOwner = owner(owner);
        if (entity == null) {
            return LeDatTask.NOOP;
        }
        if (!canSchedule(safeOwner, "entity-repeat", task)) {
            return LeDatTask.NOOP;
        }
        long safeDelay = Math.max(1L, delayTicks);
        long safePeriod = Math.max(1L, periodTicks);
        TaskState state = new TaskState();
        Runnable wrapped = repeating(safeOwner, "entity-repeat", task, state);
        return scheduleTask(safeOwner, "entity-repeat", true, state,
                () -> reflective(foliaLib.getScheduler().runAtEntityTimer(entity, wrapped, safeDelay, safePeriod)));
    }

    @Override
    public LeDatTask region(Plugin owner, Location location, Runnable task) {
        Plugin safeOwner = owner(owner);
        if (location == null || location.getWorld() == null) {
            return LeDatTask.NOOP;
        }
        if (!canSchedule(safeOwner, "region", task)) {
            return LeDatTask.NOOP;
        }
        TaskState state = new TaskState();
        Runnable wrapped = oneShot(safeOwner, "region", task, state);
        return scheduleTask(safeOwner, "region", false, state,
                () -> future(foliaLib.getScheduler().runAtLocation(location, consumer(wrapped))));
    }

    @Override
    public LeDatTask regionLater(Plugin owner, Location location, Runnable task, long delayTicks) {
        Plugin safeOwner = owner(owner);
        if (location == null || location.getWorld() == null) {
            return LeDatTask.NOOP;
        }
        if (!canSchedule(safeOwner, "region-later", task)) {
            return LeDatTask.NOOP;
        }
        long safeDelay = Math.max(1L, delayTicks);
        TaskState state = new TaskState();
        Runnable wrapped = oneShot(safeOwner, "region-later", task, state);
        return scheduleTask(safeOwner, "region-later", false, state,
                () -> reflective(foliaLib.getScheduler().runAtLocationLater(location, wrapped, safeDelay)));
    }

    @Override
    public LeDatTask regionRepeat(Plugin owner, Location location, Runnable task, long delayTicks, long periodTicks) {
        Plugin safeOwner = owner(owner);
        if (location == null || location.getWorld() == null) {
            return LeDatTask.NOOP;
        }
        if (!canSchedule(safeOwner, "region-repeat", task)) {
            return LeDatTask.NOOP;
        }
        long safeDelay = Math.max(1L, delayTicks);
        long safePeriod = Math.max(1L, periodTicks);
        TaskState state = new TaskState();
        Runnable wrapped = repeating(safeOwner, "region-repeat", task, state);
        return scheduleTask(safeOwner, "region-repeat", true, state,
                () -> reflective(foliaLib.getScheduler().runAtLocationTimer(location, wrapped, safeDelay, safePeriod)));
    }

    @Override
    public LeDatTask sender(Plugin owner, CommandSender sender, Runnable task) {
        if (sender instanceof Player player) {
            return entity(owner, player, task);
        }
        return sync(owner, task);
    }

    @Override
    public CompletableFuture<Boolean> teleportAsync(Plugin owner, Entity entity, Location location) {
        Plugin safeOwner = owner(owner);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (closed.get() || !ownerEnabled(safeOwner) || entity == null || location == null || location.getWorld() == null) {
            future.complete(false);
            return future;
        }
        try {
            foliaLib.getScheduler().teleportAsync(entity, location).whenComplete((success, error) -> {
                if (error != null) {
                    future.completeExceptionally(error);
                } else {
                    future.complete(Boolean.TRUE.equals(success));
                }
            });
        } catch (Throwable error) {
            logger.log(Level.WARNING, "FoliaLib teleportAsync fallback for " + safeOwner.getName(), error);
            return LeDatScheduler.super.teleportAsync(safeOwner, entity, location);
        }
        return future;
    }

    @Override
    public <T> CompletableFuture<T> supplyAsync(Plugin owner, String taskName, Supplier<T> supplier) {
        Plugin safeOwner = owner(owner);
        CompletableFuture<T> future = new CompletableFuture<>();
        String safeName = taskName(taskName, "supply-async");
        if (supplier == null) {
            future.completeExceptionally(new NullPointerException("supplier"));
            return future;
        }
        if (closed.get() || !ownerEnabled(safeOwner)) {
            future.completeExceptionally(new IllegalStateException("Scheduler is closed or owner is disabled: " + safeOwner.getName()));
            return future;
        }
        LeDatTask scheduled = async(safeOwner, safeName, () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        if (scheduled == LeDatTask.NOOP && !future.isDone()) {
            future.completeExceptionally(new IllegalStateException("Could not submit async scheduler task: " + safeOwner.getName() + "/" + safeName));
        }
        return future;
    }

    @Override
    public CompletableFuture<Void> runAsyncFuture(Plugin owner, String taskName, Runnable task) {
        if (task == null) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new NullPointerException("task"));
            return future;
        }
        return supplyAsync(owner, taskName, () -> {
            task.run();
            return null;
        });
    }

    @Override
    public void cancelAll(Plugin owner) {
        Plugin safeOwner = owner(owner);
        if (safeOwner.equals(platformPlugin)) {
            closed.set(true);
        }
        String key = ownerKey(safeOwner);
        ownerGenerations.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        Set<ManagedTask> tasks = tasksByOwner.remove(key);
        if (tasks != null) {
            for (ManagedTask task : tasks) {
                task.cancelQuietly();
            }
            tasks.clear();
        }
        String logPrefix = safeOwner.getName() + ":";
        failureLogTimes.keySet().removeIf(logKey -> logKey.startsWith(logPrefix));
        slowLogTimes.keySet().removeIf(logKey -> logKey.startsWith(logPrefix));
        if (safeOwner.equals(platformPlugin)) {
            try {
                foliaLib.getScheduler().cancelAllTasks();
            } catch (Throwable error) {
                logger.log(Level.WARNING, "FoliaLib cancelAllTasks failed", error);
            }
        }
    }

    @Override
    public int trackedTaskCount(Plugin owner) {
        Set<ManagedTask> tasks = tasksByOwner.get(ownerKey(owner(owner)));
        return tasks == null ? 0 : tasks.size();
    }

    @Override
    public Map<String, Integer> trackedTaskCounts() {
        Map<String, Integer> snapshot = new HashMap<>();
        tasksByOwner.forEach((owner, tasks) -> snapshot.put(owner, tasks.size()));
        return Map.copyOf(snapshot);
    }

    @Override
    public Map<String, LeDatScheduler.SchedulerOwnerStats> ownerStats() {
        Map<String, LeDatScheduler.SchedulerOwnerStats> snapshot = new HashMap<>();
        tasksByOwner.forEach((owner, tasks) -> {
            int total = tasks.size();
            int repeating = 0;
            int cancelledCount = 0;
            for (ManagedTask task : tasks) {
                if (task.repeating) {
                    repeating++;
                }
                if (task.cancelledFlag.get()) {
                    cancelledCount++;
                }
            }
            snapshot.put(owner, new LeDatScheduler.SchedulerOwnerStats(owner, total, repeating, Math.max(0, total - repeating), cancelledCount));
        });
        return Map.copyOf(snapshot);
    }

    @Override
    public long submittedTasks() {
        return submitted.get();
    }

    @Override
    public long completedTasks() {
        return completed.get();
    }

    @Override
    public long failedTasks() {
        return failed.get();
    }

    @Override
    public long cancelledTasks() {
        return cancelled.get();
    }

    @Override
    public long slowTasks() {
        return slow.get();
    }

    private LeDatTask wrapped(WrappedTask task) {
        return () -> {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        };
    }

    private LeDatTask future(CompletableFuture<?> future) {
        return () -> {
            if (future != null && !future.isDone()) {
                future.cancel(false);
            }
        };
    }

    private LeDatTask reflective(Object taskHandle) {
        if (taskHandle instanceof CompletableFuture<?> future) {
            return future(future);
        }
        if (taskHandle instanceof WrappedTask wrappedTask) {
            return wrapped(wrappedTask);
        }
        return ReflectionTask.of(taskHandle);
    }

    private LeDatTask scheduleTask(Plugin owner, String taskName, boolean repeating, TaskState state, Supplier<LeDatTask> scheduler) {
        if (closed.get() || !ownerEnabled(owner)) {
            requestCancel(state);
            return LeDatTask.NOOP;
        }
        String key = ownerKey(owner);
        long generation = ownerGenerations.computeIfAbsent(key, ignored -> new AtomicLong()).get();
        try {
            LeDatTask raw = scheduler.get();
            if (raw == null) {
                requestCancel(state);
                return LeDatTask.NOOP;
            }
            return track(owner, raw, repeating, state, generation);
        } catch (Throwable error) {
            requestCancel(state);
            logScheduleFailure(owner, taskName, error);
            return LeDatTask.NOOP;
        }
    }

    private ManagedTask track(Plugin owner, LeDatTask task, boolean repeating, TaskState state, long expectedGeneration) {
        ManagedTask managed = new ManagedTask(ownerKey(owner), task, repeating, state);
        AtomicLong generation = ownerGenerations.computeIfAbsent(managed.ownerKey, ignored -> new AtomicLong());
        if (generation.get() != expectedGeneration) {
            managed.cancelQuietly();
            return managed;
        }
        tasksByOwner.computeIfAbsent(managed.ownerKey, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(managed);
        if (generation.get() != expectedGeneration) {
            managed.cancelQuietly();
            return managed;
        }
        if (state != null) {
            state.task.set(managed);
            if (state.cancelRequested.get()) {
                managed.cancelQuietly();
            } else if (state.finished.get()) {
                managed.complete();
            }
        }
        return managed;
    }

    private Runnable oneShot(Plugin owner, String taskName, Runnable task, TaskState state) {
        return () -> {
            long start = System.currentTimeMillis();
            submitted.incrementAndGet();
            try {
                if (closed.get() || !ownerEnabled(owner)) {
                    return;
                }
                task.run();
                completed.incrementAndGet();
            } catch (Throwable error) {
                failed.incrementAndGet();
                logFailure(owner, taskName, error);
                if (shouldRethrowTaskErrors()) {
                    rethrow(error);
                }
            } finally {
                warnSlow(owner, taskName, start);
                state.finished.set(true);
                ManagedTask managed = state.task.get();
                if (managed != null) {
                    managed.complete();
                }
            }
        };
    }

    private Runnable repeating(Plugin owner, String taskName, Runnable task, TaskState state) {
        return () -> {
            long start = System.currentTimeMillis();
            submitted.incrementAndGet();
            try {
                if (closed.get() || !ownerEnabled(owner)) {
                    requestCancel(state);
                    return;
                }
                task.run();
                completed.incrementAndGet();
            } catch (Throwable error) {
                failed.incrementAndGet();
                logFailure(owner, taskName, error);
                if (shouldRethrowTaskErrors()) {
                    rethrow(error);
                }
            } finally {
                warnSlow(owner, taskName, start);
            }
        };
    }

    /**
     * Re-reads scheduler tuning values from the platform config. Config values only
     * change after {@code reloadConfig()}, so refreshing here (and from the platform
     * reload command) keeps observable behavior identical to reading live while
     * avoiding a YAML path lookup on every task execution.
     */
    public void reloadSettings() {
        var config = platformPlugin.getConfig();
        this.settings = new Settings(
                config.getBoolean("scheduler.task-log-errors", true),
                Math.max(0L, config.getLong("scheduler.task-error-log-interval-ms", 10000L)),
                config.getLong("scheduler.slow-task-warn-ms", config.getLong("scheduler.async-warning-ms", 250L)),
                Math.max(0L, config.getLong("scheduler.slow-task-log-interval-ms", 10000L)),
                config.getBoolean("scheduler.task-rethrow-errors", false));
    }

    private void logScheduleFailure(Plugin owner, String taskName, Throwable error) {
        String name = taskName(taskName, "unnamed");
        Settings current = settings;
        if (!current.logErrors()) {
            return;
        }
        String key = owner.getName() + ":" + name + ":submit";
        if (shouldLog(failureLogTimes, key, current.errorLogIntervalMs())) {
            logger.log(Level.WARNING, "Failed to submit scheduled task for " + owner.getName() + " [" + name + "]", error);
        }
    }

    private void logFailure(Plugin owner, String taskName, Throwable error) {
        String name = taskName == null || taskName.isBlank() ? "unnamed" : taskName;
        Settings current = settings;
        if (!current.logErrors()) {
            return;
        }
        String key = owner.getName() + ":" + name;
        if (!shouldLog(failureLogTimes, key, current.errorLogIntervalMs())) {
            return;
        }
        logger.log(Level.WARNING, "Scheduled task failed for " + owner.getName() + " [" + name + "]", error);
    }

    private void warnSlow(Plugin owner, String taskName, long start) {
        long took = System.currentTimeMillis() - start;
        Settings current = settings;
        long threshold = current.slowWarnMs();
        if (threshold > 0L && took >= threshold) {
            slow.incrementAndGet();
            String name = taskName == null || taskName.isBlank() ? "unnamed" : taskName;
            if (shouldLog(slowLogTimes, owner.getName() + ":" + name, current.slowLogIntervalMs())) {
                logger.warning("Slow scheduled task for " + owner.getName() + " [" + name + "]: " + took + "ms via FoliaLib/" + implementationName);
            }
        }
    }

    private boolean shouldLog(Map<String, AtomicLong> logTimes, String key, long intervalMs) {
        if (intervalMs <= 0L) {
            return true;
        }
        if (logTimes.size() > LOG_THROTTLE_MAP_CAP) {
            // Dynamic task names (per-player suffixes etc.) must not grow this map
            // without bound; resetting only relaxes throttling briefly.
            logTimes.clear();
        }
        long now = System.currentTimeMillis();
        AtomicLong holder = logTimes.computeIfAbsent(key, ignored -> new AtomicLong());
        long previous = holder.get();
        return now - previous >= intervalMs && holder.compareAndSet(previous, now);
    }

    private void rethrow(Throwable error) {
        if (error instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (error instanceof Error fatal) {
            throw fatal;
        }
        throw new RuntimeException(error);
    }

    private Consumer<WrappedTask> consumer(Runnable task) {
        return ignored -> task.run();
    }

    private boolean canSchedule(Plugin owner, String taskName, Runnable task) {
        if (task == null) {
            String name = taskName(taskName, "unnamed");
            if (shouldLog(failureLogTimes, owner.getName() + ":" + name + ":null", settings.errorLogIntervalMs())) {
                logger.warning("Ignored null scheduled task for " + owner.getName() + " [" + name + "]");
            }
            return false;
        }
        return !closed.get() && ownerEnabled(owner);
    }

    private boolean ownerEnabled(Plugin owner) {
        try {
            return owner != null && owner.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean shouldRethrowTaskErrors() {
        return settings.rethrowErrors();
    }

    private String taskName(String taskName, String fallback) {
        return taskName == null || taskName.isBlank() ? fallback : taskName;
    }

    private void requestCancel(TaskState state) {
        if (state == null) {
            return;
        }
        state.cancelRequested.set(true);
        notifyCancelled(state);
        ManagedTask managed = state.task.get();
        if (managed != null) {
            managed.cancelQuietly();
        }
    }

    private void notifyCancelled(TaskState state) {
        if (state == null) {
            return;
        }
        Runnable callback = state.cancelCallback.getAndSet(null);
        if (callback != null) {
            callback.run();
        }
    }

    private Plugin owner(Plugin owner) {
        return owner == null ? platformPlugin : owner;
    }

    private String ownerKey(Plugin owner) {
        return owner == null ? platformPlugin.getName() : owner.getName();
    }

    private record Settings(boolean logErrors, long errorLogIntervalMs, long slowWarnMs,
                            long slowLogIntervalMs, boolean rethrowErrors) {
    }

    private final class TaskState {
        private final AtomicReference<ManagedTask> task = new AtomicReference<>();
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private final AtomicReference<Runnable> cancelCallback = new AtomicReference<>();
    }

    private final class ManagedTask implements LeDatTask {
        private final String ownerKey;
        private final LeDatTask delegate;
        private final boolean repeating;
        private final TaskState state;
        private final AtomicBoolean cancelledFlag = new AtomicBoolean(false);

        private ManagedTask(String ownerKey, LeDatTask delegate, boolean repeating, TaskState state) {
            this.ownerKey = ownerKey;
            this.delegate = delegate;
            this.repeating = repeating;
            this.state = state;
        }

        @Override
        public void cancel() {
            if (!cancelledFlag.compareAndSet(false, true)) {
                return;
            }
            cancelled.incrementAndGet();
            try {
                delegate.cancel();
            } finally {
                notifyCancelled(state);
                remove();
            }
        }

        private void complete() {
            if (!repeating) {
                remove();
            }
        }

        private void remove() {
            Set<ManagedTask> tasks = tasksByOwner.get(ownerKey);
            if (tasks != null) {
                tasks.remove(this);
                if (tasks.isEmpty()) {
                    tasksByOwner.remove(ownerKey, tasks);
                }
            }
        }
    }
}
