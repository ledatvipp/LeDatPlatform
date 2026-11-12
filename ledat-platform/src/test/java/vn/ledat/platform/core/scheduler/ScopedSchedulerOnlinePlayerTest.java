package vn.ledat.platform.core.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import vn.ledat.platform.api.scheduler.EntityTaskOutcome;
import vn.ledat.platform.api.scheduler.LeDatScheduler;
import vn.ledat.platform.api.scheduler.LeDatTask;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScopedSchedulerOnlinePlayerTest {
    @Test
    @SuppressWarnings("unchecked")
    void onlinePlayerDispatchUsesRetirementSafeEntityOperation() {
        LeDatScheduler backend = mock(LeDatScheduler.class);
        Plugin owner = mock(Plugin.class);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();

        when(player.isOnline()).thenReturn(true);
        when(backend.sync(eq(owner), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return LeDatTask.NOOP;
        });
        when(backend.entityOperation(eq(owner), eq(player), any(Supplier.class))).thenAnswer(invocation -> {
            Supplier<Object> operation = invocation.getArgument(2);
            return CompletableFuture.completedFuture(EntityTaskOutcome.executed(operation.get()));
        });

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);

            new LeDatScheduler.ScopedScheduler(backend, owner)
                    .runOnlinePlayer(playerId, ignored -> calls.incrementAndGet());
        }

        assertEquals(1, calls.get());
        verify(backend).entityOperation(eq(owner), eq(player), any(Supplier.class));
        verify(backend, never()).entity(eq(owner), eq(player), any(Runnable.class));
    }
}
