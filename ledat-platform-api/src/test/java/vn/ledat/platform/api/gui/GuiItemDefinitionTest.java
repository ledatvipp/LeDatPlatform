package vn.ledat.platform.api.gui;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GuiItemDefinitionTest {
    @Test
    void configKeepsTheAuthoredBulletSpacesPlaceholdersAndInstructions() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("material", "PLAYER_HEAD");
        config.set("name", "<aqua>Hồ Sơ</aqua>");
        List<String> lore = new ArrayList<>(List.of(" ", "<dark_gray> ○ <white>Người chơi: <yellow>{player}</yellow>",
                "  <dark_gray> -<yellow> {amount} xu</yellow>", " ", "<#A8D8EA>Nhấn để xem"));
        config.set("lore", lore);
        config.set("head.source", "PLAYER");
        GuiItemDefinition definition = GuiItemDefinition.from(config);
        lore.clear();
        assertEquals(" ", definition.lore().get(0));
        assertTrue(definition.lore().get(2).startsWith("  <dark_gray> -"));
        assertEquals("{player_uuid}", definition.head().uuid());
        assertThrows(UnsupportedOperationException.class, () -> definition.lore().clear());
    }

    @Test
    void malformedHeadMaterialSourceAndAmountHaveAConfigPath() {
        YamlConfiguration yaml = new YamlConfiguration();
        var section = yaml.createSection("menus.profile");
        section.set("material", "BOOK");
        section.set("head.source", "PLAYER");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> GuiItemDefinition.from(section)).getMessage().contains("menus.profile"));
        section.set("material", "PLAYER_HEAD");
        section.set("head.source", "ARBITRARY_URL");
        assertThrows(IllegalArgumentException.class, () -> GuiItemDefinition.from(section));
        assertThrows(IllegalArgumentException.class, () -> new GuiItemDefinition(Material.BOOK, 65, null, List.of(), null, null, null));
    }
}
