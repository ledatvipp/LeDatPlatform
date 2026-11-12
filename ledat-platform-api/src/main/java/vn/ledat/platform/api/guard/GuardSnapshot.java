package vn.ledat.platform.api.guard;

import java.time.Instant;

/**
 * Read-only production guard counters for doctor/admin diagnostics.
 */
public record GuardSnapshot(
        boolean productionProfileEnabled,
        boolean primaryThread,
        boolean mainThreadDbRejectEnabled,
        long guardedOperations,
        long guardedFailures,
        long slowCommandHandlers,
        int managedExecutorOwners,
        int managedExecutorCount,
        int activeManagedExecutorThreads,
        int queuedManagedExecutorTasks,
        int unregisteredLeDatThreads,
        int schedulerOwners,
        int trackedSchedulerTasks,
        int disabledPluginTaskOwners,
        int disabledPluginTrackedTasks,
        Instant createdAt
) {
    public GuardSnapshot {
        guardedOperations = Math.max(0L, guardedOperations);
        guardedFailures = Math.max(0L, guardedFailures);
        slowCommandHandlers = Math.max(0L, slowCommandHandlers);
        managedExecutorOwners = Math.max(0, managedExecutorOwners);
        managedExecutorCount = Math.max(0, managedExecutorCount);
        activeManagedExecutorThreads = Math.max(0, activeManagedExecutorThreads);
        queuedManagedExecutorTasks = Math.max(0, queuedManagedExecutorTasks);
        unregisteredLeDatThreads = Math.max(0, unregisteredLeDatThreads);
        schedulerOwners = Math.max(0, schedulerOwners);
        trackedSchedulerTasks = Math.max(0, trackedSchedulerTasks);
        disabledPluginTaskOwners = Math.max(0, disabledPluginTaskOwners);
        disabledPluginTrackedTasks = Math.max(0, disabledPluginTrackedTasks);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
