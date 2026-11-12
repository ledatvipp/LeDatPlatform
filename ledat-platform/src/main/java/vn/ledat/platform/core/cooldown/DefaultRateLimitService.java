package vn.ledat.platform.core.cooldown;

import vn.ledat.platform.api.cooldown.RateLimitResult;
import vn.ledat.platform.api.cooldown.RateLimitService;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class DefaultRateLimitService implements RateLimitService {
    private final Map<Key, Window> windows = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryAcquire(String namespace, UUID subject, String bucket, int maxActions, Duration windowDuration) {
        long now = System.currentTimeMillis();
        long windowMs = Math.max(1L, windowDuration.toMillis());
        Key key = new Key(namespace, subject, bucket);
        Window window = windows.compute(key, (ignored, current) -> {
            if (current == null || current.expiresAt <= now) {
                return new Window(now + windowMs, new AtomicInteger(0));
            }
            return current;
        });
        int used = window.count.incrementAndGet();
        if (used <= maxActions) {
            return RateLimitResult.allowed(Math.max(0, maxActions - used));
        }
        return RateLimitResult.denied(Duration.ofMillis(Math.max(0L, window.expiresAt - now)));
    }

    @Override
    public void clear(String namespace, UUID subject, String bucket) {
        windows.remove(new Key(namespace, subject, bucket));
    }

    @Override
    public void clearSubject(UUID subject) {
        windows.keySet().removeIf(key -> key.subject().equals(subject));
    }

    @Override
    public void clearNamespace(String namespace) {
        String safeNamespace = namespace == null ? "default" : namespace.toLowerCase(java.util.Locale.ROOT);
        windows.keySet().removeIf(key -> key.namespace().equals(safeNamespace));
    }

    @Override
    public int cleanupExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<Key, Window> entry : windows.entrySet()) {
            if (entry.getValue().expiresAt <= now && windows.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
    }

    private record Key(String namespace, UUID subject, String bucket) {
        private Key {
            namespace = namespace == null ? "default" : namespace.toLowerCase(java.util.Locale.ROOT);
            bucket = bucket == null ? "default" : bucket.toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static final class Window {
        private final long expiresAt;
        private final AtomicInteger count;

        private Window(long expiresAt, AtomicInteger count) {
            this.expiresAt = expiresAt;
            this.count = count;
        }
    }
}
