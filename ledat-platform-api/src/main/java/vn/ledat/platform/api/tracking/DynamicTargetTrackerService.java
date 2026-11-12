package vn.ledat.platform.api.tracking;

import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Viewer-private, moving-player tracking contract. */
public interface DynamicTargetTrackerService {
    CompletableFuture<StartResult> start(Plugin owner, UUID viewerId, UUID targetId,
                                         Duration duration, Presentation presentation);

    boolean stop(UUID viewerId, String reason);

    boolean isTracking(UUID viewerId);

    record Presentation(String sameWorldActionbar, String otherWorldActionbar,
                        boolean updateCompass, int updatePeriodTicks) {
        public Presentation {
            updatePeriodTicks = Math.max(1, updatePeriodTicks);
        }
    }

    record StartResult(boolean started, String reason, long expiresAtMillis) {
        public static StartResult success(long expiresAtMillis) {
            return new StartResult(true, "started", expiresAtMillis);
        }
        public static StartResult rejected(String reason) {
            return new StartResult(false, reason, 0L);
        }
    }
}
