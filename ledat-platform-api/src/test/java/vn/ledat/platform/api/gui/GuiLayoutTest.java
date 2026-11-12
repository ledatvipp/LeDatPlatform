package vn.ledat.platform.api.gui;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GuiLayoutTest {
    private static Map<Character, GuiLayout.Symbol> symbols() {
        return Map.of('#', new GuiLayout.Symbol("border", GuiLayout.Kind.DECORATION),
                '.', new GuiLayout.Symbol("empty", GuiLayout.Kind.EMPTY),
                'C', new GuiLayout.Symbol("rewards", GuiLayout.Kind.COLLECTION),
                'B', new GuiLayout.Symbol("back", GuiLayout.Kind.BUTTON));
    }

    @Test
    void collectionCapacityAndOrderComeFromTheActualImmutableRegion() {
        List<String> rows = new ArrayList<>(List.of("#########", "#CC.CCC.#", "#B......#"));
        Map<Character, GuiLayout.Symbol> mutable = new LinkedHashMap<>(symbols());
        GuiLayout layout = GuiLayout.compile("rewards", rows, mutable);
        rows.set(1, ".........");
        mutable.clear();
        assertEquals(27, layout.size());
        assertEquals(List.of(10, 11, 13, 14, 15), layout.slots("rewards"));
        assertEquals(List.of(19), layout.slots('B'));
        assertThrows(UnsupportedOperationException.class, () -> layout.slots('C').clear());
        assertThrows(UnsupportedOperationException.class, () -> layout.symbols().clear());
    }

    @Test
    void malformedRowsUnknownSymbolsDuplicateIdsAndEmptyCollectionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.compile("x", List.of("short"), symbols()));
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.compile("x", java.util.Collections.nCopies(7, "#########"), symbols()));
        String message = assertThrows(IllegalArgumentException.class,
                () -> GuiLayout.compile("menus/rewards.yml", "rewards", List.of("####?####"), symbols())).getMessage();
        assertTrue(message.contains("menus/rewards.yml"));
        assertTrue(message.contains("menu=rewards"));
        assertTrue(message.contains("row=0 column=4"));
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.compile("x", List.of("#########"), symbols()));
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.compile("x", List.of("#########"), Map.of(
                '#', new GuiLayout.Symbol("same", GuiLayout.Kind.STATIC),
                '.', new GuiLayout.Symbol("same", GuiLayout.Kind.EMPTY))));
    }

    @Test
    void readsExplicitYamlSymbolKindsWithoutInventingActionHandlers() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("menu-id", "daily");
        yaml.set("layout.rows", List.of("EEEEIEEEE"));
        yaml.createSection("layout.symbols.E", Map.of("id", "empty", "type", "EMPTY"));
        yaml.createSection("layout.symbols.I", Map.of("id", "info", "type", "STATIC"));
        assertEquals(List.of(4), GuiLayout.from(yaml).slots("info"));
    }
}
