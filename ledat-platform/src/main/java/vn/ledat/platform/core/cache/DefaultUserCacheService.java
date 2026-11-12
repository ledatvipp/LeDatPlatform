package vn.ledat.platform.core.cache;

import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.cache.UserCacheService;
import vn.ledat.platform.api.scheduler.LeDatScheduler;
import vn.ledat.platform.api.scheduler.LeDatTask;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;

/** Bounded, write-behind user cache with in-flight load coalescing. */
public final class DefaultUserCacheService implements UserCacheService {
    private final Plugin platformPlugin;
    private final Map<String, DefaultUserCache<?>> caches = new ConcurrentHashMap<>();
    private final LeDatTask maintenanceTask;

    public DefaultUserCacheService(Plugin platformPlugin, LeDatScheduler scheduler) {
        this.platformPlugin = platformPlugin;
        long interval = Math.max(5L, platformPlugin.getConfig().getLong("user-cache.maintenance-interval-seconds", 15L));
        this.maintenanceTask = scheduler.asyncRepeat(platformPlugin, "user-cache-maintenance", this::maintenance,
                Duration.ofSeconds(interval), Duration.ofSeconds(interval));
    }

    @Override
    public <T> Builder<T> cache(Plugin owner, String name, Class<T> valueType) {
        Plugin safeOwner = owner == null ? platformPlugin : owner;
        String safeName = sanitize(name);
        if (safeName.isBlank()) {
            throw new IllegalArgumentException("cache name cannot be blank");
        }
        return new CacheBuilder<>(safeOwner, safeName, valueType == null ? Object.class : valueType);
    }

    @Override
    public Optional<UserCache<?>> find(Plugin owner, String name) {
        return Optional.ofNullable(caches.get(key(owner == null ? platformPlugin : owner, sanitize(name))));
    }

    @Override
    public Map<String, UserCache<?>> caches() {
        Map<String, UserCache<?>> snapshot = new java.util.LinkedHashMap<>();
        caches.forEach(snapshot::put);
        return Map.copyOf(snapshot);
    }

    @Override
    public CompletableFuture<Void> flush(Plugin owner) {
        if (owner == null) {
            return CompletableFuture.completedFuture(null);
        }
        String prefix = owner.getName().toLowerCase(java.util.Locale.ROOT) + ":";
        return CompletableFuture.allOf(caches.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .map(DefaultUserCache::flush)
                .toArray(CompletableFuture[]::new));
    }

    @Override
    public void cleanup(Plugin owner) {
        if (owner == null) {
            return;
        }
        String prefix = owner.getName().toLowerCase(java.util.Locale.ROOT) + ":";
        List<DefaultUserCache<?>> removed = new ArrayList<>();
        caches.entrySet().removeIf(entry -> {
            if (!entry.getKey().startsWith(prefix)) {
                return false;
            }
            removed.add(entry.getValue());
            return true;
        });
        removed.forEach(cache -> cache.flush().whenComplete((ignored, error) -> cache.clear()));
        if (owner.equals(platformPlugin)) {
            maintenanceTask.cancelQuietly();
        }
    }

    @Override
    public CacheServiceStats stats() {
        int entries = 0;
        int dirty = 0;
        int inFlight = 0;
        long evictions = 0;
        long failures = 0;
        for (DefaultUserCache<?> cache : caches.values()) {
            UserCacheStats stats = cache.stats();
            entries += stats.size();
            dirty += stats.dirtyEntries();
            inFlight += stats.inFlightLoads();
            evictions += stats.evictions();
            failures += stats.loadFailures() + stats.writeFailures();
        }
        return new CacheServiceStats(caches.size(), entries, dirty, inFlight, evictions, failures);
    }

    public CompletableFuture<Void> flushAll() {
        return CompletableFuture.allOf(caches.values().stream()
                .map(DefaultUserCache::flush)
                .toArray(CompletableFuture[]::new));
    }

    private void maintenance() {
        caches.values().forEach(DefaultUserCache::maintain);
    }

    private String key(Plugin owner, String name) {
        return owner.getName().toLowerCase(java.util.Locale.ROOT) + ":" + name;
    }

    private String sanitize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    private final class CacheBuilder<T> implements Builder<T> {
        private final Plugin owner;
        private final String name;
        private final Class<?> valueType;
        private Function<UUID, CompletableFuture<Optional<T>>> loader = id -> CompletableFuture.completedFuture(Optional.empty());
        private BiFunction<UUID, T, CompletableFuture<Void>> writer = (id, value) -> CompletableFuture.completedFuture(null);
        private Duration expireAfterAccess = Duration.ofMinutes(15);
        private Duration refreshAfterWrite = Duration.ZERO;
        private Duration writeBehind = Duration.ofSeconds(2);
        private Duration cacheMisses = Duration.ofSeconds(30);
        private int maximumSize = 10_000;

        private CacheBuilder(Plugin owner, String name, Class<?> valueType) {
            this.owner = owner;
            this.name = name;
            this.valueType = valueType;
        }

        @Override public Builder<T> loader(Function<UUID, CompletableFuture<Optional<T>>> loader) { this.loader = loader == null ? this.loader : loader; return this; }
        @Override public Builder<T> writer(BiFunction<UUID, T, CompletableFuture<Void>> writer) { this.writer = writer == null ? this.writer : writer; return this; }
        @Override public Builder<T> expireAfterAccess(Duration duration) { this.expireAfterAccess = positive(duration, Duration.ofMinutes(15)); return this; }
        @Override public Builder<T> refreshAfterWrite(Duration duration) { this.refreshAfterWrite = nonNegative(duration); return this; }
        @Override public Builder<T> writeBehind(Duration duration) { this.writeBehind = nonNegative(duration); return this; }
        @Override public Builder<T> maximumSize(int maximumSize) { this.maximumSize = Math.max(16, maximumSize); return this; }
        @Override public Builder<T> cacheMisses(Duration duration) { this.cacheMisses = nonNegative(duration); return this; }

        @Override
        @SuppressWarnings("unchecked")
        public UserCache<T> build() {
            String key = key(owner, name);
            DefaultUserCache<T> created = new DefaultUserCache<>(key, owner, valueType, loader, writer,
                    expireAfterAccess, refreshAfterWrite, writeBehind, cacheMisses, maximumSize);
            DefaultUserCache<?> existing = caches.putIfAbsent(key, created);
            if (existing != null && !existing.valueType.equals(valueType)) {
                throw new IllegalStateException("Cache " + key + " already exists with value type " + existing.valueType.getName());
            }
            return existing == null ? created : (UserCache<T>) existing;
        }

        private Duration positive(Duration value, Duration fallback) {
            return value == null || value.isZero() || value.isNegative() ? fallback : value;
        }

        private Duration nonNegative(Duration value) {
            return value == null || value.isNegative() ? Duration.ZERO : value;
        }
    }

    private static final class Entry<T> {
        private final T value;
        private final boolean present;
        private final long writtenAt;
        private volatile long accessedAt;
        private final AtomicBoolean dirty;

        private Entry(T value, boolean present, boolean dirty, long now) {
            this.value = value;
            this.present = present;
            this.writtenAt = now;
            this.accessedAt = now;
            this.dirty = new AtomicBoolean(dirty);
        }
    }

    private final class DefaultUserCache<T> implements UserCache<T> {
        private final String key;
        private final Plugin owner;
        private final Class<?> valueType;
        private final Function<UUID, CompletableFuture<Optional<T>>> loader;
        private final BiFunction<UUID, T, CompletableFuture<Void>> writer;
        private final long expireNanos;
        private final long refreshNanos;
        private final long writeBehindNanos;
        private final long missNanos;
        private final int maximumSize;
        private final Map<UUID, Entry<T>> entries = new ConcurrentHashMap<>();
        private final Map<UUID, CompletableFuture<Optional<T>>> inFlight = new ConcurrentHashMap<>();
        private final Map<UUID, CompletableFuture<Void>> writeChains = new ConcurrentHashMap<>();
        private final AtomicLong hits = new AtomicLong();
        private final AtomicLong misses = new AtomicLong();
        private final AtomicLong loads = new AtomicLong();
        private final AtomicLong loadFailures = new AtomicLong();
        private final AtomicLong writes = new AtomicLong();
        private final AtomicLong writeFailures = new AtomicLong();
        private final AtomicLong evictions = new AtomicLong();

        private DefaultUserCache(String key, Plugin owner, Class<?> valueType,
                                 Function<UUID, CompletableFuture<Optional<T>>> loader,
                                 BiFunction<UUID, T, CompletableFuture<Void>> writer,
                                 Duration expireAfterAccess, Duration refreshAfterWrite,
                                 Duration writeBehind, Duration cacheMisses, int maximumSize) {
            this.key = key;
            this.owner = owner;
            this.valueType = valueType;
            this.loader = loader;
            this.writer = writer;
            this.expireNanos = expireAfterAccess.toNanos();
            this.refreshNanos = refreshAfterWrite.toNanos();
            this.writeBehindNanos = writeBehind.toNanos();
            this.missNanos = cacheMisses.toNanos();
            this.maximumSize = maximumSize;
        }

        @Override public String key() { return key; }

        @Override
        public CompletableFuture<Optional<T>> get(UUID userId) {
            if (userId == null) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            long now = System.nanoTime();
            Entry<T> entry = entries.get(userId);
            if (entry != null && alive(entry, now)) {
                entry.accessedAt = now;
                hits.incrementAndGet();
                if (entry.present && !entry.dirty.get() && refreshNanos > 0 && now - entry.writtenAt >= refreshNanos) {
                    load(userId, true);
                }
                return CompletableFuture.completedFuture(entry.present ? Optional.of(entry.value) : Optional.empty());
            }
            if (entry != null) {
                evict(userId, entry);
            }
            misses.incrementAndGet();
            return load(userId, false);
        }

        @Override
        public Optional<T> getIfPresent(UUID userId) {
            if (userId == null) {
                return Optional.empty();
            }
            Entry<T> entry = entries.get(userId);
            long now = System.nanoTime();
            if (entry == null || !alive(entry, now) || !entry.present) {
                return Optional.empty();
            }
            entry.accessedAt = now;
            hits.incrementAndGet();
            return Optional.of(entry.value);
        }

        @Override
        public void put(UUID userId, T value) {
            if (userId == null || value == null) {
                return;
            }
            requireType(value);
            entries.put(userId, new Entry<>(value, true, true, System.nanoTime()));
            trim();
            if (writeBehindNanos == 0L) {
                write(userId, entries.get(userId));
            }
        }

        @Override
        public CompletableFuture<Void> putAndSave(UUID userId, T value) {
            put(userId, value);
            Entry<T> entry = entries.get(userId);
            return entry == null ? CompletableFuture.completedFuture(null) : write(userId, entry);
        }

        @Override
        public CompletableFuture<Void> invalidate(UUID userId, boolean save) {
            if (userId == null) {
                return CompletableFuture.completedFuture(null);
            }
            Entry<T> entry = entries.remove(userId);
            inFlight.remove(userId);
            return save && entry != null && entry.present && entry.dirty.get()
                    ? writeValue(userId, entry.value, entry.dirty)
                    : CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> flush() {
            return CompletableFuture.allOf(entries.entrySet().stream()
                    .filter(entry -> entry.getValue().present && entry.getValue().dirty.get())
                    .map(entry -> write(entry.getKey(), entry.getValue()))
                    .toArray(CompletableFuture[]::new));
        }

        @Override public void clear() { entries.clear(); inFlight.clear(); }
        @Override public int size() { return entries.size(); }

        @Override
        public UserCacheStats stats() {
            int dirty = (int) entries.values().stream().filter(entry -> entry.dirty.get()).count();
            return new UserCacheStats(key, entries.size(), dirty, inFlight.size(), hits.get(), misses.get(), loads.get(),
                    loadFailures.get(), writes.get(), writeFailures.get(), evictions.get(), Instant.now());
        }

        private CompletableFuture<Optional<T>> load(UUID userId, boolean refresh) {
            CompletableFuture<Optional<T>> existing = inFlight.get(userId);
            if (existing != null) {
                return existing;
            }
            CompletableFuture<Optional<T>> result = new CompletableFuture<>();
            existing = inFlight.putIfAbsent(userId, result);
            if (existing != null) {
                return existing;
            }
            loads.incrementAndGet();
            CompletableFuture<Optional<T>> loadedFuture;
            try {
                loadedFuture = loader.apply(userId);
                if (loadedFuture == null) {
                    loadedFuture = CompletableFuture.completedFuture(Optional.empty());
                }
            } catch (Throwable error) {
                loadedFuture = CompletableFuture.failedFuture(error);
            }
            loadedFuture.handle((loaded, error) -> {
                    if (error != null) {
                        loadFailures.incrementAndGet();
                        if (!refresh) {
                            throw new java.util.concurrent.CompletionException(error);
                        }
                        return getIfPresent(userId);
                    }
                    Optional<T> safe = loaded == null ? Optional.empty() : loaded;
                    long now = System.nanoTime();
                    safe.ifPresent(this::requireType);
                    java.util.concurrent.atomic.AtomicReference<Optional<T>> resolved = new java.util.concurrent.atomic.AtomicReference<>(safe);
                    if (safe.isPresent() || missNanos > 0L) {
                        entries.compute(userId, (ignored, current) -> {
                            if (current != null && current.dirty.get()) {
                                resolved.set(current.present ? Optional.of(current.value) : Optional.empty());
                                return current;
                            }
                            return new Entry<>(safe.orElse(null), safe.isPresent(), false, now);
                        });
                        trim();
                    }
                    return resolved.get();
                }).whenComplete((loaded, error) -> {
                    inFlight.remove(userId, result);
                    if (error == null) {
                        result.complete(loaded);
                    } else {
                        result.completeExceptionally(error);
                    }
                });
            return result;
        }

        private boolean alive(Entry<T> entry, long now) {
            long ttl = entry.present ? expireNanos : missNanos;
            return ttl > 0L && now - entry.accessedAt < ttl;
        }

        private void maintain() {
            long now = System.nanoTime();
            entries.forEach((id, entry) -> {
                if (!alive(entry, now)) {
                    evict(id, entry);
                } else if (entry.present && entry.dirty.get() && now - entry.writtenAt >= writeBehindNanos) {
                    write(id, entry);
                }
            });
            trim();
        }

        private void trim() {
            int size = entries.size();
            if (size <= maximumSize) {
                return;
            }
            int batch = Math.min(64, Math.max(1, maximumSize / 100));
            int targetSize = Math.max(0, maximumSize - batch);
            int remove = Math.min(size, size - targetSize);
            PriorityQueue<Map.Entry<UUID, Entry<T>>> oldest = new PriorityQueue<>(remove,
                    Comparator.comparingLong((Map.Entry<UUID, Entry<T>> entry) -> entry.getValue().accessedAt).reversed());
            for (Map.Entry<UUID, Entry<T>> entry : entries.entrySet()) {
                if (oldest.size() < remove) {
                    oldest.offer(entry);
                } else if (entry.getValue().accessedAt < oldest.peek().getValue().accessedAt) {
                    oldest.poll();
                    oldest.offer(entry);
                }
            }
            oldest.forEach(entry -> evict(entry.getKey(), entry.getValue()));
        }

        private void evict(UUID id, Entry<T> entry) {
            if (!entries.remove(id, entry)) {
                return;
            }
            evictions.incrementAndGet();
            if (entry.present && entry.dirty.get()) {
                writeValue(id, entry.value, entry.dirty).whenComplete((ignored, error) -> {
                    if (error != null) {
                        // Failed evictions are restored so a transient database outage
                        // cannot silently discard the only dirty copy.
                        entries.putIfAbsent(id, entry);
                    }
                });
            }
        }

        private CompletableFuture<Void> write(UUID id, Entry<T> entry) {
            return entry == null || !entry.present ? CompletableFuture.completedFuture(null) : writeValue(id, entry.value, entry.dirty);
        }

        private CompletableFuture<Void> writeValue(UUID id, T value, AtomicBoolean dirtyFlag) {
            if (!dirtyFlag.compareAndSet(true, false)) {
                return CompletableFuture.completedFuture(null);
            }
            writes.incrementAndGet();
            CompletableFuture<Void> operation = new CompletableFuture<>();
            AtomicReference<CompletableFuture<Void>> readyRef = new AtomicReference<>();
            CompletableFuture<Void> chained = writeChains.compute(id, (ignored, previous) -> {
                CompletableFuture<Void> ready = previous == null
                        ? CompletableFuture.completedFuture(null)
                        : previous.handle((result, error) -> null);
                readyRef.set(ready);
                return ready.thenCompose(unused -> operation);
            });
            readyRef.get().whenComplete((ignored, predecessorError) -> {
                CompletableFuture<Void> write = invokeWriter(id, value);
                write.whenComplete((result, error) -> {
                    if (error != null) {
                        dirtyFlag.set(true);
                        writeFailures.incrementAndGet();
                        owner.getLogger().log(Level.WARNING, "User cache write failed for " + key, error);
                        operation.completeExceptionally(error);
                    } else {
                        operation.complete(null);
                    }
                });
            });
            chained.whenComplete((ignored, error) -> writeChains.remove(id, chained));
            return chained;
        }

        private CompletableFuture<Void> invokeWriter(UUID id, T value) {
            try {
                CompletableFuture<Void> future = writer.apply(id, value);
                return future == null ? CompletableFuture.completedFuture(null) : future;
            } catch (Throwable error) {
                return CompletableFuture.failedFuture(error);
            }
        }

        private void requireType(T value) {
            if (value != null && !valueType.isInstance(value)) {
                throw new IllegalArgumentException("Cache " + key + " expected " + valueType.getName() + " but got " + value.getClass().getName());
            }
        }
    }
}
