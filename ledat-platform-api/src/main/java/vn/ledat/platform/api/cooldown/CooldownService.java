package vn.ledat.platform.api.cooldown;

import java.time.Duration;
import java.util.UUID;

public interface CooldownService {
    boolean tryAcquire(String namespace, UUID subject, String action, Duration duration);
    Duration remaining(String namespace, UUID subject, String action);
    void clear(String namespace, UUID subject, String action);
    void clearSubject(UUID subject);

    /**
     * Clear all cooldowns in a namespace. Useful when a child plugin is disabled/reloaded.
     */
    default void clearNamespace(String namespace) { }

    int cleanupExpired();
}
