package vn.ledat.platform.core.dialog;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;
import vn.ledat.platform.api.dialog.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultDialogSessionTest {
    @Test
    void foreignPlayerCannotConsumeTheRealPlayersActionAndSubmitIsOneShot() throws Exception {
        try (DialogTestSupport f = new DialogTestSupport()) {
            DefaultDialogService service = f.dialogService();
            NativeDialogBridge bridge = mock(NativeDialogBridge.class);
            DialogTestSupport.set(service, "nativeBridge", bridge);
            AtomicInteger calls = new AtomicInteger();
            DialogSession expected = register(service, f, "a", calls, false);
            Event event = event(bridge, f.player, "a", Map.of());
            Player foreign = mock(Player.class);
            when(foreign.getUniqueId()).thenReturn(UUID.randomUUID());
            when(bridge.eventPlayer(event)).thenReturn(foreign);
            invoke(service, event);
            assertSame(expected, service.session(f.player.getUniqueId()));
            assertTrue(f.queued.isEmpty());
            when(bridge.eventPlayer(event)).thenReturn(f.player);
            invoke(service, event);
            invoke(service, event);
            assertEquals(0, calls.get());
            f.drain();
            assertEquals(1, calls.get());
            assertNull(service.session(f.player.getUniqueId()));
        }
    }

    @Test
    void queuedOldActionCannotRemoveOrDispatchAfterReplacement() throws Exception {
        try (DialogTestSupport f = new DialogTestSupport()) {
            DefaultDialogService service = f.dialogService();
            NativeDialogBridge bridge = mock(NativeDialogBridge.class);
            DialogTestSupport.set(service, "nativeBridge", bridge);
            AtomicInteger calls = new AtomicInteger();
            register(service, f, "a", calls, false);
            invoke(service, event(bridge, f.player, "a", Map.of()));
            service.cleanup(f.player.getUniqueId());
            DialogSession current = register(service, f, "b", calls, false);
            f.drain();
            assertEquals(0, calls.get());
            assertSame(current, service.session(f.player.getUniqueId()));
            invoke(service, event(bridge, f.player, "b", Map.of()));
            f.drain();
            assertEquals(1, calls.get());
        }
    }

    @Test
    void queuedCallbackRechecksQuitCleanupAndOwnerDisable() throws Exception {
        try (DialogTestSupport f = new DialogTestSupport()) {
            DefaultDialogService service = f.dialogService();
            NativeDialogBridge bridge = mock(NativeDialogBridge.class);
            DialogTestSupport.set(service, "nativeBridge", bridge);
            AtomicInteger calls = new AtomicInteger();
            register(service, f, "quit", calls, false);
            invoke(service, event(bridge, f.player, "quit", Map.of()));
            service.cleanup(f.player.getUniqueId());
            when(f.player.isOnline()).thenReturn(false);
            f.drain();
            when(f.player.isOnline()).thenReturn(true);
            register(service, f, "disable", calls, false);
            invoke(service, event(bridge, f.player, "disable", Map.of()));
            when(f.owner.isEnabled()).thenReturn(false);
            f.drain();
            assertEquals(0, calls.get());
        }
    }

    @Test
    void invalidNativePayloadCannotReachMutation() throws Exception {
        try (DialogTestSupport f = new DialogTestSupport()) {
            DefaultDialogService service = f.dialogService();
            NativeDialogBridge bridge = mock(NativeDialogBridge.class);
            DialogTestSupport.set(service, "nativeBridge", bridge);
            AtomicInteger calls = new AtomicInteger();
            register(service, f, "invalid", calls, true);
            invoke(service, event(bridge, f.player, "invalid", Map.of("enabled", "not-a-boolean")));
            f.drain();
            assertEquals(0, calls.get());
            assertNull(service.session(f.player.getUniqueId()));
        }
    }

    private static Event event(NativeDialogBridge bridge, Player player, String key, Map<String, String> inputs) {
        Event event = mock(Event.class);
        when(bridge.eventIdentifier(event)).thenReturn(key);
        when(bridge.eventPlayer(event)).thenReturn(player);
        when(bridge.eventInputs(eq(event), anyList())).thenReturn(inputs);
        return event;
    }

    private static void invoke(DefaultDialogService service, Event event) throws Exception {
        DialogTestSupport.call(service, "handleNativeEvent", new Class<?>[]{Event.class}, event);
    }

    @SuppressWarnings("unchecked")
    private static DialogSession register(DefaultDialogService service, DialogTestSupport f, String id, AtomicInteger calls, boolean input) throws Exception {
        DialogButton button = DialogButton.confirm(Component.text("Save"), null, click -> calls.incrementAndGet());
        PlatformDialog.Builder builder = PlatformDialog.builder(f.owner, id).button(button);
        if (input) builder.input(DialogInputSpec.builder("enabled", DialogInputType.BOOLEAN).build());
        PlatformDialog dialog = builder.build();
        DialogSession session = new DialogSession(f.player.getUniqueId(), f.owner.getName(), id, id, true, Map.of());
        ((Map<UUID, DialogSession>) DialogTestSupport.get(service, "sessions")).put(f.player.getUniqueId(), session);
        Class<?> pending = Class.forName(DefaultDialogService.class.getName() + "$PendingAction");
        var ctor = pending.getDeclaredConstructor(PlatformDialog.class, DialogSession.class, DialogButton.class);
        ctor.setAccessible(true);
        ((Map<String, Object>) DialogTestSupport.get(service, "nativeActions")).put(id, ctor.newInstance(dialog, session, button));
        return session;
    }
}
