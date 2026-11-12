package vn.ledat.platform.core.scheduler;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.enums.EntityTaskResult;
import com.tcoded.folialib.enums.ImplementationType;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import vn.ledat.platform.api.scheduler.EntityTaskOutcome;
import vn.ledat.platform.api.scheduler.LeDatTask;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

class DefaultSchedulerEntityOperationTest {
    @Test
    void retiredBranchNeverRunsOperationBodyAndWinsExactlyOnce() {
        try (Harness harness = new Harness()) {
            AtomicInteger calls = new AtomicInteger();
            CompletableFuture<EntityTaskOutcome<String>> result = harness.scheduler.entityOperation(
                    harness.owner, harness.entity, () -> {
                        calls.incrementAndGet();
                        return "snapshot";
                    });

            harness.retired.get().run();
            harness.execute.get().accept(mock(WrappedTask.class));

            assertEquals(EntityTaskOutcome.Status.RETIRED, result.join().status());
            assertEquals(0, calls.get());
            assertEquals(0, harness.scheduler.trackedTaskCount(harness.owner));
        }
    }

    @Test
    void executionAndFailureProduceTypedTerminalOutcomes() {
        try (Harness executed = new Harness()) {
            CompletableFuture<EntityTaskOutcome<String>> result = executed.scheduler.entityOperation(
                    executed.owner, executed.entity, () -> "snapshot");
            executed.execute.get().accept(mock(WrappedTask.class));

            assertEquals(EntityTaskOutcome.Status.EXECUTED, result.join().status());
            assertEquals("snapshot", result.join().value());
        }

        IllegalStateException failure = new IllegalStateException("capture failed");
        try (Harness failed = new Harness()) {
            CompletableFuture<EntityTaskOutcome<String>> result = failed.scheduler.entityOperation(
                    failed.owner, failed.entity, () -> { throw failure; });
            failed.execute.get().accept(mock(WrappedTask.class));

            assertEquals(EntityTaskOutcome.Status.FAILED, result.join().status());
            assertSame(failure, result.join().error());
        }
    }

    @Test
    void ownerCancellationCompletesPendingOperationAsRejected() {
        try (Harness harness = new Harness()) {
            CompletableFuture<EntityTaskOutcome<String>> result = harness.scheduler.entityOperation(
                    harness.owner, harness.entity, () -> "must-not-run");

            assertFalse(result.isDone());
            harness.scheduler.cancelAll(harness.owner);

            assertTrue(result.isDone());
            assertEquals(EntityTaskOutcome.Status.REJECTED, result.join().status());
            assertEquals(0, harness.scheduler.trackedTaskCount(harness.owner));
        }
    }

    @Test
    void disabledOwnerAndBackendSubmissionFailureAreRejectedWithoutRunningBody() {
        try (Harness disabled = new Harness()) {
            AtomicInteger calls = new AtomicInteger();
            when(disabled.owner.isEnabled()).thenReturn(false);

            EntityTaskOutcome<String> result = disabled.scheduler.entityOperation(
                    disabled.owner, disabled.entity, () -> {
                        calls.incrementAndGet();
                        return "must-not-run";
                    }).join();

            assertEquals(EntityTaskOutcome.Status.REJECTED, result.status());
            assertEquals(0, calls.get());
        }

        try (Harness rejected = new Harness()) {
            AtomicInteger calls = new AtomicInteger();
            when(rejected.backend.runAtEntityWithFallback(any(Entity.class), any(Consumer.class), any(Runnable.class)))
                    .thenThrow(new IllegalStateException("scheduler rejected"));

            EntityTaskOutcome<String> result = rejected.scheduler.entityOperation(
                    rejected.owner, rejected.entity, () -> {
                        calls.incrementAndGet();
                        return "must-not-run";
                    }).join();

            assertEquals(EntityTaskOutcome.Status.REJECTED, result.status());
            assertEquals(0, calls.get());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancellationRacingBackendSubmissionCancelsThePreCancelTask() throws Exception {
        try (Harness harness = new Harness(); ExecutorService caller = Executors.newSingleThreadExecutor()) {
            CountDownLatch backendEntered = new CountDownLatch(1);
            CountDownLatch backendRelease = new CountDownLatch(1);
            CompletableFuture<Void> backendTask = new CompletableFuture<>();
            when(harness.backend.runAsync(any(Consumer.class))).thenAnswer(invocation -> {
                backendEntered.countDown();
                if (!backendRelease.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test timed out releasing scheduler backend");
                }
                return backendTask;
            });

            Future<LeDatTask> submitted = caller.submit(
                    () -> harness.scheduler.async(harness.owner, "cancel-race", () -> { }));
            assertTrue(backendEntered.await(2, TimeUnit.SECONDS));

            harness.scheduler.cancelAll(harness.owner);
            backendRelease.countDown();
            assertNotNull(submitted.get(2, TimeUnit.SECONDS));

            assertTrue(backendTask.isCancelled());
            assertEquals(0, harness.scheduler.trackedTaskCount(harness.owner));
        }
    }

    private static final class Harness implements AutoCloseable {
        private final Plugin platform = plugin("LeDatPlatform");
        private final Plugin owner = plugin("LeDatAdminTools");
        private final Entity entity = mock(Entity.class);
        private final PlatformScheduler backend = mock(PlatformScheduler.class);
        private final AtomicReference<Consumer<WrappedTask>> execute = new AtomicReference<>();
        private final AtomicReference<Runnable> retired = new AtomicReference<>();
        private final CompletableFuture<EntityTaskResult> backendFuture = new CompletableFuture<>();
        private final MockedConstruction<FoliaLib> construction;
        private final DefaultScheduler scheduler;

        @SuppressWarnings("unchecked")
        private Harness() {
            when(backend.runAtEntityWithFallback(any(Entity.class), any(Consumer.class), any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        execute.set(invocation.getArgument(1));
                        retired.set(invocation.getArgument(2));
                        return backendFuture;
                    });
            construction = mockConstruction(FoliaLib.class, (mock, context) -> {
                when(mock.getImplType()).thenReturn(ImplementationType.PAPER);
                when(mock.getScheduler()).thenReturn(backend);
                when(mock.isPaper()).thenReturn(true);
            });
            scheduler = new DefaultScheduler(platform);
            assertNotNull(construction.constructed().getFirst());
        }

        @Override
        public void close() {
            scheduler.cancelAll(owner);
            scheduler.cancelAll(platform);
            construction.close();
        }
    }

    private static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(name + "Test"));
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        return plugin;
    }
}
