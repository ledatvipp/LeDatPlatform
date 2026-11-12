package vn.ledat.platform.core.bedrockform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockItemTextProjectionTest {

    @Test
    void projectsDisplayNameAndLoreComponents() {
        Component object = Component.empty().hoverEvent(Component.text("object-marker"));
        BedrockTextRenderer renderer = new BedrockTextRenderer(
                MiniMessage.miniMessage(),
                component -> component == object ? "Compass" : null,
                "[icon]"
        );
        Component displayName = Component.text("Open <player> ").append(object);
        List<Component> lore = List.of(object, Component.text("Keep me"));

        assertEquals("Open <player> Compass", plain(BedrockItemTextProjection.project(displayName, renderer)));
        assertEquals(List.of("Compass", "Keep me"), BedrockItemTextProjection.project(lore, renderer).stream().map(BedrockItemTextProjectionTest::plain).toList());
        assertEquals("Open <player> ", plain(displayName));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component == null ? Component.empty() : component);
    }
}
