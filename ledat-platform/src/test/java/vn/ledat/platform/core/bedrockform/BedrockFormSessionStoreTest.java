package vn.ledat.platform.core.bedrockform;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import vn.ledat.platform.api.bedrockform.BedrockForm;
import vn.ledat.platform.api.bedrockform.BedrockFormDisplayMode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BedrockFormSessionStoreTest {
    @Test
    void takesOneSessionExactlyOnce() {
        BedrockFormSessionStore store = new BedrockFormSessionStore();
        UUID playerId = UUID.randomUUID();
        BedrockFormSessionStore.StoredSession stored = store.put(
                playerId, form("root"), rendered(), "session-a", Instant.now().plusSeconds(60));

        assertSame(stored, store.take(playerId, "session-a"));
        assertNull(store.take(playerId, "session-a"));
        assertEquals(0, store.size());
    }

    @Test
    void staleResponseCannotRemoveNewNestedSession() {
        BedrockFormSessionStore store = new BedrockFormSessionStore();
        UUID playerId = UUID.randomUUID();
        store.put(playerId, form("root"), rendered(), "session-a", Instant.now().plusSeconds(60));
        BedrockFormSessionStore.StoredSession child = store.put(
                playerId, form("child"), rendered(), "session-b", Instant.now().plusSeconds(60));

        assertNull(store.take(playerId, "session-a"));
        assertSame(child, store.get(playerId));
        assertNotNull(store.take(playerId, "session-b"));
    }

    @Test
    void newNestedSessionCanSubmitImmediatelyAfterParent() {
        BedrockFormSessionStore store = new BedrockFormSessionStore();
        UUID playerId = UUID.randomUUID();
        store.put(playerId, form("root"), rendered(), "session-a", Instant.now().plusSeconds(60));

        assertNotNull(store.take(playerId, "session-a"));
        store.put(playerId, form("child"), rendered(), "session-b", Instant.now().plusSeconds(60));

        assertNotNull(store.take(playerId, "session-b"));
        assertEquals(0, store.size());
    }

    private BedrockForm form(String menuId) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("TestPlugin");
        return new BedrockForm(
                plugin,
                menuId,
                BedrockFormDisplayMode.SIMPLE,
                Component.text(menuId),
                Component.empty(),
                List.of(),
                List.of(),
                Map.of(),
                close -> { });
    }

    private RenderedBedrockForm rendered() {
        return new RenderedBedrockForm(BedrockFormDisplayMode.SIMPLE, "Title", "", List.of(), List.of());
    }
}
