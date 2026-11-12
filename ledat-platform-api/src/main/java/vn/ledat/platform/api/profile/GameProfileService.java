package vn.ledat.platform.api.profile;

import org.bukkit.inventory.ItemStack;
import org.bukkit.profile.PlayerProfile;

import java.net.URL;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface GameProfileService {
    CompletableFuture<Optional<PlayerProfile>> resolve(UUID uniqueId, String name);
    CompletableFuture<Optional<PlayerProfile>> resolve(UUID uniqueId);
    CompletableFuture<Optional<PlayerProfile>> resolve(String name);
    CompletableFuture<ItemStack> head(UUID uniqueId, String name);
    ItemStack texturedHead(URL skinUrl, String displayName);
    void invalidate(UUID uniqueId, String name);
    void clear();

    default ProfileCacheStats stats() {
        return new ProfileCacheStats(0, 0, 0, 0, 0, Instant.now());
    }

    static GameProfileService disabled() {
        return new GameProfileService() {
            @Override public CompletableFuture<Optional<PlayerProfile>> resolve(UUID uniqueId, String name) { return CompletableFuture.completedFuture(Optional.empty()); }
            @Override public CompletableFuture<Optional<PlayerProfile>> resolve(UUID uniqueId) { return resolve(uniqueId, null); }
            @Override public CompletableFuture<Optional<PlayerProfile>> resolve(String name) { return resolve(null, name); }
            @Override public CompletableFuture<ItemStack> head(UUID uniqueId, String name) { return CompletableFuture.completedFuture(new ItemStack(org.bukkit.Material.PLAYER_HEAD)); }
            @Override public ItemStack texturedHead(URL skinUrl, String displayName) { return new ItemStack(org.bukkit.Material.PLAYER_HEAD); }
            @Override public void invalidate(UUID uniqueId, String name) { }
            @Override public void clear() { }
        };
    }

    record ProfileCacheStats(int entries, int inFlight, long hits, long misses, long failures, Instant snapshotAt) {
    }
}
