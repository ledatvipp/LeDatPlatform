package vn.ledat.platform.api.cooldown;

import java.time.Duration;
import java.util.UUID;

public interface RateLimitService {
    RateLimitResult tryAcquire(String namespace, UUID subject, String bucket, int maxActions, Duration window);
    void clear(String namespace, UUID subject, String bucket);
    void clearSubject(UUID subject);

    /**
     * Clear all rate-limit windows in a namespace. Useful when a child plugin is disabled/reloaded.
     */
    default void clearNamespace(String namespace) { }

    int cleanupExpired();
}
