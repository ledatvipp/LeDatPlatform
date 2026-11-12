package vn.ledat.platform.core.dialog;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import vn.ledat.platform.core.bedrockform.BedrockTextRenderer;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogRichTextRendererTest {

    @Test
    void realRuntimeObjectBridgeKeepsSemanticTextForBedrock() {
        Plugin plugin = plugin();
        DialogRichTextRenderer renderer = new DialogRichTextRenderer(plugin, net.kyori.adventure.text.minimessage.MiniMessage.miniMessage());
        BedrockTextRenderer bedrock = new BedrockTextRenderer(plugin);

        assertTrue(renderer.available());
        assertEquals("Diamond Sword", bedrock.render(renderer.itemSprite("diamond_sword")));
        assertEquals("LeDatPlayer", bedrock.render(renderer.playerHead("LeDatPlayer")));
    }

    @Test
    void itemTagUsesItemSpritesForBlockMaterialsWithItemTextures() throws Exception {
        DialogRichTextRenderer renderer = new DialogRichTextRenderer(plugin(), net.kyori.adventure.text.minimessage.MiniMessage.miniMessage());

        assertSprite(renderer, "hopper", "minecraft:items", "minecraft:item/hopper");
        assertSprite(renderer, "barrier", "minecraft:items", "minecraft:item/barrier");
        assertSprite(renderer, "comparator", "minecraft:items", "minecraft:item/comparator");
        assertSprite(renderer, "bell", "minecraft:items", "minecraft:item/bell");
    }

    @Test
    void itemTagKeepsBlockAtlasForRegularBlockTextures() throws Exception {
        DialogRichTextRenderer renderer = new DialogRichTextRenderer(plugin(), net.kyori.adventure.text.minimessage.MiniMessage.miniMessage());

        assertSprite(materialSpriteRef(renderer, "red_concrete", true), "minecraft:blocks", "minecraft:block/red_concrete");
        assertSprite(materialSpriteRef(renderer, "grass_block", true), "minecraft:blocks", "minecraft:block/grass_block_top");
    }

    private static void assertSprite(DialogRichTextRenderer renderer, String material, String atlas, String sprite) throws Exception {
        assertSprite(spriteRef(renderer, material), atlas, sprite);
    }

    private static void assertSprite(Object ref, String atlas, String sprite) throws Exception {
        assertEquals(atlas, accessor(ref, "atlas"));
        assertEquals(sprite, accessor(ref, "sprite"));
    }

    private static Object spriteRef(DialogRichTextRenderer renderer, String material) throws Exception {
        Method method = DialogRichTextRenderer.class.getDeclaredMethod("spriteKey", String.class);
        method.setAccessible(true);
        return method.invoke(renderer, material);
    }

    private static Object materialSpriteRef(DialogRichTextRenderer renderer, String material, boolean block) throws Exception {
        Method method = DialogRichTextRenderer.class.getDeclaredMethod("materialSpriteKey", String.class, boolean.class);
        method.setAccessible(true);
        return method.invoke(renderer, material, block);
    }

    private static String accessor(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return String.valueOf(method.invoke(target));
    }

    private static Plugin plugin() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("dialogs.rich-tags.enabled", true);
        config.set("dialogs.rich-tags.object-components.enabled", true);
        config.set("dialogs.rich-tags.item-sprite-atlas", "minecraft:items");
        config.set("dialogs.rich-tags.item-sprite-prefix", "minecraft:item/");
        config.set("dialogs.rich-tags.block-sprite-atlas", "minecraft:blocks");
        config.set("dialogs.rich-tags.block-sprite-prefix", "minecraft:block/");
        Logger logger = Logger.getLogger("DialogRichTextRendererTest");
        return (Plugin) Proxy.newProxyInstance(
                DialogRichTextRendererTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConfig" -> config;
                    case "getLogger" -> logger;
                    case "getName" -> "LeDatPlatformTest";
                    case "isEnabled" -> true;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == short.class || type == byte.class || type == long.class) {
            return 0;
        }
        if (type == float.class || type == double.class) {
            return 0.0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
