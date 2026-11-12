package vn.ledat.platform.core.dialog;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Renders dialog MiniMessage with LeDat rich object tags.
 *
 * <p>Supported tags:</p>
 * <ul>
 *   <li>{@code <item:diamond_sword>} -> Adventure object sprite component when runtime supports it.</li>
 *   <li>{@code <head:playerName>} -> Adventure player-head object component when runtime supports it.</li>
 * </ul>
 *
 * <p>The implementation is intentionally reflection based because LeDatPlatform still compiles
 * against Paper 1.21.4/Adventure 4.17 while object components are only present on newer runtimes.
 * Unsupported runtimes degrade to a compact text glyph instead of crashing.</p>
 */
final class DialogRichTextRenderer {
    private static final Pattern SAFE_KEY = Pattern.compile("^[a-z0-9_./:-]+$");
    private static final TextColor FALLBACK_ITEM_COLOR = TextColor.color(0x55FFFF);
    private static final TextColor FALLBACK_HEAD_COLOR = TextColor.color(0xFFAA00);
    private static final Map<String, String> ITEM_SPRITE_ALIASES = Map.ofEntries(
            Map.entry("barrier", "barrier"),
            Map.entry("bell", "bell"),
            Map.entry("chest", "chest_minecart"),
            Map.entry("trapped_chest", "chest_minecart"),
            Map.entry("ender_chest", "ender_eye"),
            Map.entry("clock", "clock_00"),
            Map.entry("comparator", "comparator"),
            Map.entry("compass", "compass_00"),
            Map.entry("hopper", "hopper"),
            Map.entry("player_head", "totem_of_undying"),
            Map.entry("zombie_head", "rotten_flesh"),
            Map.entry("skeleton_skull", "bone"),
            Map.entry("wither_skeleton_skull", "coal"),
            Map.entry("creeper_head", "gunpowder"),
            Map.entry("dragon_head", "dragon_breath"),
            Map.entry("piglin_head", "gold_ingot"),
            Map.entry("lectern", "writable_book"),
            Map.entry("recovery_compass", "recovery_compass_00"),
            Map.entry("oak_sign", "oak_sign"),
            Map.entry("spruce_sign", "spruce_sign"),
            Map.entry("birch_sign", "birch_sign"),
            Map.entry("jungle_sign", "jungle_sign"),
            Map.entry("acacia_sign", "acacia_sign"),
            Map.entry("dark_oak_sign", "dark_oak_sign"),
            Map.entry("mangrove_sign", "mangrove_sign"),
            Map.entry("cherry_sign", "cherry_sign"),
            Map.entry("bamboo_sign", "bamboo_sign"),
            Map.entry("crimson_sign", "crimson_sign"),
            Map.entry("warped_sign", "warped_sign"),
            Map.entry("repeater", "repeater"),
            Map.entry("cauldron", "cauldron"),
            Map.entry("brewing_stand", "brewing_stand"),
            Map.entry("flower_pot", "flower_pot"),
            Map.entry("lantern", "lantern"),
            Map.entry("soul_lantern", "soul_lantern"),
            Map.entry("campfire", "campfire"),
            Map.entry("soul_campfire", "soul_campfire"),
            Map.entry("chain", "chain"),
            Map.entry("iron_door", "iron_door"),
            Map.entry("oak_door", "oak_door"),
            Map.entry("spruce_door", "spruce_door"),
            Map.entry("birch_door", "birch_door"),
            Map.entry("jungle_door", "jungle_door"),
            Map.entry("acacia_door", "acacia_door"),
            Map.entry("dark_oak_door", "dark_oak_door"),
            Map.entry("crimson_door", "crimson_door"),
            Map.entry("warped_door", "warped_door"),
            Map.entry("candle", "candle"),
            Map.entry("cake", "cake"),
            Map.entry("tripwire_hook", "tripwire_hook"),
            Map.entry("redstone", "redstone"),
            Map.entry("lever", "lever"),
            Map.entry("redstone_torch", "redstone_torch")
    );
    private static final Map<String, String> BLOCK_SPRITE_ALIASES = Map.of(
            "grass_block", "grass_block_top",
            "tnt", "tnt_side"
    );

    private final Plugin plugin;
    private final MiniMessage miniMessage;
    private final ObjectBridge objectBridge;

    DialogRichTextRenderer(Plugin plugin, MiniMessage miniMessage) {
        this.plugin = plugin;
        this.miniMessage = miniMessage;
        this.objectBridge = new ObjectBridge(plugin);
    }

    Component render(String raw, Map<String, String> placeholders) {
        String applied = replace(raw == null ? "" : raw, placeholders);
        try {
            return nonItalic(miniMessage.deserialize(applied, itemResolver(), headResolver()));
        } catch (Throwable error) {
            plugin.getLogger().log(Level.WARNING, "Could not render rich dialog text; falling back to escaped MiniMessage: " + applied, error);
            return nonItalic(Component.text(applied));
        }
    }

    Component itemSprite(String materialName) {
        if (!enabled()) {
            return fallbackItem(materialName);
        }
        SpriteRef sprite = spriteKey(materialName);
        if (sprite == null || sprite.sprite().isBlank()) {
            return fallbackItem(materialName);
        }
        if (objectBridge.available() && objectComponentsEnabled()) {
            Component object = objectBridge.sprite(sprite.atlas(), sprite.sprite());
            if (object != null) {
                return nonItalic(object.hoverEvent(Component.text(pretty(materialName))));
            }
        }
        return fallbackItem(materialName);
    }

    Component playerHead(String playerNameOrUuid) {
        if (!enabled()) {
            return fallbackHead(playerNameOrUuid);
        }
        String target = playerNameOrUuid == null ? "" : playerNameOrUuid.trim();
        if (target.isBlank()) {
            return fallbackHead(target);
        }
        if (objectBridge.available() && objectComponentsEnabled()) {
            Component object = objectBridge.playerHead(target);
            if (object != null) {
                return nonItalic(object.hoverEvent(Component.text(target)));
            }
        }
        return fallbackHead(target);
    }

    boolean available() {
        return objectBridge.available() && objectComponentsEnabled();
    }

    String status() {
        if (!enabled()) {
            return "disabled";
        }
        if (!objectComponentsEnabled()) {
            return "fallback-only";
        }
        return objectBridge.status();
    }

    private TagResolver itemResolver() {
        return TagResolver.resolver("item", (args, ctx) -> {
            String material = args.popOr("Expected material in <item:material>").value();
            return Tag.inserting(itemSprite(material));
        });
    }

    private TagResolver headResolver() {
        return TagResolver.resolver("head", (args, ctx) -> {
            String target = args.popOr("Expected player name or UUID in <head:player>").value();
            return Tag.inserting(playerHead(target));
        });
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("dialogs.rich-tags.enabled", true);
    }

    private boolean objectComponentsEnabled() {
        return plugin.getConfig().getBoolean("dialogs.rich-tags.object-components.enabled", true);
    }

    private String atlasKey() {
        String raw = plugin.getConfig().getString("dialogs.rich-tags.item-sprite-atlas", "minecraft:items");
        return sanitizeKey(raw, "minecraft:items");
    }

    private String blockAtlasKey() {
        String raw = plugin.getConfig().getString("dialogs.rich-tags.block-sprite-atlas", "minecraft:blocks");
        return sanitizeKey(raw, "minecraft:blocks");
    }

    private String spritePrefix() {
        String raw = plugin.getConfig().getString("dialogs.rich-tags.item-sprite-prefix", "minecraft:item/");
        return sanitizeKey(raw, "minecraft:item/");
    }

    private String blockSpritePrefix() {
        String raw = plugin.getConfig().getString("dialogs.rich-tags.block-sprite-prefix", "minecraft:block/");
        return sanitizeKey(raw, "minecraft:block/");
    }

    private SpriteRef spriteKey(String materialName) {
        String raw = materialName == null ? "" : materialName.trim().toLowerCase(Locale.ROOT);
        if (raw.isBlank()) {
            return null;
        }
        if (raw.startsWith("minecraft:block/") || raw.startsWith("block/")) {
            return new SpriteRef(blockAtlasKey(), sanitizeSprite(raw));
        }
        if (raw.startsWith("minecraft:item/") || raw.startsWith("item/")) {
            return new SpriteRef(atlasKey(), sanitizeSprite(raw));
        }
        if (raw.contains(":")) {
            return new SpriteRef(atlasKey(), sanitizeSprite(raw));
        }
        String id = raw.replace(' ', '_');
        Material material = matchMaterial(id);
        if (material != null) {
            id = material.name().toLowerCase(Locale.ROOT);
            return materialSpriteKey(id, isBlockMaterial(material));
        }
        id = ITEM_SPRITE_ALIASES.getOrDefault(id, id);
        return new SpriteRef(atlasKey(), sanitizeKey(spritePrefix() + id, ""));
    }

    private Material matchMaterial(String id) {
        try {
            return Material.matchMaterial(id.toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private SpriteRef materialSpriteKey(String id, boolean blockSprite) {
        String itemAlias = ITEM_SPRITE_ALIASES.get(id);
        if (itemAlias != null) {
            return new SpriteRef(atlasKey(), sanitizeKey(spritePrefix() + itemAlias, ""));
        }
        String spriteId = blockSprite ? BLOCK_SPRITE_ALIASES.getOrDefault(id, id) : id;
        String atlas = blockSprite ? blockAtlasKey() : atlasKey();
        String prefix = blockSprite ? blockSpritePrefix() : spritePrefix();
        return new SpriteRef(atlas, sanitizeKey(prefix + spriteId, ""));
    }

    private boolean isBlockMaterial(Material material) {
        try {
            return material != null && material.isBlock();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String sanitizeSprite(String raw) {
        String sprite = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!sprite.contains(":") && (sprite.startsWith("item/") || sprite.startsWith("block/"))) {
            sprite = "minecraft:" + sprite;
        }
        return sanitizeKey(sprite, "");
    }

    private Component fallbackItem(String materialName) {
        String glyph = plugin.getConfig().getString("dialogs.rich-tags.fallback.item-glyph", "▣");
        String hover = pretty(materialName);
        return Component.text(glyph == null || glyph.isBlank() ? "▣" : glyph, FALLBACK_ITEM_COLOR)
                .hoverEvent(Component.text(hover == null || hover.isBlank() ? "Item" : hover))
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component fallbackHead(String playerName) {
        String glyph = plugin.getConfig().getString("dialogs.rich-tags.fallback.head-glyph", "☻");
        String hover = playerName == null || playerName.isBlank() ? "Player Head" : playerName;
        return Component.text(glyph == null || glyph.isBlank() ? "☻" : glyph, FALLBACK_HEAD_COLOR)
                .hoverEvent(Component.text(hover))
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component nonItalic(Component component) {
        Component rooted = component == null ? Component.text("") : Component.text("").append(component);
        return rooted.decoration(TextDecoration.ITALIC, false);
    }

    private String replace(String text, Map<String, String> placeholders) {
        String result = text == null ? "" : text;
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue() == null ? "" : entry.getValue();
                boolean allowMini = key != null && (key.endsWith("_colored") || key.endsWith("_badge") || key.endsWith("_formatted"));
                result = result.replace("{" + key + "}", allowMini ? value : miniMessage.escapeTags(value));
            }
        }
        return result;
    }

    private String sanitizeKey(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!SAFE_KEY.matcher(key).matches()) {
            return fallback == null ? "" : fallback;
        }
        return key;
    }

    private String pretty(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Item";
        }
        String normalized = raw.trim()
                .replace("minecraft:item/", "")
                .replace("minecraft:block/", "")
                .replace("minecraft:", "")
                .replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String part : normalized.split(" +")) {
            if (part.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.length() > 1 ? part.substring(1) : "");
        }
        return out.isEmpty() ? raw : out.toString();
    }

    private record SpriteRef(String atlas, String sprite) {
    }

    private static final class ObjectBridge {
        private final boolean available;
        private final String status;
        private final Method componentObject;
        private final Method spriteAtlas;
        private final Method playerHeadString;
        private final Method playerHeadUuid;

        ObjectBridge(Plugin plugin) {
            Method componentObjectMethod = null;
            Method spriteAtlasMethod = null;
            Method playerHeadStringMethod = null;
            Method playerHeadUuidMethod = null;
            boolean ok = false;
            String message;
            try {
                Class<?> objectContents = Class.forName("net.kyori.adventure.text.object.ObjectContents");
                componentObjectMethod = Component.class.getMethod("object", objectContents);
                spriteAtlasMethod = objectContents.getMethod("sprite", Key.class, Key.class);
                playerHeadStringMethod = objectContents.getMethod("playerHead", String.class);
                try {
                    playerHeadUuidMethod = objectContents.getMethod("playerHead", UUID.class);
                } catch (NoSuchMethodException ignored) {
                    playerHeadUuidMethod = null;
                }
                ok = true;
                message = "Adventure object components available";
            } catch (Throwable error) {
                message = error.getClass().getSimpleName() + ": " + error.getMessage();
                try {
                    plugin.getLogger().fine("Adventure object components unavailable: " + message);
                } catch (Throwable ignored) {
                }
            }
            this.available = ok;
            this.status = message;
            this.componentObject = componentObjectMethod;
            this.spriteAtlas = spriteAtlasMethod;
            this.playerHeadString = playerHeadStringMethod;
            this.playerHeadUuid = playerHeadUuidMethod;
        }

        boolean available() {
            return available;
        }

        String status() {
            return status;
        }

        Component sprite(String atlas, String sprite) {
            if (!available || atlas == null || sprite == null || atlas.isBlank() || sprite.isBlank()) {
                return null;
            }
            try {
                Key atlasKey = key(atlas, "minecraft", "items");
                Key spriteKey = key(sprite, "minecraft", "item/barrier");
                Object contents = spriteAtlas.invoke(null, atlasKey, spriteKey);
                Object component = componentObject.invoke(null, contents);
                return component instanceof Component c ? c : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        Component playerHead(String target) {
            if (!available || target == null || target.isBlank()) {
                return null;
            }
            try {
                Object contents;
                if (playerHeadUuid != null && looksLikeUuid(target)) {
                    contents = playerHeadUuid.invoke(null, UUID.fromString(target));
                } else {
                    contents = playerHeadString.invoke(null, target);
                }
                Object component = componentObject.invoke(null, contents);
                return component instanceof Component c ? c : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private Key key(String value, String fallbackNamespace, String fallbackValue) {
            String raw = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (raw.contains(":")) {
                String[] split = raw.split(":", 2);
                return Key.key(split[0].isBlank() ? fallbackNamespace : split[0], split[1].isBlank() ? fallbackValue : split[1]);
            }
            return Key.key(fallbackNamespace, raw.isBlank() ? fallbackValue : raw);
        }

        private boolean looksLikeUuid(String value) {
            try {
                UUID.fromString(value);
                return true;
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
    }
}
