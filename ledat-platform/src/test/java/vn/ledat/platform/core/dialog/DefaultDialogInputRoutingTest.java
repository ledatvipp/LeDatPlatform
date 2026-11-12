package vn.ledat.platform.core.dialog;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.ledat.platform.api.bedrockform.*;
import vn.ledat.platform.api.dialog.*;
import vn.ledat.platform.api.gui.GuiClick;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DefaultDialogInputRoutingTest {
    @Test
    void decimalStepsAllowClientFloatPrecisionAndStillRejectHalfSteps() {
        try (DialogTestSupport f = new DialogTestSupport()) {
            PlatformDialog dialog = PlatformDialog.builder(f.owner, "decimal")
                    .input(DialogInputSpec.builder("number", DialogInputType.NUMBER_RANGE).range(0, 100).step(0.01f).build()).build();
            assertEquals("99.98", DefaultDialogService.normalizeDialogInputs(dialog, Map.of("number", "99.98")).get("number"));
            assertThrows(IllegalArgumentException.class, () -> DefaultDialogService.normalizeDialogInputs(dialog, Map.of("number", "99.985")));
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void localizedDuplicateLabelsKeepTheExactOriginalSelectedIdentity() throws Exception {
        try (DialogTestSupport f = new DialogTestSupport()) {
            DefaultDialogService service = f.dialogService();
            BedrockFormService.CustomBuilder form = mock(BedrockFormService.CustomBuilder.class, RETURNS_SELF);
            when(f.forms.custom(any(), anyString())).thenReturn(form);
            when(form.open(f.player)).thenReturn(BedrockFormResult.OPENED);
            String original = "<aqua>" + "identity".repeat(20) + "</aqua>";
            AtomicReference<DialogClick> clicked = new AtomicReference<>();
            DialogInputSpec input = DialogInputSpec.builder("choice", DialogInputType.SINGLE_OPTION)
                    .option("other", Component.text("Cùng nhãn"), false)
                    .option(original, Component.text("Cùng nhãn"), true).build();
            PlatformDialog dialog = PlatformDialog.builder(f.owner, "choose").input(input)
                    .button(DialogButton.confirm(Component.text("Lưu"), null, clicked::set)).build();
            Object result = DialogTestSupport.call(service, "openBedrockForm", new Class<?>[]{org.bukkit.entity.Player.class, PlatformDialog.class}, f.player, dialog);
            assertEquals(BedrockFormResult.OPENED, result);
            verify(form).dropdown(eq("choice"), any(), eq(List.of("Cùng nhãn", "Cùng nhãn")), eq(1));
            ArgumentCaptor<Consumer<BedrockFormClick>> submit = ArgumentCaptor.forClass((Class) Consumer.class);
            verify(form).submit(any(), submit.capture());
            BedrockFormSession session = new BedrockFormSession(f.player.getUniqueId(), f.owner.getName(), "choose", "session", Instant.now(), Instant.now().plusSeconds(60), Map.of());
            submit.getValue().accept(new BedrockFormClick(f.player, session, "submit", Map.of("choice", "Cùng nhãn", "choice.index", 1), null));
            assertEquals(original, clicked.get().input("choice"));
            assertEquals("confirm", clicked.get().actionId());
        }
    }

    @Test
    void typedPayloadValidationRejectsMalformedFieldsAndUnknownKeys() {
        try (DialogTestSupport f = new DialogTestSupport()) {
            PlatformDialog dialog = PlatformDialog.builder(f.owner, "validate")
                    .input(DialogInputSpec.text("name", Component.text("Name")).maxLength(2).build())
                    .input(DialogInputSpec.builder("enabled", DialogInputType.BOOLEAN).build())
                    .input(DialogInputSpec.builder("number", DialogInputType.NUMBER_RANGE).range(2, 60).step(1f).build())
                    .input(DialogInputSpec.builder("choice", DialogInputType.SINGLE_OPTION).option("stable", Component.text("Localized"), true).build()).build();
            Map<String, String> valid = Map.of("name", "😀😀", "enabled", "true", "number", "5", "choice", "stable");
            assertEquals(valid, DefaultDialogService.normalizeDialogInputs(dialog, valid));
            for (Map.Entry<String, String> invalid : List.of(Map.entry("name", "😀😀😀"), Map.entry("enabled", "yes"), Map.entry("number", "NaN"), Map.entry("number", "Infinity"), Map.entry("number", "61"), Map.entry("number", "5.5"), Map.entry("choice", "Localized"))) {
                var payload = new java.util.HashMap<>(valid);
                payload.put(invalid.getKey(), invalid.getValue());
                assertThrows(IllegalArgumentException.class, () -> DefaultDialogService.normalizeDialogInputs(dialog, payload), invalid.toString());
            }
            var missing = new java.util.HashMap<>(valid);
            missing.remove("enabled");
            assertThrows(IllegalArgumentException.class, () -> DefaultDialogService.normalizeDialogInputs(dialog, missing));
            var extra = new java.util.HashMap<>(valid);
            extra.put("unknown", "data");
            assertThrows(IllegalArgumentException.class, () -> DefaultDialogService.normalizeDialogInputs(dialog, extra));
        }
    }

    @Test
    void readOnlyFallbackCannotSubmitMissingInputsButCancelStillRuns() throws Exception {
        try (DialogTestSupport f = new DialogTestSupport()) {
            DefaultDialogService service = f.dialogService();
            AtomicInteger saved = new AtomicInteger();
            AtomicInteger cancelled = new AtomicInteger();
            DialogButton save = new DialogButton("save", Component.text("Lưu"), null, 100, -1, null, false, click -> saved.incrementAndGet());
            DialogButton cancel = new DialogButton("cancel", Component.text("Hủy"), null, 100, -1, null, false, click -> cancelled.incrementAndGet());
            PlatformDialog dialog = PlatformDialog.builder(f.owner, "input").input(DialogInputSpec.text("value", Component.text("Value")).build()).button(save).button(cancel).build();
            GuiClick click = new GuiClick(f.player, null, 0, null, null, null);
            Class<?>[] signature = {PlatformDialog.class, DialogSession.class, DialogButton.class, GuiClick.class};
            DialogTestSupport.call(service, "handleFallbackButton", signature, dialog, null, save, click);
            DialogTestSupport.call(service, "handleFallbackButton", signature, dialog, null, cancel, click);
            assertEquals(0, saved.get());
            assertEquals(1, cancelled.get());
        }
    }

    @Test
    void cancelOnlyDialogDoesNotBecomeABedrockSubmitAction() throws Exception {
        try (DialogTestSupport f = new DialogTestSupport()) {
            DefaultDialogService service = f.dialogService();
            PlatformDialog dialog = PlatformDialog.builder(f.owner, "cancel-only")
                    .button(DialogButton.cancel(Component.text("Hủy"), null, click -> fail("must not submit"))).build();
            assertNull(DialogTestSupport.call(service, "submitButton", new Class<?>[]{PlatformDialog.class}, dialog));
        }
    }
}
