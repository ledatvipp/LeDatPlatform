package vn.ledat.platform.common.scheduler;

import com.tcoded.folialib.FoliaLib;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * FoliaLib-backed scheduler facade.
 * This class is intended to be shaded/relocated inside each plugin jar.
 *
 * <p>Production behaviour:</p>
 * <ul>
 *     <li>all tasks are tracked and cancelled on disable/reload;</li>
 *     <li>task exceptions are logged with plugin context instead of silently killing loops;</li>
 *     <li>task handles are idempotent and safe to cancel repeatedly;</li>
 *     <li>null entity/location or disabled plugin returns a NOOP handle instead of throwing.</li>
 * </ul>
 */
public final class FoliaLibSchedulerFacade implements SchedulerFacade {
    private final Plugin plugin;
    private final FoliaLib foliaLib;
    private final Set<TrackedTaskHandle> trackedTasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public FoliaLibSchedulerFacade(Plugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin cannot be null");
        }
        this.plugin = plugin;
        this.foliaLib = new FoliaLib(plugin);
    }

    @Override
    public ScheduledTaskHandle runAsync(Runnable task) {
        return schedule("async", false, task, safeTask -> foliaLib.getScheduler().runAsync(ignored -> safeTask.run()));
    }

    @Override
    public ScheduledTaskHandle runAsyncLater(Runnable task, long delayTicks) {
        return schedule("async-later", false, task,
                safeTask -> foliaLib.getScheduler().runLaterAsync(safeTask, sanitizeDelay(delayTicks)));
    }

    @Override
    public ScheduledTaskHandle runAsyncRepeating(Runnable task, long delayTicks, long periodTicks) {
        return schedule("async-repeating", true, task,
                safeTask -> foliaLib.getScheduler().runTimerAsync(safeTask, sanitizeDelay(delayTicks), sanitizePeriod(periodTicks)));
    }

    @Override
    public ScheduledTaskHandle runGlobal(Runnable task) {
        return schedule("global", false, task, safeTask -> foliaLib.getScheduler().runNextTick(ignored -> safeTask.run()));
    }

    @Override
    public ScheduledTaskHandle runGlobalLater(Runnable task, long delayTicks) {
        return schedule("global-later", false, task,
                safeTask -> foliaLib.getScheduler().runLater(safeTask, sanitizeDelay(delayTicks)));
    }

    @Override
    public ScheduledTaskHandle runGlobalRepeating(Runnable task, long delayTicks, long periodTicks) {
        return schedule("global-repeating", true, task,
                safeTask -> foliaLib.getScheduler().runTimer(safeTask, sanitizeDelay(delayTicks), sanitizePeriod(periodTicks)));
    }

    @Override
    public ScheduledTaskHandle runEntity(Entity entity, Runnable task) {
        if (!validEntity(entity)) {
            return ScheduledTaskHandle.NOOP;
        }
        return schedule("entity", false, task, safeTask -> foliaLib.getScheduler().runAtEntity(entity, ignored -> safeTask.run()));
    }

    @Override
    public ScheduledTaskHandle runEntityLater(Entity entity, Runnable task, long delayTicks) {
        if (!validEntity(entity)) {
            return ScheduledTaskHandle.NOOP;
        }
        return schedule("entity-later", false, task,
                safeTask -> foliaLib.getScheduler().runAtEntityLater(entity, safeTask, sanitizeDelay(delayTicks)));
    }

    @Override
    public ScheduledTaskHandle runEntityRepeating(Entity entity, Runnable task, long delayTicks, long periodTicks) {
        if (!validEntity(entity)) {
            return ScheduledTaskHandle.NOOP;
        }
        return schedule("entity-repeating", true, task,
                safeTask -> foliaLib.getScheduler().runAtEntityTimer(entity, safeTask, sanitizeDelay(delayTicks), sanitizePeriod(periodTicks)));
    }

    @Override
    public ScheduledTaskHandle runAtLocation(Location location, Runnable task) {
        if (!validLocation(location)) {
            return ScheduledTaskHandle.NOOP;
        }
        return schedule("location", false, task, safeTask -> foliaLib.getScheduler().runAtLocation(location, ignored -> safeTask.run()));
    }

    @Override
    public ScheduledTaskHandle runAtLocationLater(Location location, Runnable task, long delayTicks) {
        if (!validLocation(location)) {
            return ScheduledTaskHandle.NOOP;
        }
        return schedule("location-later", false, task,
                safeTask -> foliaLib.getScheduler().runAtLocationLater(location, safeTask, sanitizeDelay(delayTicks)));
    }

    @Override
    public ScheduledTaskHandle runAtLocationRepeating(Location location, Runnable task, long delayTicks, long periodTicks) {
        if (!validLocation(location)) {
            return ScheduledTaskHandle.NOOP;
        }
        return schedule("location-repeating", true, task,
                safeTask -> foliaLib.getScheduler().runAtLocationTimer(location, safeTask, sanitizeDelay(delayTicks), sanitizePeriod(periodTicks)));
    }

    @Override
    public CompletableFuture<Boolean> teleportAsync(Entity entity, Location location) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (closed.get() || !plugin.isEnabled() || entity == null || location == null) {
            future.complete(false);
            return future;
        }
        try {
            foliaLib.getScheduler().teleportAsync(entity, location).thenAccept(future::complete).exceptionally(error -> {
                future.completeExceptionally(error);
                return null;
            });
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    @Override
    public void cancelAll() {
        closed.set(true);
        for (TrackedTaskHandle handle : Set.copyOf(trackedTasks)) {
            handle.cancelQuietly();
        }
        trackedTasks.clear();
        try {
            foliaLib.getScheduler().cancelAllTasks();
        } catch (Throwable error) {
            plugin.getLogger().log(Level.WARNING, "Failed to cancel FoliaLib tasks for " + plugin.getName(), error);
        }
    }

    private ScheduledTaskHandle schedule(String taskName, boolean repeating, Runnable task, TaskScheduler scheduler) {
        if (task == null || closed.get() || !plugin.isEnabled()) {
            return ScheduledTaskHandle.NOOP;
        }

        AtomicReference<TrackedTaskHandle> handleRef = new AtomicReference<>();
        Runnable safeTask = () -> {
            if (closed.get() || !plugin.isEnabled()) {
                TrackedTaskHandle handle = handleRef.get();
                if (handle != null) {
                    handle.cancelQuietly();
                }
                return;
            }
            try {
                task.run();
            } catch (Throwable error) {
                plugin.getLogger().log(Level.SEVERE, "Unhandled exception in scheduled task '" + taskName + "'", error);
            } finally {
                if (!repeating) {
                    TrackedTaskHandle handle = handleRef.get();
                    if (handle != null) {
                        trackedTasks.remove(handle);
                    }
                }
            }
        };

        try {
            Object rawHandle = scheduler.schedule(safeTask);
            if (rawHandle == null) {
                return ScheduledTaskHandle.NOOP;
            }
            TrackedTaskHandle handle = new TrackedTaskHandle(taskName, rawHandle);
            handleRef.set(handle);
            trackedTasks.add(handle);
            return handle;
        } catch (Throwable error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to schedule task '" + taskName + "'", error);
            return ScheduledTaskHandle.NOOP;
        }
    }

    private boolean validEntity(Entity entity) {
        return entity != null && !closed.get() && plugin.isEnabled();
    }

    private boolean validLocation(Location location) {
        return location != null && location.getWorld() != null && !closed.get() && plugin.isEnabled();
    }

    private static long sanitizeDelay(long delayTicks) {
        return Math.max(1L, delayTicks);
    }

    private static long sanitizePeriod(long periodTicks) {
        return Math.max(1L, periodTicks);
    }

    private static void invokeCancel(Object taskHandle) throws ReflectiveOperationException {
        if (taskHandle instanceof Future<?> future) {
            future.cancel(false);
            return;
        }
        Method cancel = taskHandle.getClass().getMethod("cancel");
        cancel.invoke(taskHandle);
    }

    @FunctionalInterface
    private interface TaskScheduler {
        Object schedule(Runnable safeTask) throws Throwable;
    }

    private final class TrackedTaskHandle implements ScheduledTaskHandle {
        private final String name;
        private final Object rawHandle;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private TrackedTaskHandle(String name, Object rawHandle) {
            this.name = name;
            this.rawHandle = rawHandle;
        }

        @Override
        public void cancel() {
            cancelQuietly();
        }

        private void cancelQuietly() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            trackedTasks.remove(this);
            try {
                invokeCancel(rawHandle);
            } catch (ReflectiveOperationException error) {
                plugin.getLogger().log(Level.FINE, "Scheduler handle for task '" + name + "' does not expose cancel()", error);
            } catch (Throwable error) {
                plugin.getLogger().log(Level.WARNING, "Failed to cancel task '" + name + "'", error);
            }
        }
    }
}
