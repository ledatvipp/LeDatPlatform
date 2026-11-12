package vn.ledat.platform.common.cooldown;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small bounded-in-spirit cooldown helper used by LeDat plugins for GUI clicks,
 * PvP duplicate windows and pair anti-farm guards.
 *
 * <p>The class stores only UUID/string keys, never Player references. Call
 * {@link #cleanup()} from an existing repeating task or during plugin reload.</p>
 */
public final class LeDatCooldownGuard {
    private final Map<String, Long> timestamps = new ConcurrentHashMap<>();
    private volatile long defaultWindowMillis;
    private volatile long cleanupAfterMillis;

    public LeDatCooldownGuard(Duration defaultWindow) {
        this(defaultWindow == null ? 0L : defaultWindow.toMillis());
    }

    public LeDatCooldownGuard(long defaultWindowMillis) {
        configure(defaultWindowMillis);
    }

    public void configure(long defaultWindowMillis) {
        this.defaultWindowMillis = Math.max(0L, defaultWindowMillis);
        this.cleanupAfterMillis = Math.max(5_000L, this.defaultWindowMillis * 4L);
    }

    public boolean tryMark(UUID uuid) {
        return tryMark(key(uuid), defaultWindowMillis);
    }

    public boolean tryMark(UUID uuid, long windowMillis) {
        return tryMark(key(uuid), windowMillis);
    }

    public boolean tryMarkPair(UUID first, UUID second) {
        return tryMark(pairKey(first, second), defaultWindowMillis);
    }

    public boolean tryMarkPair(UUID first, UUID second, long windowMillis) {
        return tryMark(pairKey(first, second), windowMillis);
    }

    public boolean tryMark(String key) {
        return tryMark(key, defaultWindowMillis);
    }

    public boolean tryMark(String rawKey, long windowMillis) {
        String key = normalize(rawKey);
        if (key.isEmpty()) {
            return false;
        }
        long safeWindow = Math.max(0L, windowMillis);
        long now = System.currentTimeMillis();
        Long last = timestamps.get(key);
        if (safeWindow > 0L && last != null && now - last <= safeWindow) {
            return false;
        }
        timestamps.put(key, now);
        return true;
    }

    public long remainingMillis(UUID uuid) {
        return remainingMillis(key(uuid), defaultWindowMillis);
    }

    public long remainingMillisPair(UUID first, UUID second) {
        return remainingMillis(pairKey(first, second), defaultWindowMillis);
    }

    public long remainingMillis(String rawKey, long windowMillis) {
        String key = normalize(rawKey);
        Long last = timestamps.get(key);
        if (last == null) {
            return 0L;
        }
        long remaining = last + Math.max(0L, windowMillis) - System.currentTimeMillis();
        return Math.max(0L, remaining);
    }

    public void clear(UUID uuid) {
        timestamps.remove(key(uuid));
    }

    public void clearPair(UUID first, UUID second) {
        timestamps.remove(pairKey(first, second));
    }

    public void clear() {
        timestamps.clear();
    }

    public int size() {
        return timestamps.size();
    }

    public void cleanup() {
        cleanup(cleanupAfterMillis);
    }

    public void cleanup(long maxAgeMillis) {
        long cutoff = System.currentTimeMillis() - Math.max(1L, maxAgeMillis);
        Iterator<Map.Entry<String, Long>> iterator = timestamps.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (entry.getValue() == null || entry.getValue() < cutoff) {
                iterator.remove();
            }
        }
    }

    public static String key(UUID uuid) {
        return uuid == null ? "" : uuid.toString();
    }

    public static String pairKey(UUID first, UUID second) {
        return key(first) + ':' + key(second);
    }

    private static String normalize(String raw) {
        return Objects.toString(raw, "").trim().toLowerCase(java.util.Locale.ROOT);
    }
}
