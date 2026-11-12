package vn.ledat.platform.api.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface LeDatScheduler {
    boolean isFolia();
    boolean isPrimaryThread();

    /**
     * Runtime scheduler backend name, for example FOLIA, PAPER, SPIGOT or UNKNOWN.
     */
    default String implementationName() {
        return isFolia() ? "FOLIA" : "PAPER";
    }

    /**
     * True when the current thread owns the region for the location. On Paper this is the main thread check.
     */
    default boolean isOwnedByCurrentRegion(Location location) {
        return isPrimaryThread();
    }

    /**
     * True when the current thread owns the entity region. On Paper this is the main thread check.
     */
    default boolean isOwnedByCurrentRegion(Entity entity) {
        return isPrimaryThread();
    }

    LeDatTask sync(Plugin owner, Runnable task);
    LeDatTask async(Plugin owner, String taskName, Runnable task);
    LeDatTask later(Plugin owner, Runnable task, long delayTicks);
    LeDatTask asyncLater(Plugin owner, String taskName, Runnable task, Duration delay);
    LeDatTask repeat(Plugin owner, Runnable task, long delayTicks, long periodTicks);
    LeDatTask asyncRepeat(Plugin owner, String taskName, Runnable task, Duration delay, Duration period);
    LeDatTask entity(Plugin owner, Entity entity, Runnable task);

    /**
     * Run an operation only while the entity scheduler still owns a live entity.
     * Retirement is a terminal outcome and must never execute the operation body as a fallback.
     */
    default <T> CompletableFuture<EntityTaskOutcome<T>> entityOperation(
            Plugin owner, Entity entity, Supplier<T> operation) {
        if (owner == null || entity == null || operation == null || !owner.isEnabled()) {
            return CompletableFuture.completedFuture(EntityTaskOutcome.rejected());
        }
        CompletableFuture<EntityTaskOutcome<T>> result = new CompletableFuture<>();
        LeDatTask scheduled = entity(owner, entity, () -> {
            try {
                result.complete(EntityTaskOutcome.executed(operation.get()));
            } catch (Throwable error) {
                result.complete(EntityTaskOutcome.failed(error));
            }
        });
        if (scheduled == LeDatTask.NOOP && !result.isDone()) {
            result.complete(EntityTaskOutcome.rejected());
        }
        return result;
    }

    /**
     * Run a delayed task on the owning entity region when the scheduler backend supports it.
     * Implementations may fall back to a delayed global hop followed by {@link #entity(Plugin, Entity, Runnable)}.
     */
    default LeDatTask entityLater(Plugin owner, Entity entity, Runnable task, long delayTicks) {
        return later(owner, () -> entity(owner, entity, task), Math.max(1L, delayTicks));
    }

    /**
     * Run a repeating task on the owning entity region when the scheduler backend supports it.
     * Implementations may fall back to a global timer that hops into the entity region each tick.
     */
    default LeDatTask entityRepeat(Plugin owner, Entity entity, Runnable task, long delayTicks, long periodTicks) {
        return repeat(owner, () -> entity(owner, entity, task), Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    LeDatTask region(Plugin owner, Location location, Runnable task);

    /**
     * Run a delayed task on the owning location/region when the scheduler backend supports it.
     * Implementations may fall back to a delayed global hop followed by {@link #region(Plugin, Location, Runnable)}.
     */
    default LeDatTask regionLater(Plugin owner, Location location, Runnable task, long delayTicks) {
        return later(owner, () -> region(owner, location, task), Math.max(1L, delayTicks));
    }

    /**
     * Run a repeating task on the owning location/region when the scheduler backend supports it.
     * Implementations may fall back to a global timer that hops into the location region each tick.
     */
    default LeDatTask regionRepeat(Plugin owner, Location location, Runnable task, long delayTicks, long periodTicks) {
        return repeat(owner, () -> region(owner, location, task), Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    LeDatTask sender(Plugin owner, CommandSender sender, Runnable task);

    /**
     * Cross-platform teleport wrapper. On Folia-aware backends this should route through the
     * scheduler implementation instead of directly calling Bukkit/Paper teleport APIs.
     */
    default CompletableFuture<Boolean> teleportAsync(Plugin owner, Entity entity, Location location) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (entity == null || location == null) {
            future.complete(false);
            return future;
        }
        entity(owner, entity, () -> {
            try {
                future.complete(entity.teleport(location));
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    <T> CompletableFuture<T> supplyAsync(Plugin owner, String taskName, Supplier<T> supplier);
    CompletableFuture<Void> runAsyncFuture(Plugin owner, String taskName, Runnable task);

    void cancelAll(Plugin owner);


    /**
     * Create a plugin-scoped scheduler adapter. This is intentionally small and
     * only delegates to the platform scheduler while carrying the plugin owner.
     * It helps migrated plugins avoid direct Bukkit/Folia scheduler calls.
     */
    default ScopedScheduler scoped(Plugin owner) {
        return new ScopedScheduler(this, owner);
    }

    final class ScopedScheduler {
        private final LeDatScheduler scheduler;
        private final Plugin owner;

        public ScopedScheduler(LeDatScheduler scheduler, Plugin owner) {
            this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        public Plugin owner() {
            return owner;
        }

        public boolean isFolia() {
            return scheduler.isFolia();
        }

        public boolean isPrimaryThread() {
            return scheduler.isPrimaryThread();
        }

        public String implementationName() {
            return scheduler.implementationName();
        }

        public LeDatTask runGlobal(Runnable task) {
            return scheduler.sync(owner, task);
        }

        public LeDatTask runGlobalLater(Runnable task, long delayTicks) {
            return scheduler.later(owner, task, Math.max(1L, delayTicks));
        }

        public LeDatTask runGlobalRepeating(Runnable task, long delayTicks, long periodTicks) {
            return scheduler.repeat(owner, task, Math.max(1L, delayTicks), Math.max(1L, periodTicks));
        }

        public LeDatTask runAsync(String taskName, Runnable task) {
            return scheduler.async(owner, taskName == null ? "async" : taskName, task);
        }

        public LeDatTask runAsync(Runnable task) {
            return runAsync("async", task);
        }

        public LeDatTask runAsyncLater(String taskName, Runnable task, long delayTicks) {
            return scheduler.asyncLater(owner, taskName == null ? "async-later" : taskName, task, Duration.ofMillis(Math.max(1L, delayTicks) * 50L));
        }

        public LeDatTask runAsyncLater(Runnable task, long delayTicks) {
            return runAsyncLater("async-later", task, delayTicks);
        }

        public LeDatTask runAsyncRepeating(String taskName, Runnable task, long delayTicks, long periodTicks) {
            return scheduler.asyncRepeat(owner, taskName == null ? "async-repeat" : taskName, task,
                    Duration.ofMillis(Math.max(1L, delayTicks) * 50L),
                    Duration.ofMillis(Math.max(1L, periodTicks) * 50L));
        }

        public LeDatTask runAsyncRepeating(Runnable task, long delayTicks, long periodTicks) {
            return runAsyncRepeating("async-repeat", task, delayTicks, periodTicks);
        }

        public LeDatTask runEntity(Entity entity, Runnable task) {
            return scheduler.entity(owner, entity, task);
        }

        public <T> CompletableFuture<EntityTaskOutcome<T>> runEntityOperation(Entity entity, Supplier<T> operation) {
            return scheduler.entityOperation(owner, entity, operation);
        }

        public LeDatTask runEntityLater(Entity entity, Runnable task, long delayTicks) {
            return scheduler.entityLater(owner, entity, task, Math.max(1L, delayTicks));
        }

        /**
         * Entity-safe repeating task. On Folia this routes each tick back to the
         * entity region; on Paper it is a normal repeating task.
         */
        public LeDatTask runEntityRepeating(Entity entity, Runnable task, long delayTicks, long periodTicks) {
            return scheduler.entityRepeat(owner, entity, task, Math.max(1L, delayTicks), Math.max(1L, periodTicks));
        }

        public LeDatTask runPlayer(UUID playerId, Runnable task) {
            if (playerId == null || task == null) {
                return LeDatTask.NOOP;
            }
            return runOnlinePlayer(playerId, task);
        }

        public LeDatTask runPlayer(UUID playerId, Consumer<Player> task) {
            if (playerId == null || task == null) {
                return LeDatTask.NOOP;
            }
            return runOnlinePlayer(playerId, task);
        }

        /**
         * Resolve an online player from the platform scheduler first, then run
         * the callback on that player's entity scheduler. Prefer this when the
         * caller may be asynchronous or otherwise outside a known scheduler
         * scope.
         */
        public LeDatTask runOnlinePlayer(UUID playerId, Runnable task) {
            if (playerId == null || task == null) {
                return LeDatTask.NOOP;
            }
            return runOnlinePlayer(playerId, player -> task.run());
        }

        /**
         * Resolve an online player from the platform scheduler first, then run
         * the callback on that player's entity scheduler. Prefer this when the
         * caller may be asynchronous or otherwise outside a known scheduler
         * scope.
         */
        public LeDatTask runOnlinePlayer(UUID playerId, Consumer<Player> task) {
            if (playerId == null || task == null) {
                return LeDatTask.NOOP;
            }
            return runGlobal(() -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) {
                    return;
                }
                runEntityOperation(player, () -> {
                    if (player.isOnline()) {
                        task.accept(player);
                    }
                    return null;
                });
            });
        }

        public LeDatTask runAtLocation(Location location, Runnable task) {
            return scheduler.region(owner, location, task);
        }

        public LeDatTask runAtLocationLater(Location location, Runnable task, long delayTicks) {
            return scheduler.regionLater(owner, location, task, Math.max(1L, delayTicks));
        }

        public LeDatTask runAtLocationRepeating(Location location, Runnable task, long delayTicks, long periodTicks) {
            return scheduler.regionRepeat(owner, location, task, Math.max(1L, delayTicks), Math.max(1L, periodTicks));
        }

        public LeDatTask runSender(CommandSender sender, Runnable task) {
            return scheduler.sender(owner, sender, task);
        }

        public CompletableFuture<Boolean> teleportAsync(Entity entity, Location location) {
            return scheduler.teleportAsync(owner, entity, location);
        }

        public CompletableFuture<Boolean> teleportAsync(Player player, Location location) {
            return teleportAsync((Entity) player, location);
        }

        public void cancelAll() {
            scheduler.cancelAll(owner);
        }
    }

    /**
     * Number of tracked active scheduled tasks owned by this plugin.
     * Implementations may return 0 when task tracking is not available.
     */
    default int trackedTaskCount(Plugin owner) {
        return 0;
    }

    /**
     * Snapshot of active tracked task counts by plugin name.
     */
    default Map<String, Integer> trackedTaskCounts() {
        return Map.of();
    }

    /**
     * Runtime submitted task counter from the scheduler backend.
     * Implementations may return 0 when metrics are unavailable.
     */
    default long submittedTasks() {
        return 0L;
    }

    /**
     * Runtime completed task counter from the scheduler backend.
     */
    default long completedTasks() {
        return 0L;
    }

    /**
     * Runtime failed task counter from the scheduler backend.
     */
    default long failedTasks() {
        return 0L;
    }

    /**
     * Runtime cancelled task counter from the scheduler backend.
     */
    default long cancelledTasks() {
        return 0L;
    }

    /**
     * Runtime slow-task warning counter from the scheduler backend.
     */
    default long slowTasks() {
        return 0L;
    }

    /**
     * Per-plugin tracked task snapshot for doctor/runtime diagnostics.
     */
    default Map<String, SchedulerOwnerStats> ownerStats() {
        return Map.of();
    }

    /**
     * Read-only per-plugin task counters.
     */
    record SchedulerOwnerStats(
            String pluginName,
            int trackedTasks,
            int repeatingTasks,
            int oneShotTasks,
            int cancelledTasks
    ) {
        public SchedulerOwnerStats {
            pluginName = pluginName == null || pluginName.isBlank() ? "unknown" : pluginName;
            trackedTasks = Math.max(0, trackedTasks);
            repeatingTasks = Math.max(0, repeatingTasks);
            oneShotTasks = Math.max(0, oneShotTasks);
            cancelledTasks = Math.max(0, cancelledTasks);
        }
    }

}
