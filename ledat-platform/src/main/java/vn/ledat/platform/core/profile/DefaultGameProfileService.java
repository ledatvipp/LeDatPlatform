package vn.ledat.platform.core.profile;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import vn.ledat.platform.api.profile.GameProfileService;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class DefaultGameProfileService implements GameProfileService {
    private final Plugin plugin;
    private final Map<String, CachedProfile> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Optional<PlayerProfile>>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public DefaultGameProfileService(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public CompletableFuture<Optional<PlayerProfile>> resolve(UUID uniqueId, String name) {
        if (uniqueId == null && (name == null || name.isBlank())) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String key = key(uniqueId, name);
        long now = System.nanoTime();
        CachedProfile cached = cache.get(key);
        if (cached != null && now - cached.accessedAt < ttlNanos(cached.profile.isPresent())) {
            cached.accessedAt = now;
            hits.incrementAndGet();
            return CompletableFuture.completedFuture(cached.profile.map(PlayerProfile::clone));
        }
        if (cached != null) {
            cache.remove(key, cached);
        }
        misses.incrementAndGet();
        CompletableFuture<Optional<PlayerProfile>> existing = inFlight.get(key);
        if (existing != null) {
            return existing.thenApply(profile -> profile.map(PlayerProfile::clone));
        }
        CompletableFuture<Optional<PlayerProfile>> result = new CompletableFuture<>();
        existing = inFlight.putIfAbsent(key, result);
        if (existing != null) {
            return existing.thenApply(profile -> profile.map(PlayerProfile::clone));
        }
        try {
            PlayerProfile unresolved = uniqueId != null && name != null && !name.isBlank()
                    ? Bukkit.createPlayerProfile(uniqueId, name)
                    : uniqueId != null ? Bukkit.createPlayerProfile(uniqueId) : Bukkit.createPlayerProfile(name);
            unresolved.update().whenComplete((updated, error) -> {
                inFlight.remove(key, result);
                if (error != null || updated == null) {
                    failures.incrementAndGet();
                    cache.put(key, new CachedProfile(Optional.empty(), System.nanoTime()));
                    trim();
                    result.complete(Optional.empty());
                } else {
                    PlayerProfile copy = updated.clone();
                    cache.put(key, new CachedProfile(Optional.of(copy), System.nanoTime()));
                    trim();
                    result.complete(Optional.of(copy.clone()));
                }
            });
        } catch (Throwable error) {
            inFlight.remove(key, result);
            failures.incrementAndGet();
            cache.put(key, new CachedProfile(Optional.empty(), System.nanoTime()));
            trim();
            result.complete(Optional.empty());
        }
        return result;
    }

    @Override public CompletableFuture<Optional<PlayerProfile>> resolve(UUID uniqueId) { return resolve(uniqueId, null); }
    @Override public CompletableFuture<Optional<PlayerProfile>> resolve(String name) { return resolve(null, name); }

    @Override
    public CompletableFuture<ItemStack> head(UUID uniqueId, String name) {
        return resolve(uniqueId, name).thenApply(profile -> {
            ItemStack item = new ItemStack(Material.PLAYER_HEAD);
            if (item.getItemMeta() instanceof SkullMeta meta) {
                profile.ifPresent(meta::setOwnerProfile);
                item.setItemMeta(meta);
            }
            return item;
        });
    }

    @Override
    public ItemStack texturedHead(URL skinUrl, String displayName) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (skinUrl == null || !(item.getItemMeta() instanceof SkullMeta meta)) {
            return item;
        }
        PlayerProfile profile = Bukkit.createPlayerProfile(UUID.nameUUIDFromBytes(skinUrl.toExternalForm().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        PlayerTextures textures = profile.getTextures();
        textures.setSkin(skinUrl);
        profile.setTextures(textures);
        meta.setOwnerProfile(profile);
        if (displayName != null && !displayName.isBlank()) {
            meta.displayName(Component.text(displayName));
        }
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public void invalidate(UUID uniqueId, String name) {
        cache.remove(key(uniqueId, name));
    }

    @Override public void clear() { cache.clear(); inFlight.clear(); }

    @Override
    public ProfileCacheStats stats() {
        return new ProfileCacheStats(cache.size(), inFlight.size(), hits.get(), misses.get(), failures.get(), Instant.now());
    }

    private long ttlNanos(boolean present) {
        long seconds = present
                ? Math.max(60L, plugin.getConfig().getLong("profiles.cache-ttl-seconds", 21600L))
                : Math.max(5L, plugin.getConfig().getLong("profiles.negative-cache-ttl-seconds", 60L));
        return Duration.ofSeconds(seconds).toNanos();
    }

    private void trim() {
        int maximum = Math.max(64, plugin.getConfig().getInt("profiles.maximum-cache-size", 5000));
        int size = cache.size();
        if (size <= maximum) {
            return;
        }
        int batch = Math.min(64, Math.max(1, maximum / 100));
        int targetSize = Math.max(0, maximum - batch);
        int remove = Math.min(size, size - targetSize);
        PriorityQueue<Map.Entry<String, CachedProfile>> oldest = new PriorityQueue<>(remove,
                Comparator.comparingLong((Map.Entry<String, CachedProfile> entry) -> entry.getValue().accessedAt).reversed());
        for (Map.Entry<String, CachedProfile> entry : cache.entrySet()) {
            if (oldest.size() < remove) {
                oldest.offer(entry);
            } else if (entry.getValue().accessedAt < oldest.peek().getValue().accessedAt) {
                oldest.poll();
                oldest.offer(entry);
            }
        }
        oldest.forEach(entry -> cache.remove(entry.getKey(), entry.getValue()));
    }

    private String key(UUID uniqueId, String name) {
        if (uniqueId != null) {
            return "uuid:" + uniqueId;
        }
        return "name:" + String.valueOf(name).trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static final class CachedProfile {
        private final Optional<PlayerProfile> profile;
        private volatile long accessedAt;

        private CachedProfile(Optional<PlayerProfile> profile, long accessedAt) {
            this.profile = profile;
            this.accessedAt = accessedAt;
        }
    }
}
