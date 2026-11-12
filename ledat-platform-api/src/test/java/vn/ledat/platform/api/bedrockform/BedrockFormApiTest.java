package vn.ledat.platform.api.bedrockform;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockFormApiTest {
    @Test
    void disabledServiceNoOps() {
        BedrockFormService service = BedrockFormService.disabled();

        assertFalse(service.isAvailable());
        assertFalse(service.canOpen(null));
        assertEquals(BedrockFormResult.DISABLED, service.simple(null, "shop")
                .title(Component.text("Shop"))
                .button("blocks", Component.text("Blocks"), click -> { })
                .open(null));
        assertEquals(0, service.stats().sessions());
    }

    @Test
    void sessionExpiryUsesExpiresAt() {
        BedrockFormSession active = new BedrockFormSession(UUID.randomUUID(), "Plugin", "menu", "session",
                Instant.now(), Instant.now().plusSeconds(30), Map.of());
        BedrockFormSession expired = new BedrockFormSession(UUID.randomUUID(), "Plugin", "menu", "session",
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(1), Map.of());

        assertFalse(active.expired());
        assertTrue(expired.expired());
    }

    @Test
    void clickInputsConvertSafely() {
        BedrockFormClick click = new BedrockFormClick(null, null, "submit", Map.of(
                "name", "LeDat",
                "enabled", "true",
                "amount", "12",
                "ratio", 1.5f
        ), null);

        assertEquals("LeDat", click.inputString("name", ""));
        assertTrue(click.inputBoolean("enabled", false));
        assertEquals(12, click.inputInt("amount", 0));
        assertEquals(1.5f, click.inputFloat("ratio", 0f));
    }
}
