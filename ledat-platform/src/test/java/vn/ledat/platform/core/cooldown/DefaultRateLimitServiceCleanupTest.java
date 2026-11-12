package vn.ledat.platform.core.cooldown;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultRateLimitServiceCleanupTest {
    @Test
    void cleanupLeavesAnActiveWindowIntact() {
        DefaultRateLimitService service = new DefaultRateLimitService();
        UUID subject = UUID.randomUUID();

        assertTrue(service.tryAcquire("tempblocks", subject, "feedback", 1, Duration.ofSeconds(30)).allowed());

        assertEquals(0, service.cleanupExpired());
        assertFalse(service.tryAcquire("tempblocks", subject, "feedback", 1, Duration.ofSeconds(30)).allowed());
    }
}
