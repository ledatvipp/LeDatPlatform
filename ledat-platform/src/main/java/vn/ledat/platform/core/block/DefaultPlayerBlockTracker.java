package vn.ledat.platform.core.block;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.block.PlayerBlockTracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class DefaultPlayerBlockTracker implements PlayerBlockTracker, Listener {
    private final Plugin plugin;
    private final NamespacedKey storageKey;
    private final AtomicLong lookups = new AtomicLong();
    private final AtomicLong marks = new AtomicLong();
    private final AtomicLong removals = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile boolean autoTrack;

    public DefaultPlayerBlockTracker(Plugin plugin) {
        this.plugin = plugin;
        this.storageKey = new NamespacedKey(plugin, "player_placed_blocks_v1");
        reload();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Refreshes cached config flags; call after the platform config was reloaded. */
    public void reload() {
        this.autoTrack = plugin.getConfig().getBoolean("player-block-tracker.auto-track", true);
    }

    @Override
    public boolean isPlayerPlaced(Block block) {
        if (block == null) {
            return false;
        }
        lookups.incrementAndGet();
        try {
            int[] values = values(block.getChunk());
            return Arrays.binarySearch(values, pack(block)) >= 0;
        } catch (Throwable error) {
            failures.incrementAndGet();
            return false;
        }
    }

    @Override
    public boolean markPlayerPlaced(Block block) {
        if (block == null) {
            return false;
        }
        try {
            Chunk chunk = block.getChunk();
            int packed = pack(block);
            int[] values = values(chunk);
            int index = Arrays.binarySearch(values, packed);
            if (index >= 0) {
                return false;
            }
            int insert = -index - 1;
            int[] next = new int[values.length + 1];
            System.arraycopy(values, 0, next, 0, insert);
            next[insert] = packed;
            System.arraycopy(values, insert, next, insert + 1, values.length - insert);
            container(chunk).set(storageKey, PersistentDataType.INTEGER_ARRAY, next);
            marks.incrementAndGet();
            return true;
        } catch (Throwable error) {
            failures.incrementAndGet();
            return false;
        }
    }

    @Override
    public boolean unmark(Block block) {
        if (block == null) {
            return false;
        }
        try {
            Chunk chunk = block.getChunk();
            int[] values = values(chunk);
            int index = Arrays.binarySearch(values, pack(block));
            if (index < 0) {
                return false;
            }
            if (values.length == 1) {
                container(chunk).remove(storageKey);
            } else {
                int[] next = new int[values.length - 1];
                System.arraycopy(values, 0, next, 0, index);
                System.arraycopy(values, index + 1, next, index, values.length - index - 1);
                container(chunk).set(storageKey, PersistentDataType.INTEGER_ARRAY, next);
            }
            removals.incrementAndGet();
            return true;
        } catch (Throwable error) {
            failures.incrementAndGet();
            return false;
        }
    }

    @Override
    public int trackedInChunk(Block block) {
        if (block == null) {
            return 0;
        }
        try {
            return values(block.getChunk()).length;
        } catch (Throwable error) {
            failures.incrementAndGet();
            return 0;
        }
    }
    @Override public TrackerStats stats() { return new TrackerStats(lookups.get(), marks.get(), removals.get(), failures.get()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (autoTrack) {
            markPlayerPlaced(event.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (autoTrack) {
            unmark(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (autoTrack) {
            unmarkAll(event.blockList());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (autoTrack) {
            unmarkAll(event.blockList());
        }
    }

    /**
     * Explosion lists can contain hundreds of blocks in the same chunk; removing
     * them one by one re-reads and rewrites the chunk PDC array per block. Group
     * by chunk so each affected chunk is read and written once.
     */
    private void unmarkAll(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        Map<Chunk, List<Block>> byChunk = new HashMap<>();
        for (Block block : blocks) {
            if (block != null) {
                byChunk.computeIfAbsent(block.getChunk(), ignored -> new ArrayList<>()).add(block);
            }
        }
        for (Map.Entry<Chunk, List<Block>> entry : byChunk.entrySet()) {
            unmarkInChunk(entry.getKey(), entry.getValue());
        }
    }

    private void unmarkInChunk(Chunk chunk, List<Block> blocks) {
        try {
            int[] stored = values(chunk);
            if (stored.length == 0) {
                return;
            }
            // The PDC may hand back its backing array; never mutate it in place.
            int[] values = stored.clone();
            int removed = 0;
            for (Block block : blocks) {
                int index = Arrays.binarySearch(values, 0, values.length - removed, pack(block));
                if (index < 0) {
                    continue;
                }
                System.arraycopy(values, index + 1, values, index, values.length - removed - index - 1);
                removed++;
            }
            if (removed == 0) {
                return;
            }
            if (removed == values.length) {
                container(chunk).remove(storageKey);
            } else {
                container(chunk).set(storageKey, PersistentDataType.INTEGER_ARRAY,
                        Arrays.copyOf(values, values.length - removed));
            }
            removals.addAndGet(removed);
        } catch (Throwable error) {
            failures.incrementAndGet();
        }
    }

    private int[] values(Chunk chunk) {
        int[] values = container(chunk).get(storageKey, PersistentDataType.INTEGER_ARRAY);
        if (values == null || values.length == 0) {
            return new int[0];
        }
        if (!sorted(values)) {
            values = values.clone();
            Arrays.sort(values);
        }
        return values;
    }

    private PersistentDataContainer container(Chunk chunk) {
        return chunk.getPersistentDataContainer();
    }

    private int pack(Block block) {
        int relativeY = block.getY() - block.getWorld().getMinHeight();
        return (relativeY << 8) | ((block.getX() & 15) << 4) | (block.getZ() & 15);
    }

    private boolean sorted(int[] values) {
        for (int i = 1; i < values.length; i++) {
            if (values[i - 1] > values[i]) {
                return false;
            }
        }
        return true;
    }
}
