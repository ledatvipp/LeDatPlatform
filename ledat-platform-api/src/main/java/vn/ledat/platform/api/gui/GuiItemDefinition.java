package vn.ledat.platform.api.gui;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Immutable presentation data. Construction never resolves profiles or touches an inventory. */
public record GuiItemDefinition(Material material, int amount, String name, List<String> lore,
                                Integer customModelData, String itemModel, Head head) {
    public GuiItemDefinition {
        Objects.requireNonNull(material, "material");
        if (empty(material)) throw new IllegalArgumentException("GUI item material cannot be air");
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("GUI item amount must be 1 to 64");
        lore = lore == null ? List.of() : List.copyOf(lore);
        if (head != null && material != Material.PLAYER_HEAD) {
            throw new IllegalArgumentException("GUI head requires material PLAYER_HEAD");
        }
    }

    public enum HeadSource { PLAYER, TEXTURE_URL, TEXTURE_HASH, TEXTURE_BASE64 }

    public record Head(HeadSource source, String uuid, String nameFallback, String value, Material fallbackMaterial) {
        public Head {
            Objects.requireNonNull(source, "source");
            uuid = uuid == null ? "" : uuid;
            nameFallback = nameFallback == null ? "" : nameFallback;
            value = value == null ? "" : value;
            fallbackMaterial = fallbackMaterial == null ? Material.PLAYER_HEAD : fallbackMaterial;
            if (empty(fallbackMaterial)) throw new IllegalArgumentException("GUI head fallback material cannot be air");
        }
    }

    public static GuiItemDefinition from(ConfigurationSection section) {
        Objects.requireNonNull(section, "section");
        try {
            Material material = material(section.getString("material", "PAPER"));
            ConfigurationSection headConfig = section.getConfigurationSection("head");
            Head head = headConfig == null ? null : new Head(
                    HeadSource.valueOf(headConfig.getString("source", "PLAYER").toUpperCase(Locale.ROOT)),
                    headConfig.getString("uuid", "{player_uuid}"),
                    headConfig.getString("name-fallback", "{player}"), headConfig.getString("value", ""),
                    material(headConfig.getString("fallback-material", "PLAYER_HEAD")));
            return new GuiItemDefinition(material, section.getInt("amount", 1), section.getString("name"),
                    section.getStringList("lore"), section.isInt("custom-model-data") ? section.getInt("custom-model-data") : null,
                    section.getString("item-model"), head);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(section.getCurrentPath() + ": " + error.getMessage(), error);
        }
    }

    private static Material material(String name) {
        Material value = name == null ? null : Material.matchMaterial(name);
        if (value == null || empty(value)) throw new IllegalArgumentException("Invalid GUI item material: " + name);
        return value;
    }

    private static boolean empty(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }
}
