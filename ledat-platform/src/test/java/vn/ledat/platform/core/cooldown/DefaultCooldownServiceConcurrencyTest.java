package vn.ledat.platform.core.cooldown;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultCooldownServiceConcurrencyTest {

    @Test
    void exactlyOneConcurrentCallerAcquiresCooldown() throws Exception {
        DefaultCooldownService service = new DefaultCooldownService();
        UUID subject = UUID.randomUUID();
        int callers = 64;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();

        try {
            for (int i = 0; i < callers; i++) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return service.tryAcquire("test", subject, "atomic", Duration.ofSeconds(30));
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            long acquired = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(5, TimeUnit.SECONDS)) {
                    acquired++;
                }
            }
            assertEquals(1L, acquired);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cleanupExpiredDoesNotRemoveActiveOrOtherNamespaces() {
        DefaultCooldownService service = new DefaultCooldownService();
        UUID subject = UUID.randomUUID();

        assertTrue(service.tryAcquire("tempblocks", subject, "feedback:placement", Duration.ofSeconds(30)));
        assertTrue(service.tryAcquire("other-plugin", subject, "feedback:placement", Duration.ofSeconds(30)));

        service.clearNamespace("tempblocks");

        assertEquals(Duration.ZERO, service.remaining("tempblocks", subject, "feedback:placement"));
        assertTrue(service.remaining("other-plugin", subject, "feedback:placement").isPositive());
        assertEquals(0, service.cleanupExpired());
    }
}
