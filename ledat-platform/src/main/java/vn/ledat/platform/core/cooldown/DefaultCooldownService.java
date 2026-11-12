package vn.ledat.platform.core.cooldown;

import vn.ledat.platform.api.cooldown.CooldownService;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultCooldownService implements CooldownService {
    private final Map<Key, Long> expiresAt = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String namespace, UUID subject, String action, Duration duration) {
        Key key = new Key(namespace, subject, action);
        long now = System.currentTimeMillis();
        long until = now + Math.max(0L, duration.toMillis());
        java.util.concurrent.atomic.AtomicBoolean acquired = new java.util.concurrent.atomic.AtomicBoolean(false);
        expiresAt.compute(key, (ignored, current) -> {
            if (current == null || current <= now) {
                acquired.set(true);
                return until;
            }
            return current;
        });
        return acquired.get();
    }

    @Override
    public Duration remaining(String namespace, UUID subject, String action) {
        Long until = expiresAt.get(new Key(namespace, subject, action));
        if (until == null) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(Math.max(0L, until - System.currentTimeMillis()));
    }

    @Override
    public void clear(String namespace, UUID subject, String action) {
        expiresAt.remove(new Key(namespace, subject, action));
    }

    @Override
    public void clearSubject(UUID subject) {
        expiresAt.keySet().removeIf(key -> key.subject().equals(subject));
    }

    @Override
    public void clearNamespace(String namespace) {
        String safeNamespace = namespace == null ? "default" : namespace.toLowerCase(java.util.Locale.ROOT);
        expiresAt.keySet().removeIf(key -> key.namespace().equals(safeNamespace));
    }

    @Override
    public int cleanupExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<Key, Long> entry : expiresAt.entrySet()) {
            if (entry.getValue() <= now && expiresAt.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
    }

    private record Key(String namespace, UUID subject, String action) {
        private Key {
            namespace = namespace == null ? "default" : namespace.toLowerCase(java.util.Locale.ROOT);
            action = action == null ? "default" : action.toLowerCase(java.util.Locale.ROOT);
        }
    }
}
