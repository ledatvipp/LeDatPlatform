package vn.ledat.platform.core.bedrockform;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import vn.ledat.platform.api.bedrockform.*;
import vn.ledat.platform.core.dialog.DialogTestSupport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DefaultBedrockFormServiceTest {
    @Test
    void decimalSliderStepsUseFloatPrecisionWithoutAcceptingHalfSteps() throws Exception {
        try (Fixture f = new Fixture()) {
            BedrockForm form = f.service.custom(f.base.owner, "decimal")
                    .slider("number", Component.text("Number"), 0, 100, 0.01f, 5).build();
            assertEquals(99.98f, DefaultBedrockFormService.normalizeCustomInputs(form, Map.of("number", 99.98f)).get("number"));
            assertThrows(IllegalArgumentException.class, () -> DefaultBedrockFormService.normalizeCustomInputs(form, Map.of("number", 99.985f)));
        }
    }

    @Test
    void originalOptionIdentitySurvivesDuplicateAndClippedDisplayLabels() throws Exception {
        try (Fixture f = new Fixture()) {
            String original = "<aqua>" + "identity".repeat(20) + "</aqua>";
            AtomicReference<BedrockFormClick> action = new AtomicReference<>();
            f.service.custom(f.base.owner, "identity")
                    .dropdown("choice", Component.text("Choice"), List.of("other", original), 1)
                    .submit(Component.text("Save"), action::set).open(f.base.player);
            f.base.drain();
            f.handler.get().valid(0, Map.of("choice", "display-only", "choice.index", 1), null);
            assertNull(action.get());
            f.base.drain();
            assertEquals(original, action.get().inputString("choice", ""));
            assertEquals(1, action.get().inputInt("choice.index", -1));
            assertEquals(1, f.service.stats().submitted());
        }
    }

    @Test
    void invalidCustomTypesKeysNumbersAndIndicesCannotReachMutation() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger calls = new AtomicInteger();
            BedrockForm form = f.service.custom(f.base.owner, "validate")
                    .input("name", Component.text("Name"), "", "")
                    .toggle("enabled", Component.text("Enabled"), true)
                    .slider("number", Component.text("Number"), 2, 60, 1, 5)
                    .dropdown("choice", Component.text("Choice"), List.of("a", "b"), 0)
                    .submit(Component.text("Save"), click -> calls.incrementAndGet()).build();
            Map<String, Object> valid = Map.of("name", "Player", "enabled", true, "number", 5f, "choice", "a", "choice.index", 0);
            assertEquals(valid, DefaultBedrockFormService.normalizeCustomInputs(form, valid));
            for (Map.Entry<String, Object> invalid : List.<Map.Entry<String, Object>>of(
                    Map.entry("name", 12), Map.entry("enabled", "true"), Map.entry("number", "5"),
                    Map.entry("number", Float.NaN), Map.entry("number", Float.POSITIVE_INFINITY),
                    Map.entry("number", 61f), Map.entry("number", 5.5f),
                    Map.entry("choice.index", -1), Map.entry("choice.index", 2), Map.entry("choice.index", 0.5), Map.entry("choice.index", "0"))) {
                HashMap<String, Object> payload = new HashMap<>(valid);
                payload.put(invalid.getKey(), invalid.getValue());
                f.service.open(f.base.player, form);
                f.base.drain();
                f.handler.get().valid(0, payload, null);
                f.base.drain();
                assertEquals(0, calls.get(), invalid.toString());
                assertNull(f.service.session(f.base.player.getUniqueId()));
            }
            HashMap<String, Object> missing = new HashMap<>(valid);
            missing.remove("enabled");
            assertThrows(IllegalArgumentException.class, () -> DefaultBedrockFormService.normalizeCustomInputs(form, missing));
            HashMap<String, Object> extra = new HashMap<>(valid);
            extra.put("unknown", false);
            assertThrows(IllegalArgumentException.class, () -> DefaultBedrockFormService.normalizeCustomInputs(form, extra));
        }
    }

    @Test
    void queuedSubmitAndCloseCannotAffectReplacementAndDuplicatesRunOnce() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger closes = new AtomicInteger();
            BedrockForm a = f.simple("a", click -> calls.incrementAndGet(), close -> closes.incrementAndGet());
            f.service.open(f.base.player, a);
            f.base.drain();
            BedrockFormBridge.BridgeResponseHandler old = f.handler.get();
            old.valid(0, Map.of(), null);
            old.closed(null);
            f.replace(f.simple("b", click -> calls.incrementAndGet(), close -> closes.incrementAndGet()));
            BedrockFormSession current = f.service.session(f.base.player.getUniqueId());
            f.base.drain();
            assertEquals(0, calls.get());
            assertEquals(0, closes.get());
            assertSame(current, f.service.session(f.base.player.getUniqueId()));
            f.handler.get().valid(0, Map.of(), null);
            f.handler.get().valid(0, Map.of(), null);
            f.base.drain();
            assertEquals(1, calls.get());
            assertEquals(0, closes.get());
            assertNull(f.service.session(f.base.player.getUniqueId()));
        }
    }

    @Test
    void queuedCallbackRechecksQuitOwnerDisableAndCleanup() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger calls = new AtomicInteger();
            f.service.open(f.base.player, f.simple("quit", click -> calls.incrementAndGet(), close -> calls.incrementAndGet()));
            f.base.drain();
            f.handler.get().valid(0, Map.of(), null);
            f.service.cleanup(f.base.player.getUniqueId());
            when(f.base.player.isOnline()).thenReturn(false);
            f.base.drain();
            when(f.base.player.isOnline()).thenReturn(true);
            f.service.open(f.base.player, f.simple("disabled", click -> calls.incrementAndGet(), close -> calls.incrementAndGet()));
            f.base.drain();
            f.handler.get().valid(0, Map.of(), null);
            when(f.base.owner.isEnabled()).thenReturn(false);
            f.base.drain();
            assertEquals(0, calls.get());
        }
    }

    @Test
    void foreignCloseInvalidAndSubmitCannotConsumeTheOwnedSession() throws Exception {
        try (Fixture f = new Fixture()) {
            f.service.open(f.base.player, f.simple("owned", click -> fail("foreign mutation"), close -> fail("foreign close")));
            f.base.drain();
            BedrockFormSession expected = f.service.session(f.base.player.getUniqueId());
            Player foreign = mock(Player.class);
            when(foreign.getUniqueId()).thenReturn(UUID.randomUUID());
            DialogTestSupport.call(f.service, "handleValid", new Class<?>[]{Player.class, BedrockFormSession.class, int.class, Map.class, Object.class}, foreign, expected, 0, Map.of(), null);
            DialogTestSupport.call(f.service, "handleClosed", new Class<?>[]{Player.class, BedrockFormSession.class, Object.class}, foreign, expected, null);
            DialogTestSupport.call(f.service, "handleInvalid", new Class<?>[]{Player.class, BedrockFormSession.class, Object.class}, foreign, expected, null);
            f.base.drain();
            assertSame(expected, f.service.session(f.base.player.getUniqueId()));
        }
    }

    @Test
    void closeDoesNotSubmitAndNestedFormCanSubmitImmediately() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger closes = new AtomicInteger();
            f.service.open(f.base.player, f.simple("close", click -> calls.incrementAndGet(), close -> closes.incrementAndGet()));
            f.base.drain();
            f.handler.get().closed(null);
            f.base.drain();
            assertEquals(0, calls.get());
            assertEquals(1, closes.get());
            f.service.open(f.base.player, f.simple("parent", click -> {
                calls.incrementAndGet();
                f.service.open(f.base.player, f.simple("child", child -> calls.incrementAndGet(), close -> closes.incrementAndGet()));
            }, close -> closes.incrementAndGet()));
            f.base.drain();
            f.handler.get().valid(0, Map.of(), null);
            f.base.drain();
            assertEquals("child", f.service.session(f.base.player.getUniqueId()).menuId());
            f.handler.get().valid(0, Map.of(), null);
            f.base.drain();
            assertEquals(2, calls.get());
            assertEquals(1, closes.get());
        }
    }

    @Test
    void ambiguousReservedChoiceKeysAreRejectedBeforeSending() throws Exception {
        try (Fixture f = new Fixture()) {
            BedrockForm invalid = f.service.custom(f.base.owner, "bad-keys")
                    .dropdown("choice", Component.text("Choice"), List.of("A"), 0)
                    .input("choice.index", Component.text("Collision"), "", "").build();
            f.service.open(f.base.player, invalid);
            f.base.drain();
            assertEquals(1, f.service.stats().failedOpens());
            assertEquals(0, f.service.sessionCount());
            verify(f.bridge, never()).send(any(), any(), any());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DialogTestSupport base = new DialogTestSupport();
        final DefaultBedrockFormService service = new DefaultBedrockFormService(base.owner, base.languages, base.scheduler);
        final BedrockFormBridge bridge = mock(BedrockFormBridge.class);
        final AtomicReference<BedrockFormBridge.BridgeResponseHandler> handler = new AtomicReference<>();

        Fixture() throws Exception {
            when(bridge.available()).thenReturn(true);
            when(bridge.isBedrock(any())).thenReturn(true);
            when(bridge.send(any(), any(), any())).thenAnswer(call -> { handler.set(call.getArgument(2)); return true; });
            DialogTestSupport.set(service, "bridge", bridge);
        }

        BedrockForm simple(String id, Consumer<BedrockFormClick> action, Consumer<BedrockFormClose> close) {
            return service.simple(base.owner, id).button("go", Component.text("Go"), action).onClose(close).build();
        }

        void replace(BedrockForm form) throws Exception {
            DialogTestSupport.call(service, "openOnEntity", new Class<?>[]{Player.class, BedrockForm.class}, base.player, form);
        }

        public void close() { base.close(); }
    }
}
