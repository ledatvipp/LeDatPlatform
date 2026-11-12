package vn.ledat.platform.api.cache;

import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Shared bounded user-cache service. Values are kept in memory while loaders and
 * writers remain fully asynchronous, so database work never has to run on a
 * Bukkit/Folia thread.
 */
public interface UserCacheService {
    <T> Builder<T> cache(Plugin owner, String name, Class<T> valueType);

    default Optional<UserCache<?>> find(Plugin owner, String name) {
        return Optional.empty();
    }

    default Map<String, UserCache<?>> caches() {
        return Map.of();
    }

    /** Flush every dirty cache owned by a plugin before its storage is closed. */
    default CompletableFuture<Void> flush(Plugin owner) {
        return CompletableFuture.completedFuture(null);
    }

    default void cleanup(Plugin owner) {
    }

    default CacheServiceStats stats() {
        return new CacheServiceStats(0, 0, 0, 0, 0, 0);
    }

    static UserCacheService disabled() {
        return DisabledHolder.INSTANCE;
    }

    interface Builder<T> {
        Builder<T> loader(Function<UUID, CompletableFuture<Optional<T>>> loader);
        Builder<T> writer(BiFunction<UUID, T, CompletableFuture<Void>> writer);
        Builder<T> expireAfterAccess(Duration duration);
        Builder<T> refreshAfterWrite(Duration duration);
        Builder<T> writeBehind(Duration duration);
        Builder<T> maximumSize(int maximumSize);
        Builder<T> cacheMisses(Duration duration);
        UserCache<T> build();
    }

    interface UserCache<T> {
        String key();
        CompletableFuture<Optional<T>> get(UUID userId);
        Optional<T> getIfPresent(UUID userId);
        void put(UUID userId, T value);
        CompletableFuture<Void> putAndSave(UUID userId, T value);
        CompletableFuture<Void> invalidate(UUID userId, boolean save);
        CompletableFuture<Void> flush();
        void clear();
        int size();
        UserCacheStats stats();
    }

    record UserCacheStats(
            String key,
            int size,
            int dirtyEntries,
            int inFlightLoads,
            long hits,
            long misses,
            long loads,
            long loadFailures,
            long writes,
            long writeFailures,
            long evictions,
            Instant snapshotAt
    ) {
    }

    record CacheServiceStats(
            int caches,
            int entries,
            int dirtyEntries,
            int inFlightLoads,
            long evictions,
            long failures
    ) {
    }

    final class DisabledHolder {
        private static final UserCacheService INSTANCE = new UserCacheService() {
            @Override
            public <T> Builder<T> cache(Plugin owner, String name, Class<T> valueType) {
                return new Builder<>() {
                    @Override public Builder<T> loader(Function<UUID, CompletableFuture<Optional<T>>> loader) { return this; }
                    @Override public Builder<T> writer(BiFunction<UUID, T, CompletableFuture<Void>> writer) { return this; }
                    @Override public Builder<T> expireAfterAccess(Duration duration) { return this; }
                    @Override public Builder<T> refreshAfterWrite(Duration duration) { return this; }
                    @Override public Builder<T> writeBehind(Duration duration) { return this; }
                    @Override public Builder<T> maximumSize(int maximumSize) { return this; }
                    @Override public Builder<T> cacheMisses(Duration duration) { return this; }
                    @Override public UserCache<T> build() { return new DisabledCache<>(); }
                };
            }
        };

        private DisabledHolder() {
        }
    }

    final class DisabledCache<T> implements UserCache<T> {
        @Override public String key() { return "disabled"; }
        @Override public CompletableFuture<Optional<T>> get(UUID userId) { return CompletableFuture.completedFuture(Optional.empty()); }
        @Override public Optional<T> getIfPresent(UUID userId) { return Optional.empty(); }
        @Override public void put(UUID userId, T value) { }
        @Override public CompletableFuture<Void> putAndSave(UUID userId, T value) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> invalidate(UUID userId, boolean save) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> flush() { return CompletableFuture.completedFuture(null); }
        @Override public void clear() { }
        @Override public int size() { return 0; }
        @Override public UserCacheStats stats() { return new UserCacheStats("disabled", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Instant.now()); }
    }
}
