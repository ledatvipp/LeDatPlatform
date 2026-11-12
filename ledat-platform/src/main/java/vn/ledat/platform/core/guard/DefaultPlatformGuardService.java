package vn.ledat.platform.core.guard;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.LeDatPlatformApi;
import vn.ledat.platform.api.executor.ManagedExecutorService;
import vn.ledat.platform.api.guard.GuardSnapshot;
import vn.ledat.platform.api.guard.PlatformGuardService;
import vn.ledat.platform.api.scheduler.LeDatScheduler;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class DefaultPlatformGuardService implements PlatformGuardService {
    private final Plugin platformPlugin;
    private final Supplier<LeDatPlatformApi> apiSupplier;
    private final AtomicLong guardedOperations = new AtomicLong();
    private final AtomicLong guardedFailures = new AtomicLong();
    private final AtomicLong lastRawThreadWarnAt = new AtomicLong();
    private final AtomicLong lastSchedulerLeakWarnAt = new AtomicLong();
    private final AtomicLong lastDbGuardWarnAt = new AtomicLong();
    private final AtomicLong lastSlowCommandCount = new AtomicLong();

    public DefaultPlatformGuardService(Plugin platformPlugin, Supplier<LeDatPlatformApi> apiSupplier) {
        this.platformPlugin = Objects.requireNonNull(platformPlugin, "platformPlugin");
        this.apiSupplier = Objects.requireNonNull(apiSupplier, "apiSupplier");
    }

    @Override
    public boolean productionProfileEnabled() {
        return platformPlugin.getConfig().getBoolean("production-profile.enabled", false);
    }

    @Override
    public boolean checkMainThread() {
        LeDatPlatformApi api = apiSupplier.get();
        if (api != null) {
            return api.scheduler().isPrimaryThread();
        }
        try {
            return Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean checkAsyncThread() {
        return !checkMainThread();
    }

    @Override
    public <T> T runGuarded(Plugin plugin, String operation, Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        guardedOperations.incrementAndGet();
        Plugin owner = plugin == null ? platformPlugin : plugin;
        try {
            return supplier.get();
        } catch (Throwable error) {
            guardedFailures.incrementAndGet();
            owner.getLogger().log(Level.WARNING, "Guarded operation failed: " + safeOperation(operation), error);
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (error instanceof Error fatal) {
                throw fatal;
            }
            throw new RuntimeException(error);
        }
    }

    @Override
    public GuardSnapshot snapshot() {
        LeDatPlatformApi api = apiSupplier.get();
        boolean primaryThread = checkMainThread();
        boolean dbReject = mainThreadDbRejectEnabled();
        long slowCommands = api == null ? 0L : api.commands().stats().slowHandlers();

        int managedOwners = 0;
        int managedCount = 0;
        int activeManagedThreads = 0;
        int queuedManagedTasks = 0;
        int unregisteredThreads = 0;
        if (api != null) {
            Map<String, ManagedExecutorService.ExecutorStats> executorStats = api.executors().stats();
            managedCount = executorStats.size();
            managedOwners = (int) executorStats.values().stream()
                    .map(ManagedExecutorService.ExecutorStats::pluginName)
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .distinct()
                    .count();
            activeManagedThreads = executorStats.values().stream().mapToInt(ManagedExecutorService.ExecutorStats::activeThreads).sum();
            queuedManagedTasks = executorStats.values().stream().mapToInt(ManagedExecutorService.ExecutorStats::queuedTasks).sum();
            unregisteredThreads = api.executors().unregisteredLeDatThreadNames().size();
        }

        int schedulerOwners = 0;
        int trackedSchedulerTasks = 0;
        int disabledOwners = 0;
        int disabledTasks = 0;
        if (api != null) {
            Map<String, LeDatScheduler.SchedulerOwnerStats> ownerStats = api.scheduler().ownerStats();
            schedulerOwners = ownerStats.size();
            trackedSchedulerTasks = ownerStats.values().stream().mapToInt(LeDatScheduler.SchedulerOwnerStats::trackedTasks).sum();
            for (LeDatScheduler.SchedulerOwnerStats stats : ownerStats.values()) {
                Plugin owner = Bukkit.getPluginManager().getPlugin(stats.pluginName());
                if (owner != null && !owner.isEnabled() && stats.trackedTasks() > 0) {
                    disabledOwners++;
                    disabledTasks += stats.trackedTasks();
                }
            }
        }

        return new GuardSnapshot(
                productionProfileEnabled(),
                primaryThread,
                dbReject,
                guardedOperations.get(),
                guardedFailures.get(),
                slowCommands,
                managedOwners,
                managedCount,
                activeManagedThreads,
                queuedManagedTasks,
                unregisteredThreads,
                schedulerOwners,
                trackedSchedulerTasks,
                disabledOwners,
                disabledTasks,
                Instant.now()
        );
    }

    public void warnProductionRisks() {
        if (!productionProfileEnabled()) {
            return;
        }
        GuardSnapshot snapshot = snapshot();
        if (!snapshot.mainThreadDbRejectEnabled()) {
            warnEvery(lastDbGuardWarnAt, "Production profile is enabled but main-thread DB borrow rejection is off.");
        }
        long slowCommands = snapshot.slowCommandHandlers();
        long previousSlow = lastSlowCommandCount.getAndSet(slowCommands);
        if (slowCommands > previousSlow) {
            platformPlugin.getLogger().warning("Production guard: slow command handlers increased from " + previousSlow + " to " + slowCommands + ".");
        }
        if (platformPlugin.getConfig().getBoolean("production-profile.executors.warn-unregistered-ledat-threads", true)
                && snapshot.unregisteredLeDatThreads() > 0) {
            warnEvery(lastRawThreadWarnAt, "Production guard: found " + snapshot.unregisteredLeDatThreads() + " LeDat-named thread(s) not registered with ManagedExecutorService.");
        }
        if (platformPlugin.getConfig().getBoolean("production-profile.scheduler.warn-disabled-owner-tasks", true)
                && snapshot.disabledPluginTaskOwners() > 0) {
            warnEvery(lastSchedulerLeakWarnAt, "Production guard: " + snapshot.disabledPluginTrackedTasks() + " tracked scheduler task(s) remain for disabled plugin owner(s).");
        }
    }

    private boolean mainThreadDbRejectEnabled() {
        return platformPlugin.getConfig().getBoolean("storage.safety.reject-main-thread-borrows", false)
                || (productionProfileEnabled()
                && platformPlugin.getConfig().getBoolean("production-profile.database.reject-main-thread-borrows", true));
    }

    private void warnEvery(AtomicLong gate, String message) {
        long now = System.currentTimeMillis();
        long previous = gate.get();
        long interval = Math.max(1000L, platformPlugin.getConfig().getLong("production-profile.warning-interval-ms", 60000L));
        if (now - previous >= interval && gate.compareAndSet(previous, now)) {
            platformPlugin.getLogger().warning(message);
        }
    }

    private String safeOperation(String operation) {
        return operation == null || operation.isBlank() ? "operation" : operation.replaceAll("[^A-Za-z0-9_.:-]+", "_");
    }
}
