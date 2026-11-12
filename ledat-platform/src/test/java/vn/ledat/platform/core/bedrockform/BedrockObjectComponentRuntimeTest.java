package vn.ledat.platform.core.bedrockform;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockObjectComponentRuntimeTest {

    @Test
    void resolvesRealAdventureSpriteObjectOnSupportedRuntime() throws Exception {
        Class<?> objectContents = Class.forName("net.kyori.adventure.text.object.ObjectContents");
        Object sprite = objectContents.getMethod("sprite", Key.class, Key.class).invoke(
                null,
                Key.key("minecraft", "items"),
                Key.key("minecraft", "item/diamond_sword")
        );
        Method objectFactory = Component.class.getMethod("object", objectContents);
        Component component = (Component) objectFactory.invoke(null, sprite);

        assertEquals("Diamond Sword", new BedrockTextRenderer(plugin()).render(component));
    }

    @Test
    void resolvesRealAdventurePlayerHeadNameWithoutChangingCase() throws Exception {
        Class<?> objectContents = Class.forName("net.kyori.adventure.text.object.ObjectContents");
        Object head = objectContents.getMethod("playerHead", String.class).invoke(null, "LeDatPlayer");
        Component component = (Component) Component.class.getMethod("object", objectContents).invoke(null, head);

        assertEquals("LeDatPlayer", new BedrockTextRenderer(plugin()).render(component));
    }

    private static Plugin plugin() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("forms.text.object-fallback", "[icon]");
        Logger logger = Logger.getLogger("BedrockObjectComponentRuntimeTest");
        return (Plugin) Proxy.newProxyInstance(
                BedrockObjectComponentRuntimeTest.class.getClassLoader(),
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
