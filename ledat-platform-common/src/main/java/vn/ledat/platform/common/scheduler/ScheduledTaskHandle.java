package vn.ledat.platform.common.scheduler;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small wrapper around scheduler task handles.
 *
 * <p>All handles returned by ledat-platform-common are expected to be idempotent:
 * calling {@link #cancel()} multiple times must not throw and must not cancel more
 * than once. Static helpers are provided so migrated plugins can safely keep
 * handles in maps/lists and cancel them during reload/disable.</p>
 */
@FunctionalInterface
public interface ScheduledTaskHandle {
    ScheduledTaskHandle NOOP = () -> { };

    void cancel();

    default void cancelQuietly(Logger logger, String taskName) {
        try {
            cancel();
        } catch (Throwable error) {
            if (logger != null) {
                logger.log(Level.WARNING, "Failed to cancel task " + (taskName == null ? "unknown" : taskName), error);
            }
        }
    }

    static ScheduledTaskHandle idempotent(Runnable cancelAction) {
        return named("anonymous", cancelAction);
    }

    static ScheduledTaskHandle named(String name, Runnable cancelAction) {
        if (cancelAction == null) {
            return NOOP;
        }
        AtomicBoolean cancelled = new AtomicBoolean(false);
        return () -> {
            if (cancelled.compareAndSet(false, true)) {
                cancelAction.run();
            }
        };
    }

    static void cancelAll(Collection<? extends ScheduledTaskHandle> handles) {
        cancelAll(handles, null, "batch");
    }

    static void cancelAll(Collection<? extends ScheduledTaskHandle> handles, Logger logger, String taskName) {
        if (handles == null || handles.isEmpty()) {
            return;
        }
        for (ScheduledTaskHandle handle : handles) {
            if (handle != null) {
                handle.cancelQuietly(logger, taskName);
            }
        }
    }
}
