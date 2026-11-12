package vn.ledat.platform.core.bedrockform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockTextRendererTest {

    @Test
    void preservesStyledUnicodeAndLiteralAngleText() {
        BedrockTextRenderer renderer = renderer(component -> null);
        Component input = Component.text("Xin chào ", NamedTextColor.GREEN)
                .append(Component.text("😀 <player>"));

        assertEquals("Xin chào 😀 <player>", renderer.render(input));
        assertEquals("Buy <player>", renderer.renderMiniMessage("<green>Buy</green> <player>"));
    }

    @Test
    void projectsObjectAltWithoutDroppingAdjacentText() {
        Component object = Component.empty().hoverEvent(Component.text("object-marker"));
        BedrockTextRenderer renderer = renderer(component -> component == object ? "Diamond Sword" : null);

        assertEquals("Diamond Sword", renderer.render(object));
        assertEquals("Use Diamond Sword now", renderer.render(Component.text("Use ").append(object).append(Component.text(" now"))));
    }

    @Test
    void handlesNullAndClampsByCodePoint() {
        BedrockTextRenderer renderer = renderer(component -> null);

        assertEquals("", renderer.render(null));
        assertEquals("A😀", renderer.clamp("A😀B", 2));
    }

    private static BedrockTextRenderer renderer(BedrockTextRenderer.ObjectAltResolver resolver) {
        return new BedrockTextRenderer(MiniMessage.miniMessage(), resolver, "[icon]");
    }
}
