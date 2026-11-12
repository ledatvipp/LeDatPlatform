package vn.ledat.platform.core.bedrockform;

import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import vn.ledat.platform.api.bedrockform.BedrockForm;
import vn.ledat.platform.api.bedrockform.BedrockFormButton;
import vn.ledat.platform.api.bedrockform.BedrockFormDisplayMode;
import vn.ledat.platform.api.bedrockform.BedrockFormInput;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockFormRendererTest {

    @Test
    void titleClippingNeverChangesTheTextInputInitialValue() {
        Plugin plugin = plugin(16, 128);
        String initial = "😀".repeat(80) + "<literal>";
        BedrockForm form = new BedrockForm(plugin, "edit", BedrockFormDisplayMode.CUSTOM, Component.text("Edit"), Component.empty(),
                List.of(), List.of(BedrockFormInput.text("text", Component.text("Label"), "Placeholder", initial)), Map.of(), close -> { });
        assertEquals(initial, new BedrockFormRenderer(plugin).render(form).inputs().getFirst().initial());
    }

    @Test
    void preservesLiteralAngleTextAndUsesIdsOnlyForBlankLabels() {
        Plugin plugin = plugin(16, 128);
        String longTitle = "😀".repeat(17);
        BedrockForm form = new BedrockForm(
                plugin,
                "menu-id",
                BedrockFormDisplayMode.SIMPLE,
                Component.text(longTitle),
                Component.text("Body <target>"),
                List.of(
                        BedrockFormButton.of("open", Component.text("<player>"), click -> { }),
                        BedrockFormButton.of("close", Component.empty(), click -> { })
                ),
                List.of(BedrockFormInput.dropdown("choice", Component.text("Choose"), List.of("<red>One</red>", "<player>"), 0)),
                Map.of(),
                close -> { }
        );

        RenderedBedrockForm rendered = new BedrockFormRenderer(plugin).render(form);

        assertEquals("😀".repeat(16), rendered.title());
        assertEquals("Body <target>", rendered.content());
        assertEquals("<player>", rendered.buttons().get(0).label());
        assertEquals("close", rendered.buttons().get(1).label());
        assertEquals(List.of("One", "<player>"), rendered.inputs().get(0).options());
    }

    private static Plugin plugin(int maxTitle, int maxContent) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("forms.text.max-title-length", maxTitle);
        config.set("forms.text.max-content-length", maxContent);
        config.set("forms.text.object-fallback", "[icon]");
        Logger logger = Logger.getLogger("BedrockFormRendererTest");
        return (Plugin) Proxy.newProxyInstance(
                BedrockFormRendererTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConfig" -> config;
                    case "getLogger" -> logger;
                    case "getName" -> "LeDatPlatformTest";
                    case "isEnabled" -> true;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class || type == short.class || type == byte.class || type == long.class) return 0;
        if (type == float.class || type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        return null;
    }
}
