package vn.ledat.platform.api.guard;

import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

public interface PlatformGuardService {
    boolean productionProfileEnabled();

    /**
     * Returns true when called from the Bukkit/Paper primary thread or Folia global tick thread.
     */
    boolean checkMainThread();

    /**
     * Returns true when called outside the primary/global tick thread.
     */
    boolean checkAsyncThread();

    <T> T runGuarded(Plugin plugin, String operation, Supplier<T> supplier);

    default void runGuarded(Plugin plugin, String operation, Runnable runnable) {
        runGuarded(plugin, operation, () -> {
            runnable.run();
            return null;
        });
    }

    default GuardSnapshot snapshot() {
        return new GuardSnapshot(false, false, false, 0L, 0L, 0L, 0, 0, 0, 0, 0, 0, 0, 0, 0, java.time.Instant.now());
    }
}
