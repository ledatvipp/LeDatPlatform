package vn.ledat.platform.api.block;

import org.bukkit.block.Block;

/** Tracks player-placed blocks in the owning chunk's PersistentDataContainer. */
public interface PlayerBlockTracker {
    boolean isPlayerPlaced(Block block);
    boolean markPlayerPlaced(Block block);
    boolean unmark(Block block);
    int trackedInChunk(Block block);

    default TrackerStats stats() {
        return new TrackerStats(0, 0, 0, 0);
    }

    static PlayerBlockTracker disabled() {
        return new PlayerBlockTracker() {
            @Override public boolean isPlayerPlaced(Block block) { return false; }
            @Override public boolean markPlayerPlaced(Block block) { return false; }
            @Override public boolean unmark(Block block) { return false; }
            @Override public int trackedInChunk(Block block) { return 0; }
        };
    }

    record TrackerStats(long lookups, long marks, long removals, long failures) {
    }
}
